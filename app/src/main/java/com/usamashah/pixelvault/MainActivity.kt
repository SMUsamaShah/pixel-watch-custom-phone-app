package com.usamashah.pixelvault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("vault", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var cloudStatus: TextView
    private lateinit var scan: Button
    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        status.text = "Permissions updated. The export records which types are accessible."
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
                val saved = ExportWorker.selectDestination(this@MainActivity, uri)
                status.text = "Export file selected. ${if (saved) "The latest ZIP has been saved." else "Tap Export all accessible history."}"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.text = "This destination could not be saved (${e.javaClass.simpleName}). Choose a file location that allows ongoing write access."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 32) }
        fun text(message: String, size: Float = 16f): TextView = TextView(this).apply {
            text = message; textSize = size; setPadding(0, 14, 0, 14); content.addView(this)
        }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title; setOnClickListener { action() }; content.addView(this)
        }
        text("Pixel Data Vault", 26f)
        text("Export your health records from two independent sources. Keep samples, stages, timestamps and source labels. All accessible history; no record cap.")
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
        text("Google Health / Fitbit", 21f)
        cloudStatus = text(if (prefs.getBoolean("cloud", false)) "Account connected." else "Account not connected.")
        text("Cloud access requires an approved Google Health API project and an Android OAuth client for this app. Google is currently not onboarding new projects. The Health Connect exporter works independently.")
        button("Connect Google Health / Fitbit") { connectCloud() }
        button("Disconnect cloud reads") {
            prefs.edit().putBoolean("cloud", false).apply()
            cloudStatus.text = "Cloud reads disabled. You can also revoke this app in your Google account's third-party access settings."
        }
        text("Export", 21f)
        button("Choose export file (Google Drive or local)") { destinationLauncher.launch("Health-Data-Vault.zip") }
        scan = button("Export all accessible history") {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            ExportWorker.once(this)
        }
        Switch(this).apply {
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
        button("Google Health API setup information") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/health/setup")))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("vault-export").observe(this) { work ->
            val running = work.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            // An enqueued periodic run does not mean an export is in progress.
            scan.isEnabled = running == null
            status.text = running?.progress?.getString("message") ?: prefs.getString("lastStatus", "Ready.")
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
