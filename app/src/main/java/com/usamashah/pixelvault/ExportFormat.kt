package com.usamashah.pixelvault

import com.google.gson.*
import kotlinx.coroutines.CancellationException
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A page size limits memory, never the amount of history exported. */
data class Page<T>(val records: List<T>, val nextToken: String?)

suspend fun <T> readEveryPage(fetch: suspend (String?) -> Page<T>, consume: suspend (T) -> Unit) {
    var token: String? = null
    val seen = HashSet<String>()
    do {
        val page = fetch(token)
        for (record in page.records) consume(record)
        token = page.nextToken?.takeIf { it.isNotEmpty() }
        check(token == null || seen.add(token)) { "Repeated pagination token; export incomplete" }
    } while (token != null)
}

/** Serialize the SDK's PUBLIC data properties, including nested samples and metadata. */
object PublicRecordJson {
    private val methods = ConcurrentHashMap<Class<*>, List<Method>>()
    private val quantities = mapOf(
        "Length" to ("getInMeters" to "m"), "Mass" to ("getInGrams" to "g"),
        "Energy" to ("getInKilocalories" to "kcal"), "Power" to ("getInWatts" to "W"),
        "Pressure" to ("getInMillimetersOfMercury" to "mmHg"),
        "Velocity" to ("getInMetersPerSecond" to "m/s"), "Volume" to ("getInLiters" to "L"),
        "Temperature" to ("getInCelsius" to "degC"),
        "TemperatureDelta" to ("getInCelsius" to "degC difference"),
        "Percentage" to ("getValue" to "%"),
        "BloodGlucose" to ("getInMillimolesPerLiter" to "mmol/L")
    )

    fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Instant, is ZoneOffset, is LocalDate, is LocalDateTime, is Duration -> JsonPrimitive(value.toString())
        is Enum<*> -> JsonPrimitive(value.name)
        is Iterable<*> -> JsonArray().also { a -> value.forEach { a.add(encode(it)) } }
        is Map<*, *> -> JsonObject().also { o -> value.forEach { (k, v) -> o.add(k.toString(), encode(v)) } }
        else -> {
            val cls = value.javaClass
            require(cls.name.startsWith("androidx.health.connect.client.")) {
                "Unsupported public data class ${cls.name}"
            }
            val q = quantities[cls.simpleName]
            if (q != null && cls.name.contains(".units.")) {
                JsonObject().apply {
                    add("value", encode(cls.getMethod(q.first).invoke(value)))
                    addProperty("unit", q.second)
                }
            } else {
                val getters = methods.getOrPut(cls) {
                    cls.methods.filter {
                        !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.name != "getClass" && !it.name.contains('$') &&
                        (it.name.matches(Regex("get[A-Z].*")) || it.name.matches(Regex("is[A-Z].*")))
                    }.sortedBy { it.name }
                }
                JsonObject().apply {
                    addProperty("objectType", cls.simpleName)
                    for (getter in getters) {
                        val name = if (getter.name.startsWith("get")) getter.name.substring(3).replaceFirstChar { it.lowercase() }
                                   else getter.name
                        add(name, encode(getter.invoke(value)))
                    }
                }
            }
        }
    }
}

data class Coverage(
    val feed: String,
    val type: String,
    var status: String = "pending",
    var records: Long = 0,
    var samples: Long = 0,
    var firstMeasurementAt: String? = null,
    var latestMeasurementAt: String? = null,
    var firstMeasurementDate: String? = null,
    var latestMeasurementDate: String? = null,
    var error: String? = null,
    val origins: MutableMap<String, Long> = sortedMapOf()
) {
    fun observe(json: JsonObject, origin: String) {
        records++
        origins[origin] = (origins[origin] ?: 0) + 1
        if (type == "PlannedExerciseSessionRecord") return // Scheduled activity is not a measurement.
        // Inspect measurement fields only. Export, create and modified times do not prove freshness.
        val values = if (feed == "health-connect") json else json.entrySet()
            .firstOrNull { it.key !in setOf("name", "dataSource") && it.value.isJsonObject }?.value?.asJsonObject
        if (values == null) return
        val sampleArray = values.getAsJsonArray("samples") ?: values.getAsJsonArray("deltas")
        samples += sampleArray?.size()?.toLong() ?: 0
        val instants = mutableListOf<String>()
        fun addTime(o: JsonObject, key: String) {
            val v = o.get(key)
            if (v?.isJsonPrimitive == true) {
                try { instants += Instant.parse(v.asString).toString() } catch (_: DateTimeException) { }
            }
        }
        // Series parent intervals can extend past the actual last sample.
        if (sampleArray != null) {
            for (s in sampleArray) if (s.isJsonObject) addTime(s.asJsonObject, "time")
        } else {
            addTime(values, "time")
            addTime(values, "startTime")
            addTime(values, "endTime")
        }
        values.getAsJsonObject("interval")?.let { addTime(it, "startTime"); addTime(it, "endTime") }
        values.getAsJsonObject("sampleTime")?.let { addTime(it, "physicalTime") }
        for (t in instants) {
            if (firstMeasurementAt == null || Instant.parse(t) < Instant.parse(firstMeasurementAt)) firstMeasurementAt = t
            if (latestMeasurementAt == null || Instant.parse(t) > Instant.parse(latestMeasurementAt)) latestMeasurementAt = t
        }
        val date = values.get("date")
        val localDate = when {
            date?.isJsonPrimitive == true -> runCatching { LocalDate.parse(date.asString) }.getOrNull()
            date?.isJsonObject == true -> runCatching {
                LocalDate.of(date.asJsonObject["year"].asInt, date.asJsonObject["month"].asInt, date.asJsonObject["day"].asInt)
            }.getOrNull()
            else -> null
        }
        localDate?.toString()?.let { d ->
            if (firstMeasurementDate == null || d < firstMeasurementDate!!) firstMeasurementDate = d
            if (latestMeasurementDate == null || d > latestMeasurementDate!!) latestMeasurementDate = d
        }
    }
}

/** The ZIP is finished in private storage before it is offered to a destination. */
class VaultArchive(val file: File, val startedAt: Instant = Instant.now()) : AutoCloseable {
    private val zip = ZipOutputStream(file.outputStream().buffered())
    val coverage = mutableListOf<Coverage>()
    val feedStatus = sortedMapOf<String, String>()
    val notes = mutableListOf<String>()
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    suspend fun stream(feed: String, type: String, body: suspend (suspend (JsonObject, String) -> Unit) -> Unit) {
        val c = Coverage(feed, type)
        coverage += c
        zip.putNextEntry(ZipEntry("$feed/$type.ndjson"))
        try {
            body { raw, origin ->
                zip.write((gson.toJson(raw) + "\n").toByteArray(Charsets.UTF_8))
                c.observe(raw, origin)
            }
            c.status = "read_complete"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            c.status = "incomplete"
            c.error = if (e is CloudApiException) e.safeReason else e.javaClass.simpleName
        } finally {
            zip.closeEntry()
        }
    }

    fun unavailable(feed: String, type: String, why: String) {
        coverage += Coverage(feed, type, status = why)
    }

    fun finish(extra: Map<String, Any?> = emptyMap()): JsonObject {
        val manifest = gson.toJsonTree(mapOf(
            "schemaVersion" to 2,
            "appVersion" to "0.2",
            "generatedAt" to Instant.now().toString(),
            "startedAt" to startedAt.toString(),
            "displayTimezone" to "Europe/London",
            "historyMode" to "all accessible history; no app record or page cap",
            "feeds" to feedStatus,
            "coverage" to coverage,
            "notes" to notes,
            "details" to extra,
            "interpretation" to listOf(
                "Keep feeds and origins separate. Overlapping records are not independent quantities.",
                "Raw step, distance and energy sums are not deduplicated aggregates.",
                "Sample-average heart rate is not resting heart rate. Preserve actual RHR and RMSSD fields.",
                "Sleep intervals and sleep stages are distinct; preserve both without adding duplicate sessions.",
                "read_complete means all pages returned by the API were read, not proof that a wearable exported everything.",
                "Medical resources, when enabled, include only records actually stored in Health Connect."
            )
        )).asJsonObject
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return manifest
    }

    override fun close() = zip.close()
}
