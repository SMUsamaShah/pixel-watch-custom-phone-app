package com.usamashah.pixelvault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val prefs = applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private var startedAt = System.currentTimeMillis()

    override suspend fun doWork(): Result {
        if (!exportMutex.tryLock()) return Result.retry()
        try { return withContext(Dispatchers.IO) { runExport() } }
        finally { exportMutex.unlock() }
    }

    private suspend fun report(message: String, done: Int = 0, total: Int = 0) {
        currentCoroutineContext().ensureActive()
        setProgress(workDataOf("message" to message, "startedAt" to startedAt,
            "updatedAt" to System.currentTimeMillis(), "done" to done, "total" to total))
        setForeground(foregroundInfo(message, done, total))
    }

    private suspend fun runExport(): Result {
        val dir = File(applicationContext.filesDir, "exports").apply { mkdirs() }
        val pending = File(dir, "pending-$id.zip")
        val completed = File(dir, "latest.zip")
        try {
            startedAt = System.currentTimeMillis()
            report("Starting export…")
            val summary: String
            if (inputData.getBoolean("saveOnly", false)) {
                check(completed.exists()) { "No completed export to save" }
                summary = prefs.getString("lastScan", "Completed export")!!
            } else {
                val typeTotal = HealthConnectFeed.recordTypes.size +
                    (if (prefs.getBoolean("cloud", false)) GoogleHealthFeed.types.size else 0) +
                    (if (prefs.getBoolean("medical", false)) 12 else 0)
                val manifest: JsonObject
                VaultArchive(pending, onProgress = { vault, row ->
                    val records = vault.coverage.sumOf { it.records }
                    val samples = vault.coverage.sumOf { it.samples }
                    val checked = vault.coverage.count { it.status != "pending" }
                    val current = "${row.feed} / ${row.type.removeSuffix("Record")}"
                    val phase = if (row.status == "pending") "Reading $current" else "Checked $current: ${row.status.replace('_', ' ')}"
                    val latest = row.latestMeasurementAt?.let { "\nLatest measurement: ${displayTime(it)}" } ?: ""
                    report("$phase\n$checked of $typeTotal types checked · $records records · $samples samples\n" +
                        "This type: ${row.records} records · ZIP so far: ${fileSize(pending.length())}$latest", checked, typeTotal)
                }).use { vault ->
                    if (HealthConnectFeed.providerAvailable(applicationContext)) {
                        val client = HealthConnectClient.getOrCreate(applicationContext)
                        try {
                            HealthConnectFeed.export(client, vault, inputData.getBoolean("scheduled", false),
                                prefs.getBoolean("medical", false)) { waiting -> report(waiting + "\nZIP so far: ${fileSize(pending.length())}") }
                        } catch (e: Exception) {
                            if (e is CancellationException || e is VaultWriteException) throw e
                            vault.feedStatus[HealthConnectFeed.FEED] = "access_error_${e.javaClass.simpleName}"
                            vault.notes += "Health Connect access failed: ${sourceError(e)}"
                            val reached = vault.coverage.map { it.type }.toSet()
                            HealthConnectFeed.recordTypes.filter { it.java.simpleName !in reached }.forEach {
                                vault.unavailable(HealthConnectFeed.FEED, it.java.simpleName, "not_queried_access_error")
                            }
                        }
                    } else {
                        vault.feedStatus[HealthConnectFeed.FEED] = "provider_unavailable"
                        HealthConnectFeed.recordTypes.forEach { vault.unavailable(HealthConnectFeed.FEED, it.java.simpleName, "provider_unavailable") }
                    }
                    if (prefs.getBoolean("cloud", false)) report("Reading Google Health/Fitbit cloud data…")
                    GoogleHealthFeed.export(applicationContext, vault, prefs.getBoolean("cloud", false))
                    report("Finishing ZIP and coverage report…")
                    manifest = vault.finish()
                }
                check(pending.renameTo(completed)) { "Unable to commit finished export" }
                File(dir, "manifest.json").writeText(Gson().toJson(manifest))
                summary = summary(manifest)
                prefs.edit().putString("lastScan", summary).putString("lastGeneratedAt", Instant.now().toString()).apply()
            }
            val destination = prefs.getString("destination", null)
            if (destination != null) {
                try {
                    report("ZIP finished: ${fileSize(completed.length())}. Opening the selected destination…")
                    applicationContext.contentResolver.openOutputStream(Uri.parse(destination), "wt")?.use { out ->
                        completed.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            var updated = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                out.write(buffer, 0, size)
                                copied += size
                                if (System.currentTimeMillis() - updated >= 1000 || copied == completed.length()) {
                                    val percent = (copied * 100 / completed.length().coerceAtLeast(1)).toInt()
                                    report("Saving ZIP: $percent%\n${fileSize(copied)} of ${fileSize(completed.length())} copied to the selected file", percent, 100)
                                    updated = System.currentTimeMillis()
                                }
                            }
                            out.flush()
                        }
                    } ?: error("Destination unavailable")
                    prefs.edit().putString("lastStatus", "$summary\nSaved ${fileSize(completed.length())} to the chosen file.").apply()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    prefs.edit().putString("lastStatus", "$summary\nSaving failed (${sourceError(e)}). The finished ZIP is retained. Choose a destination and tap Save last completed ZIP.").apply()
                    return Result.failure(workDataOf("message" to "Export finished; saving failed"))
                }
            } else prefs.edit().putString("lastStatus", "$summary\nFinished locally. Choose a file and tap Save last completed ZIP.").apply()
            return Result.success(workDataOf("message" to summary.take(4000)))
        } catch (e: CancellationException) {
            prefs.edit().putString("lastStatus", "Export cancelled or interrupted. The previous completed local ZIP has been retained. Refresh today's data or start a new export.").apply()
            throw e
        } catch (e: Exception) {
            val failure = "Export failed: ${sourceError(e)}. The previous completed local ZIP has been retained."
            prefs.edit().putString("lastStatus", failure).apply()
            return Result.failure(workDataOf("message" to failure))
        } finally { pending.delete() }
    }

    private fun foregroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("export", "Health data export", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "export")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pixel Data Vault")
            .setContentText(message.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setProgress(total, done, total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(7, notification)
    }

    companion object {
        private val exportMutex = Mutex()
        const val WORK = "vault-export"
        private const val DAILY = "vault-daily"
        fun hasCompletedExport(context: Context) = File(context.filesDir, "exports/latest.zip").exists()
        fun selectDestination(context: Context, uri: Uri) {
            context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().putString("destination", uri.toString()).apply()
        }
        fun once(context: Context, saveOnly: Boolean = false) = WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf("saveOnly" to saveOnly)).addTag(WORK).build())
        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK)
            wm.cancelUniqueWork(DAILY)
            context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().putBoolean("daily", false).apply()
        }
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(DAILY); return }
            val cloud = context.getSharedPreferences("vault", Context.MODE_PRIVATE).getBoolean("cloud", false)
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ExportWorker>(24, TimeUnit.HOURS)
                    .setInputData(workDataOf("scheduled" to true)).addTag(WORK).addTag(DAILY)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (cloud) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true).build()).build())
        }
        fun fileSize(bytes: Long): String = String.format(Locale.UK, "%.2f MB", bytes / 1_000_000.0)
        fun displayTime(time: String): String = Instant.parse(time).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z"))
        fun summary(manifest: JsonObject): String {
            val rows = manifest.getAsJsonArray("coverage").map { it.asJsonObject }
            val total = rows.sumOf { it["records"].asLong }
            val samples = rows.sumOf { it["samples"].asLong }
            val incomplete = rows.filter { it["status"].asString == "incomplete" }
            val lines = mutableListOf("$total source-labelled records and $samples samples exported.")
            val localStatus = manifest.getAsJsonObject("feeds")[HealthConnectFeed.FEED]?.asString
            if (localStatus != null && localStatus != "connected") {
                lines += "Health Connect: ${localStatus.replace('_', ' ')}. See the coverage report for missing reads."
            }
            if (incomplete.isNotEmpty()) {
                lines += "Partial export: ${incomplete.size} types could not be read completely."
                incomplete.take(3).forEach { lines += "${it["type"].asString}: ${it["error"]?.asString}" }
            }
            for ((label, hc, cloud) in listOf(
                Triple("Heart rate", "HeartRateRecord", "heart-rate"), Triple("Steps", "StepsRecord", "steps"),
                Triple("Sleep", "SleepSessionRecord", "sleep"), Triple("HRV", "HeartRateVariabilityRmssdRecord", "daily-heart-rate-variability"),
                Triple("Resting HR", "RestingHeartRateRecord", "daily-resting-heart-rate")
            )) for (type in listOf(hc, cloud)) {
                val row = rows.firstOrNull { it["type"].asString == type } ?: continue
                val time = row["latestMeasurementAt"]?.takeUnless { it.isJsonNull }?.asString
                val date = row["latestMeasurementDate"]?.takeUnless { it.isJsonNull }?.asString
                val latest = time?.let { displayTime(it) } ?: date ?:
                    if (row["status"].asString == "read_complete") "no measurements returned" else row["status"].asString.replace('_', ' ')
                lines += "$label (${row["feed"].asString}): $latest"
            }
            return lines.joinToString("\n")
        }
    }
}
