package com.usamashah.pixelvault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = exportMutex.withLock { withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)
        val dir = File(applicationContext.filesDir, "exports").apply { mkdirs() }
        val pending = File(dir, "pending-$id.zip")
        try {
            setForeground(foregroundInfo())
            setProgress(workDataOf("message" to "Reading all accessible history…"))
            val manifest: JsonObject
            VaultArchive(pending).use { vault ->
                if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
                    val client = HealthConnectClient.getOrCreate(applicationContext)
                    try {
                        HealthConnectFeed.export(client, vault, inputData.getBoolean("scheduled", false), prefs.getBoolean("medical", false))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException || e is VaultWriteException) throw e
                        vault.feedStatus[HealthConnectFeed.FEED] = "access_error_${e.javaClass.simpleName}"
                        val reached = vault.coverage.map { it.type }.toSet()
                        HealthConnectFeed.recordTypes.filter { it.java.simpleName !in reached }.forEach {
                            vault.unavailable(HealthConnectFeed.FEED, it.java.simpleName, "not_queried_access_error")
                        }
                    }
                } else {
                    vault.feedStatus[HealthConnectFeed.FEED] = "provider_unavailable"
                    HealthConnectFeed.recordTypes.forEach { vault.unavailable(HealthConnectFeed.FEED, it.java.simpleName, "provider_unavailable") }
                }
                setProgress(workDataOf("message" to "Reading Google Health/Fitbit cloud data…"))
                GoogleHealthFeed.export(applicationContext, vault, prefs.getBoolean("cloud", false))
                manifest = vault.finish()
            }
            val completed = File(dir, "latest.zip")
            check(pending.renameTo(completed)) { "Unable to commit finished export" }
            File(dir, "manifest.json").writeText(Gson().toJson(manifest))
            val summary = summary(manifest)
            prefs.edit().putString("lastScan", summary).putString("lastGeneratedAt", Instant.now().toString()).apply()
            val destination = prefs.getString("destination", null)
            if (destination != null) {
                try {
                    applicationContext.contentResolver.openOutputStream(Uri.parse(destination), "wt")?.use { out ->
                        completed.inputStream().use { it.copyTo(out) }
                    } ?: error("Destination unavailable")
                    prefs.edit().putString("lastStatus", "$summary\nSaved to the chosen export file.").apply()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    prefs.edit().putString("lastStatus", "$summary\nExport file could not be updated (${e.javaClass.simpleName}). The finished ZIP is still available in the app; choose a destination again.").apply()
                    return@withContext Result.failure(workDataOf("message" to "Scan finished; destination write failed"))
                }
            } else prefs.edit().putString("lastStatus", "$summary\nChoose an export file to save this ZIP.").apply()
            Result.success(workDataOf("message" to summary))
        } catch (e: kotlinx.coroutines.CancellationException) {
            prefs.edit().putString("lastStatus", "Export was interrupted. Open the app and export again; the previous completed local ZIP has been retained.").apply()
            throw e
        } catch (e: Exception) {
            prefs.edit().putString("lastStatus", "Export failed (${e.javaClass.simpleName}). Open the app and export again; the previous completed local ZIP has been retained.").apply()
            Result.failure(workDataOf("message" to "Export failed: ${e.javaClass.simpleName}"))
        } finally { pending.delete() }
    } }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("export", "Health data export", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "export")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pixel Data Vault")
            .setContentText("Exporting source-labelled health records")
            .setOngoing(true).build()
        return ForegroundInfo(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private val exportMutex = Mutex()
        private const val WORK = "vault-export"
        private const val DAILY = "vault-daily"
        /** Selecting a file and scheduled export cannot write over one another. */
        suspend fun selectDestination(context: Context, uri: Uri): Boolean = exportMutex.withLock {
            withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "exports/latest.zip")
                if (file.exists()) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("Destination unavailable")
                }
                context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit()
                    .putString("destination", uri.toString()).apply()
                file.exists()
            }
        }
        fun once(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ExportWorker>().addTag(WORK).build()
        )
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(DAILY); return }
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ExportWorker>(24, TimeUnit.HOURS)
                    .setInputData(workDataOf("scheduled" to true)).addTag(WORK)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .build())
        }
        fun summary(manifest: JsonObject): String {
            val rows = manifest.getAsJsonArray("coverage").map { it.asJsonObject }
            val total = rows.sumOf { it["records"].asLong }
            val incomplete = rows.count { it["status"].asString == "incomplete" }
            val lines = mutableListOf("$total source-labelled records exported.")
            if (incomplete > 0) lines += "$incomplete types could not be read completely; details are in manifest.json."
            for ((label, hc, cloud) in listOf(
                Triple("Heart rate", "HeartRateRecord", "heart-rate"), Triple("Steps", "StepsRecord", "steps"),
                Triple("Sleep", "SleepSessionRecord", "sleep"), Triple("HRV", "HeartRateVariabilityRmssdRecord", "daily-heart-rate-variability"),
                Triple("Resting HR", "RestingHeartRateRecord", "daily-resting-heart-rate")
            )) {
                for (type in listOf(hc, cloud)) {
                    val row = rows.firstOrNull { it["type"].asString == type } ?: continue
                    val time = row["latestMeasurementAt"]?.takeUnless { it.isJsonNull }?.asString
                    val date = row["latestMeasurementDate"]?.takeUnless { it.isJsonNull }?.asString
                    val latest = time?.let {
                        Instant.parse(it).atZone(java.time.ZoneId.of("Europe/London"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm z"))
                    } ?: date ?: if (row["status"].asString == "read_complete") "no measurements returned" else row["status"].asString.replace('_', ' ')
                    lines += "$label (${row["feed"].asString}): $latest"
                }
            }
            return lines.joinToString("\n")
        }
    }
}
