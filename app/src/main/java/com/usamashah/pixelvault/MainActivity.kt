package com.usamashah.pixelvault

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * First milestone for Pixel Data Vault.
 *
 * This deliberately reads the official Health Connect store instead of trying to
 * impersonate Google's package or access its private Wearable Data Layer paths.
 * The audit preserves every HeartRateRecord and every timestamped sample returned
 * by Health Connect, then reports the observed cadence without aggregating it.
 */
class MainActivity : ComponentActivity() {
    private val requestedPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        "android.permission.health.READ_HEALTH_DATA_HISTORY"
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(requestedPermissions)) {
            setStatus("Health Connect access granted. Tap Scan to inspect raw samples.")
        } else {
            setStatus("Access was not fully granted. The scan will remain disabled.")
        }
        lifecycleScope.launch {
            refreshPermissionState(showReadyStatus = false)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = latestExport
        if (uri == null || json == null) return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(json)
        }
        Toast.makeText(this, "Raw audit exported", Toast.LENGTH_SHORT).show()
    }

    private lateinit var statusView: TextView
    private lateinit var scanButton: Button
    private lateinit var exportButton: Button
    private var latestExport: String? = null
    private var healthConnectClient: HealthConnectClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intro = TextView(this).apply {
            text = "Pixel Data Vault\n\n" +
                "This proof of concept reads raw heart-rate records already available " +
                "to your phone, keeps sample timestamps, and measures the actual cadence. " +
                "It does not contact Fitbit or Google servers."
            textSize = 17f
            setPadding(32, 32, 32, 24)
        }
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(32, 16, 32, 16)
        }
        val grantButton = Button(this).apply {
            text = "Grant Health Connect access"
            setOnClickListener { requestHealthConnectPermissions() }
        }
        scanButton = Button(this).apply {
            text = "Scan last 7 days"
            isEnabled = false
            setOnClickListener { scanLastSevenDays() }
        }
        exportButton = Button(this).apply {
            text = "Export raw JSON"
            isEnabled = false
            setOnClickListener { exportLatest() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(intro)
            addView(grantButton)
            addView(scanButton)
            addView(exportButton)
            addView(statusView)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        initializeHealthConnect()
    }

    private fun initializeHealthConnect() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                healthConnectClient = HealthConnectClient.getOrCreate(this)
                lifecycleScope.launch {
                    refreshPermissionState()
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("Health Connect needs an update before this app can read data.")
            }
            else -> {
                setStatus("Health Connect is not available on this phone.")
            }
        }
    }

    private fun requestHealthConnectPermissions() {
        val client = healthConnectClient
        if (client == null) {
            setStatus("Health Connect is not ready on this phone.")
            return
        }
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            val missing = requestedPermissions - granted
            if (missing.isEmpty()) {
                refreshPermissionState()
            } else {
                permissionLauncher.launch(requestedPermissions)
            }
        }
    }

    private suspend fun refreshPermissionState(showReadyStatus: Boolean = true) {
        val client = healthConnectClient ?: return
        val granted = client.permissionController.getGrantedPermissions()
        val ready = granted.containsAll(requestedPermissions)
        runOnUiThread {
            scanButton.isEnabled = ready
            if (ready && showReadyStatus) {
                setStatus("Ready. Scan after the watch has synced to the phone.")
            } else {
                if (!ready) setStatus("Grant the requested Health Connect permissions to begin.")
            }
        }
    }

    private fun scanLastSevenDays() {
        val client = healthConnectClient ?: return
        scanButton.isEnabled = false
        setStatus("Reading raw heart-rate records…")
        lifecycleScope.launch {
            try {
                val end = Instant.now()
                val start = end.minus(7, ChronoUnit.DAYS)
                val records = readAllHeartRateRecords(client, start, end)
                val audit = Audit.from(start, end, records)
                latestExport = audit.toJson().toString(2)
                exportButton.isEnabled = true
                setStatus(audit.summary())
            } catch (error: Exception) {
                setStatus("Scan failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                refreshPermissionState(showReadyStatus = false)
            }
        }
    }

    private suspend fun readAllHeartRateRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): List<HeartRateRecord> {
        val records = mutableListOf<HeartRateRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken
                )
            )
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records.sortedBy { it.startTime }
    }

    private fun exportLatest() {
        if (latestExport == null) {
            Toast.makeText(this, "Run a scan first", Toast.LENGTH_SHORT).show()
            return
        }
        exportLauncher.launch("pixel-heart-rate-audit-${Instant.now().epochSecond}.json")
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private data class Sample(
        val time: Instant,
        val bpm: Long,
        val origin: String
    )

    private data class Audit(
        val start: Instant,
        val end: Instant,
        val records: List<HeartRateRecord>
    ) {
        private val samples: List<Sample> = records.flatMap { record ->
            val origin = record.metadata.dataOrigin.packageName
            record.samples.map { sample -> Sample(sample.time, sample.beatsPerMinute, origin) }
        }.sortedBy { it.time }

        fun summary(): String {
            val byOrigin = samples.groupBy { it.origin }
            val lines = mutableListOf(
                "Window: ${start.truncatedTo(ChronoUnit.SECONDS)} → ${end.truncatedTo(ChronoUnit.SECONDS)}",
                "Records: ${records.size}",
                "Raw samples: ${samples.size}"
            )
            if (samples.isEmpty()) {
                lines += "No timestamped samples were returned. Sync the watch, then scan again."
                return lines.joinToString("\n")
            }
            byOrigin.toSortedMap().forEach { (origin, originSamples) ->
                val deltas = originSamples.sortedBy { it.time }
                    .zipWithNext()
                    .map { (a, b) -> Duration.between(a.time, b.time).toMillis() / 1000.0 }
                    .filter { it > 0.0 }
                val cadence = if (deltas.isEmpty()) {
                    "single sample"
                } else {
                    "Δ min/median/max ${format(deltas.min())}/${format(median(deltas))}/${format(deltas.max())} s"
                }
                lines += "$origin: ${originSamples.size} samples; $cadence"
            }
            lines += "The JSON export contains each record and each sample timestamp; no aggregation was applied."
            return lines.joinToString("\n")
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAt", Instant.now().toString())
            put("query", JSONObject().apply {
                put("startTime", start.toString())
                put("endTime", end.toString())
                put("recordType", "HeartRateRecord")
            })
            put("summary", JSONObject().apply {
                put("recordCount", records.size)
                put("sampleCount", samples.size)
                put("origins", JSONArray(samples.map { it.origin }.distinct().sorted()))
            })
            put("records", JSONArray(records.map { record ->
                JSONObject().apply {
                    put("startTime", record.startTime.toString())
                    put("endTime", record.endTime.toString())
                    put("origin", record.metadata.dataOrigin.packageName)
                    put("samples", JSONArray(record.samples.map { sample ->
                        JSONObject().apply {
                            put("time", sample.time.toString())
                            put("beatsPerMinute", sample.beatsPerMinute)
                        }
                    }))
                }
            }))
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }

        private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

        companion object {
            fun from(start: Instant, end: Instant, records: List<HeartRateRecord>): Audit =
                Audit(start, end, records)
        }
    }
}
