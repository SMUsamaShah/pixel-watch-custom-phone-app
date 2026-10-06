package com.usamashah.pixelvault

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipFile

class ExportFormatTest {
    @Test fun emptyPageWithContinuationAndThousandsOfRecordsAreAllRead() = runBlocking {
        var calls = 0
        var count = 0
        readEveryPage({ token ->
            calls++
            when (token) {
                null -> Page((0 until 2000).toList(), "empty")
                "empty" -> Page(emptyList(), "last")
                else -> Page((2000 until 4205).toList(), "")
            }
        }) { count++ }
        assertEquals(3, calls)
        assertEquals(4205, count)
    }

    @Test fun repeatedTokenFailsInsteadOfHangingOrClaimingCompletion() = runBlocking {
        try {
            readEveryPage({ _: String? -> Page(listOf(1), "loop") }) { }
            fail("Repeated token must be rejected")
        } catch (_: IllegalStateException) { }
    }

    @Test fun heartSampleTimestampWinsOverParentIntervalAndModificationTime() {
        val row = JsonParser.parseString("""{
            "startTime":"2026-09-22T09:00:00Z","endTime":"2026-10-06T00:00:00Z",
            "metadata":{"lastModifiedTime":"2026-10-06T08:00:00Z"},
            "samples":[{"time":"2026-09-22T09:26:59Z","beatsPerMinute":72}]
        }""").asJsonObject
        val c = Coverage("health-connect", "HeartRateRecord")
        c.observe(row, "fitbit")
        assertEquals("2026-09-22T09:26:59Z", c.latestMeasurementAt)
        assertEquals(1L, c.samples)
    }

    @Test fun realSdkHeartSamplesAndUnitsArePreserved() {
        val t = Instant.parse("2026-10-05T08:00:00Z")
        val raw = HeartRateRecord(t, null, t.plusSeconds(10), null,
            listOf(HeartRateRecord.Sample(t, 65), HeartRateRecord.Sample(t.plusSeconds(1), 66)), Metadata.manualEntry())
        val json = PublicRecordJson.encode(raw).asJsonObject
        assertEquals(2, json.getAsJsonArray("samples").size())
        assertEquals(66, json.getAsJsonArray("samples")[1].asJsonObject["beatsPerMinute"].asInt)
        assertTrue(json.has("metadata"))
        assertTrue(json.getAsJsonObject("metadata").has("id"))
        val length = PublicRecordJson.encode(Length.kilometers(2.5)).asJsonObject
        assertEquals("m", length["unit"].asString)
        assertEquals(2500.0, length["value"].asDouble, 0.0001)
    }

    @Test fun rawSleepStagesStayDistinctFromTheSessionInterval() {
        val t = Instant.parse("2026-10-05T22:00:00Z")
        val raw = SleepSessionRecord(t, null, t.plusSeconds(8 * 3600), null, Metadata.manualEntry(),
            stages = listOf(SleepSessionRecord.Stage(t, t.plusSeconds(3600), SleepSessionRecord.STAGE_TYPE_AWAKE),
                SleepSessionRecord.Stage(t.plusSeconds(3600), t.plusSeconds(8 * 3600), SleepSessionRecord.STAGE_TYPE_LIGHT)))
        val json = PublicRecordJson.encode(raw).asJsonObject
        assertEquals(2, json.getAsJsonArray("stages").size())
        assertEquals(t.plusSeconds(8 * 3600).toString(), json["endTime"].asString)
        assertFalse(json.has("minutesAsleep"))
    }

    @Test fun dailyMetricsKeepDatePrecisionRatherThanInventingAMeasurementTime() {
        val raw = JsonParser.parseString("""{"dailyRestingHeartRate":{"date":{"year":2026,"month":10,"day":5},"beatsPerMinute":61}}""").asJsonObject
        val c = Coverage("google-health", "daily-resting-heart-rate")
        c.observe(raw, "FITBIT")
        assertEquals("2026-10-05", c.latestMeasurementDate)
        assertNull(c.latestMeasurementAt)
    }

    @Test fun bothFeedsAndOverlappingOriginsSurviveAndLateErrorsAreVisible() = runBlocking {
        val file = File.createTempFile("vault-test", ".zip")
        try {
            VaultArchive(file).use { vault ->
                vault.stream("health-connect", "StepsRecord") { emit ->
                    for (source in listOf("phone", "fitbit")) emit(JsonParser.parseString("""{"count":500,"startTime":"2026-10-05T12:00:00Z","endTime":"2026-10-05T12:10:00Z"}""").asJsonObject, source)
                    throw IllegalStateException("A later page failed")
                }
                vault.stream("google-health", "steps") { emit ->
                    emit(JsonParser.parseString("""{"steps":{"count":"500","interval":{"startTime":"2026-10-05T12:00:00Z","endTime":"2026-10-05T12:10:00Z"}}}""").asJsonObject, "FITBIT")
                }
                vault.finish()
            }
            ZipFile(file).use { zip ->
                val hc = zip.getInputStream(zip.getEntry("health-connect/StepsRecord.ndjson")).bufferedReader().readLines()
                val cloud = zip.getInputStream(zip.getEntry("google-health/steps.ndjson")).bufferedReader().readLines()
                assertEquals(2, hc.size)
                assertEquals(1, cloud.size)
                val m = JsonParser.parseReader(zip.getInputStream(zip.getEntry("manifest.json")).reader()).asJsonObject
                val coverage = m.getAsJsonArray("coverage")
                assertEquals("incomplete", coverage[0].asJsonObject["status"].asString)
                assertEquals(2, coverage[0].asJsonObject["origins"].asJsonObject.size())
                assertEquals("read_complete", coverage[1].asJsonObject["status"].asString)
            }
        } finally { file.delete() }
    }

    @Test fun londonDatesHandleBothDaylightSavingBoundaries() {
        val zone = ZoneId.of("Europe/London")
        assertEquals("2026-03-29T02:30+01:00[Europe/London]", Instant.parse("2026-03-29T01:30:00Z").atZone(zone).toString())
        assertEquals("2026-10-25T01:30Z[Europe/London]", Instant.parse("2026-10-25T01:30:00Z").atZone(zone).toString())
    }
}
