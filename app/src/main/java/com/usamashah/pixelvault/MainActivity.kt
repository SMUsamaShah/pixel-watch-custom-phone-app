package com.usamashah.pixelvault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("vault", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var cloudStatus: TextView
    private lateinit var scan: Button
    private lateinit var refresh: Button
    private lateinit var cancel: Button
    private lateinit var saveLast: Button
    private lateinit var chooseFile: Button
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var previewStatus: TextView
    private lateinit var daily: Switch
    private val previewRows = linkedMapOf<String, TextView>()
    private var previewJob: Job? = null
    private var previewBusy = false
    private var previewComplete = false
    private var activeWork: WorkInfo? = null
    private var pendingAction = 0
    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        status.text = "Permissions updated. Reading today's data…"
        refreshToday()
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        lifecycleScope.launch {
            try {
                val auth = Identity.getAuthorizationClient(this@MainActivity).getAuthorizationResultFromIntent(result.data)
                val token = auth.accessToken ?: error("No access token returned")
                withContext(Dispatchers.IO) { GoogleHealthFeed.verify(token) }
                prefs.edit().putBoolean("cloud", true).apply()
                cloudStatus.text = "Google Health/Fitbit account connected."
            } catch (e: Exception) { showCloudError(e) }
        }
    }
    private val destinationLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                ExportWorker.selectDestination(this@MainActivity, uri)
                status.text = "Export file selected. The ZIP is written here after the scan finishes."
                if (pendingAction != 0) ExportWorker.once(this@MainActivity, saveOnly = pendingAction == 2)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.text = "This destination could not be saved (${e.javaClass.simpleName}). Choose a file location that allows ongoing write access."
            } finally { pendingAction = 0 }
        }
        else pendingAction = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAction = savedInstanceState?.getInt("pendingAction", 0) ?: 0
        if (prefs.getInt("exporterVersion", 0) < 3) {
            ExportWorker.cancel(this)
            prefs.edit().putInt("exporterVersion", 3)
                .putString("lastStatus", "Ready. Older export jobs were stopped during this update. Check today's readings, then start a new export.").apply()
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 32) }
        fun text(message: String, size: Float = 16f, target: LinearLayout = content): TextView = TextView(this).apply {
            text = message; textSize = size; setPadding(0, 14, 0, 14); target.addView(this)
        }
        fun button(title: String, target: LinearLayout = content, action: () -> Unit): Button = Button(this).apply {
            text = title; isAllCaps = false; setOnClickListener { action() }; target.addView(this)
        }
        text("Pixel Data Vault", 26f)
        text("Version 0.3 · Check the phone's data first, then export all accessible history.")
        text("Health Connect", 21f)
        text("Reads records already stored on this phone. Missing or stale data in Health Connect will remain visible in the coverage report.")
        button("Grant Health Connect access") { grantPermissions() }
        button("Health Connect settings (including route consent)") {
            try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            catch (_: Exception) { status.text = "Open Health Connect in Android settings to manage this app's access." }
        }
        CheckBox(this).apply {
            text = "Include medical records stored in Health Connect, if supported"
            isChecked = prefs.getBoolean("medical", false)
            setOnCheckedChangeListener { _, selected -> prefs.edit().putBoolean("medical", selected).apply(); if (selected) grantPermissions() }
            content.addView(this)
        }
        text("Today on this phone", 21f)
        previewStatus = text("Reading Health Connect…")
        refresh = button("Refresh today's readings") { refreshToday() }
        val moreMetrics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        for ((index, metric) in TodayPreview.metrics.withIndex()) {
            val target = if (index < 3) content else moreMetrics
            text(metric.first, 18f, target)
            previewRows[metric.first] = text("Waiting…", target = target)
        }
        button("Show / hide other readings") { moreMetrics.visibility = if (moreMetrics.visibility == View.GONE) View.VISIBLE else View.GONE }
        content.addView(moreMetrics)
        text("This preview reads the same Health Connect source used by the ZIP. It shows today's records and their sources. The export also includes older history and other available types.")
        text("Export", 21f)
        chooseFile = button("Choose export file (Google Drive or local)") { destinationLauncher.launch("Health-Data-Vault.zip") }
        scan = button("Export all accessible history") {
            requestExport(saveOnly = false)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; content.addView(this) }
        elapsed = text("")
        cancel = button("Cancel") {
            previewJob?.cancel()
            ExportWorker.cancel(this)
            daily.isChecked = false
            status.text = "Cancelling… The previous completed ZIP will be kept."
        }.apply { visibility = View.GONE }
        saveLast = button("Save last completed ZIP") { requestExport(saveOnly = true) }
        daily = Switch(this).apply {
            text = "Automatically update the chosen file daily"
            isChecked = prefs.getBoolean("daily", false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && prefs.getString("destination", null) == null) {
                    isChecked = false
                    status.text = "Choose an export file first."
                } else {
                    prefs.edit().putBoolean("daily", enabled).apply()
                    ExportWorker.schedule(this@MainActivity, enabled)
                    if (enabled) status.text = "Daily export enabled. Allow Health Connect background reads. Android may delay runs to save battery; check the measurement dates in each export."
                }
            }
            content.addView(this)
        }
        text("ZIP files include original cloud JSON and Health Connect records in separate folders, plus manifest.json with per-type coverage. Choose your private Drive folder in the system picker. Records are never added across sources.")
        status = text(prefs.getString("lastStatus", "Ready. Grant access, choose an export file, then export.")!!)
        val cloudOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button("Google Health / Fitbit connection (optional)") {
            cloudOptions.visibility = if (cloudOptions.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        content.addView(cloudOptions)
        cloudStatus = text(if (prefs.getBoolean("cloud", false)) "Account connected." else "Account not connected.", target = cloudOptions)
        text("Health Connect works without this connection. Cloud access requires an approved Google Health API project and an Android OAuth client for this app. Google is currently not onboarding new projects.", target = cloudOptions)
        button("Connect Google Health / Fitbit", cloudOptions) { connectCloud() }
        button("Disconnect cloud reads", cloudOptions) {
            prefs.edit().putBoolean("cloud", false).apply()
            cloudStatus.text = "Cloud reads disabled."
        }
        button("Google Health API setup information", cloudOptions) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/health/setup")))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("vault-export").observe(this) { work ->
            activeWork = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: work.firstOrNull { it.state == WorkInfo.State.ENQUEUED && "vault-daily" !in it.tags }
            if (activeWork == null) status.text = prefs.getString("lastStatus", "Ready.")
            updateControls()
            renderProgress()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { renderProgress(); delay(1000) }
            }
        }
        refreshToday()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pendingAction", pendingAction)
        super.onSaveInstanceState(outState)
    }

    private fun requestExport(saveOnly: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.getString("destination", null) == null) {
            pendingAction = if (saveOnly) 2 else 1
            destinationLauncher.launch("Health-Data-Vault.zip")
        } else ExportWorker.once(this, saveOnly)
    }

    private fun updateControls() {
        val exporting = activeWork != null
        scan.isEnabled = !exporting && !previewBusy && previewComplete
        refresh.isEnabled = !exporting && !previewBusy
        chooseFile.isEnabled = !exporting
        saveLast.isEnabled = !exporting && !previewBusy && ExportWorker.hasCompletedExport(this)
        daily.isEnabled = !exporting && !previewBusy
        cancel.visibility = if (exporting || previewBusy) View.VISIBLE else View.GONE
    }

    private fun renderProgress() {
        val work = activeWork
        progress.visibility = if (work != null) View.VISIBLE else View.GONE
        if (work == null) { elapsed.text = ""; return }
        val total = work.progress.getInt("total", 0)
        progress.isIndeterminate = total == 0
        if (total > 0) { progress.max = total; progress.progress = work.progress.getInt("done", 0) }
        status.text = work.progress.getString("message") ?: "Export queued. Waiting for Android to start the job…"
        val start = work.progress.getLong("startedAt", 0)
        val last = work.progress.getLong("updatedAt", start)
        if (start > 0) {
            val seconds = (System.currentTimeMillis() - start).coerceAtLeast(0) / 1000
            val quiet = (System.currentTimeMillis() - last).coerceAtLeast(0) / 1000
            elapsed.text = "Elapsed ${seconds / 60}m ${seconds % 60}s · Last update ${quiet}s ago"
        }
    }

    private fun refreshToday() {
        if (previewJob?.isActive == true || activeWork != null) return
        previewBusy = true
        previewComplete = false
        updateControls()
        previewJob = lifecycleScope.launch {
            try {
                if (!HealthConnectFeed.providerAvailable(this@MainActivity)) {
                    previewStatus.text = "Health Connect is unavailable or needs an update."
                    return@launch
                }
                previewStatus.text = "Reading today's data. Readings appear as each type finishes…"
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val readable = withContext(Dispatchers.IO) {
                    TodayPreview.load(client) { label, value ->
                        withContext(Dispatchers.Main) { previewRows[label]?.text = value }
                    }
                }
                previewComplete = readable
                previewStatus.text = if (readable) "Checked at ${java.time.LocalTime.now().withNano(0)}. Missing data and older latest readings are shown below."
                    else "Grant Health Connect read access to load today's data."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    previewStatus.text = "Preview cancelled. Tap Refresh today's readings to try again."
                    throw e
                }
                previewStatus.text = "Preview failed: ${sourceError(e)}"
            } finally { previewBusy = false; updateControls() }
        }
    }

    private fun grantPermissions() {
        if (!HealthConnectFeed.providerAvailable(this)) {
            status.text = "Health Connect is unavailable or needs a provider update."
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        permissionLauncher.launch(HealthConnectFeed.permissions(client, prefs.getBoolean("medical", false)))
    }

    private fun connectCloud() {
        cloudStatus.text = "Requesting read access…"
        lifecycleScope.launch {
            try {
                val result = Identity.getAuthorizationClient(this@MainActivity).authorize(GoogleHealthFeed.authRequest()).await()
                if (result.hasResolution()) {
                    authLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else {
                    val token = result.accessToken ?: error("No access token returned")
                    withContext(Dispatchers.IO) { GoogleHealthFeed.verify(token) }
                    prefs.edit().putBoolean("cloud", true).apply()
                    cloudStatus.text = "Google Health/Fitbit account connected."
                }
            } catch (e: Exception) { showCloudError(e) }
        }
    }

    private fun showCloudError(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) return
        val reason = if (e is CloudApiException) e.safeReason else e.javaClass.simpleName
        cloudStatus.text = "Cloud access could not be enabled ($reason). Google must approve the API project, and the app's package/signing certificate must match its Android OAuth client. Health Connect export remains available."
    }
}
