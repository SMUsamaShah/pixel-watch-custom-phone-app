package com.usamashah.pixelvault

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.reflect.KClass

fun sourceLabel(packageName: String): String = when {
    packageName == "com.fitbit.FitbitMobile" -> "Google Health / Fitbit"
    packageName == "com.google.android.apps.fitness" -> "Google Fit"
    packageName == "com.xiaomi.hm.health" -> "Zepp"
    packageName == "android" || packageName.startsWith("com.android.healthconnect.phone.") -> "Your phone"
    packageName.isBlank() -> "Source not reported"
    else -> packageName
}

data class PreviewReading(val time: Instant, val value: String, val origin: String)

/** Counts the day's actual samples rather than the enclosing series interval. */
class PreviewRecords(private val start: Instant, private val end: Instant, private val zone: ZoneId) {
    var records = 0L
        private set
    var samples = 0L
        private set
    var latest: PreviewReading? = null
        private set
    val origins = sortedMapOf<String, Long>()

    fun observe(record: Record, restrictToDay: Boolean = true) {
        val reading = reading(record, restrictToDay) ?: return
        records++
        val source = record.metadata.dataOrigin.packageName
        origins[source] = (origins[source] ?: 0) + 1
        if (latest == null || reading.time > latest!!.time) latest = reading
    }

    private fun reading(record: Record, restrictToDay: Boolean): PreviewReading? {
        val origin = record.metadata.dataOrigin.packageName
        fun result(time: Instant, value: String) = PreviewReading(time, value, origin)
        fun number(value: Double, unit: String) = String.format(Locale.UK, "%.1f %s", value, unit)
        fun interval(startTime: Instant, endTime: Instant): Boolean =
            !restrictToDay || (startTime < end && endTime > start)
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        return when (record) {
            is HeartRateRecord -> {
                var newest: HeartRateRecord.Sample? = null
                for (sample in record.samples) {
                    if (restrictToDay && (sample.time < start || sample.time >= end)) continue
                    samples++
                    if (newest == null || sample.time > newest.time) newest = sample
                }
                newest?.let { result(it.time, "${it.beatsPerMinute} bpm") }
            }
            is StepsRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, "${record.count} steps in the latest recorded interval") else null
            is SleepSessionRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, "Session ${clock.format(record.startTime)}–${clock.format(record.endTime)} · ${record.stages.size} stages") else null
            is RestingHeartRateRecord -> result(record.time, "${record.beatsPerMinute} bpm")
            is HeartRateVariabilityRmssdRecord -> result(record.time, number(record.heartRateVariabilityMillis, "ms RMSSD"))
            is OxygenSaturationRecord -> result(record.time, number(record.percentage.value, "%"))
            is RespiratoryRateRecord -> result(record.time, number(record.rate, "breaths/min"))
            is DistanceRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, number(record.distance.inMeters, "m in the latest interval")) else null
            is ActiveCaloriesBurnedRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, number(record.energy.inKilocalories, "kcal in the latest interval")) else null
            is TotalCaloriesBurnedRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, number(record.energy.inKilocalories, "kcal in the latest interval")) else null
            is ExerciseSessionRecord -> if (interval(record.startTime, record.endTime))
                result(record.endTime, "${record.title ?: "Exercise session"} · ${clock.format(record.startTime)}–${clock.format(record.endTime)}") else null
            is WeightRecord -> result(record.time, number(record.weight.inKilograms, "kg"))
            else -> null
        }?.takeIf {
            val intervalType = record is StepsRecord || record is SleepSessionRecord || record is DistanceRecord ||
                record is ActiveCaloriesBurnedRecord || record is TotalCaloriesBurnedRecord || record is ExerciseSessionRecord
            return@takeIf !restrictToDay || intervalType || (it.time >= start && it.time < end)
        }
    }

    fun description(): String {
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        val reading = latest ?: return "No measurements today."
        val count = if (samples > 0) "$samples samples in $records records" else "$records records"
        val sources = origins.entries.joinToString("; ") { "${sourceLabel(it.key)}: ${it.value}" }
        return "${reading.value}\nAt ${timeFormat.format(reading.time)} · ${sourceLabel(reading.origin)}\n$count · $sources"
    }
}

object TodayPreview {
    val metrics: List<Pair<String, KClass<out Record>>> = listOf(
        "Heart rate" to HeartRateRecord::class,
        "Steps" to StepsRecord::class,
        "Sleep (including last night)" to SleepSessionRecord::class,
        "Resting heart rate" to RestingHeartRateRecord::class,
        "Heart rate variability" to HeartRateVariabilityRmssdRecord::class,
        "Oxygen saturation" to OxygenSaturationRecord::class,
        "Breathing rate" to RespiratoryRateRecord::class,
        "Distance" to DistanceRecord::class,
        "Active calories" to ActiveCaloriesBurnedRecord::class,
        "Total calories" to TotalCaloriesBurnedRecord::class,
        "Exercise" to ExerciseSessionRecord::class,
        "Weight" to WeightRecord::class
    )

    suspend fun load(client: HealthConnectClient, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
                     onRow: suspend (String, String) -> Unit): Boolean {
        val start = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val granted = timedSourceRead(15_000) { client.permissionController.getGrantedPermissions() }
        var readable = false
        for ((label, type) in metrics) {
            if (HealthPermission.getReadPermission(type) !in granted) {
                onRow(label, "Read permission not granted.")
                continue
            }
            readable = true
            onRow(label, "Reading today's Health Connect records…")
            try {
                val day = PreviewRecords(start, now, zone)
                val queryStart = if (type == SleepSessionRecord::class) start.minusSeconds(86400) else start
                readEveryPage({ token ->
                    val response = timedSourceRead(15_000) {
                        client.readRecords(ReadRecordsRequest(type,
                            TimeRangeFilter.between(queryStart, now), pageSize = 500, pageToken = token))
                    }
                    Page(response.records, response.pageToken)
                }) { day.observe(it) }
                var description = day.description()
                if (day.latest == null) {
                    val response = timedSourceRead(15_000) {
                        client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.before(now),
                            ascendingOrder = false, pageSize = 1))
                    }
                    val previous = PreviewRecords(start, now, zone)
                    response.records.forEach { previous.observe(it, restrictToDay = false) }
                    description = previous.latest?.let {
                        val date = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z").withZone(zone).format(it.time)
                        "No measurements today.\nLatest accessible: ${it.value}\n$date · ${sourceLabel(it.origin)}"
                    } ?: "No measurements today or in accessible history."
                }
                if (type == StepsRecord::class) {
                    try {
                        val aggregate = timedSourceRead(15_000) {
                            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now)))
                        }
                        aggregate[StepsRecord.COUNT_TOTAL]?.let { description = "Today: $it steps (Health Connect total)\n$description" }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        description += "\nToday's combined total unavailable: ${sourceError(e)}"
                    }
                }
                onRow(label, description)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onRow(label, "Could not read: ${sourceError(e)}")
            }
        }
        return readable
    }
}
