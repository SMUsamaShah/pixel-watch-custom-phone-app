package com.usamashah.pixelvault

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.api.Scope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class CloudApiException(val safeReason: String) : IOException(safeReason)

/** Google's current API for Fitbit/Google Health cloud measurements. No client secret or backend. */
object GoogleHealthFeed {
    const val FEED = "google-health"
    private const val ROOT = "https://health.googleapis.com/v4/users/me"
    val scopes = listOf(
        "activity_and_fitness", "health_metrics_and_measurements", "sleep", "nutrition",
        "location", "ecg", "irn", "reproductive_health", "logged_symptoms", "mindfulness"
    ).map { "https://www.googleapis.com/auth/googlehealth.$it.readonly" }

    // DataPoint union fields in the official v4 REST schema, 6 October 2026.
    val types = listOf(
        "steps", "floors", "heart-rate", "sleep", "daily-resting-heart-rate",
        "daily-heart-rate-variability", "exercise", "weight", "altitude", "distance", "body-fat",
        "active-zone-minutes", "heart-rate-variability", "daily-sleep-temperature-derivations",
        "sedentary-period", "run-vo2-max", "oxygen-saturation", "daily-oxygen-saturation",
        "activity-level", "vo2-max", "daily-vo2-max", "nutrition-log", "irregular-rhythm-notification",
        "electrocardiogram", "daily-heart-rate-zones", "hydration-log", "food",
        "time-in-heart-rate-zone", "active-minutes", "respiratory-rate-sleep-summary",
        "daily-respiratory-rate", "swim-lengths-data", "height", "basal-energy-burned",
        "core-body-temperature", "active-energy-burned", "food-measurement-unit", "blood-glucose",
        "menstrual-period", "ovulation-test", "symptoms", "moods"
    )

    fun authRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(scopes.map { Scope(it) }).build()

    suspend fun token(context: Context): String {
        val result = Identity.getAuthorizationClient(context).authorize(authRequest()).await()
        if (result.hasResolution()) throw CloudApiException("authorization_required_open_app")
        return result.accessToken ?: throw CloudApiException("no_access_token")
    }

    suspend fun verify(token: String): JsonObject = get("$ROOT/identity", token)

    suspend fun export(context: Context, vault: VaultArchive, connected: Boolean) {
        if (!connected) {
            vault.feedStatus[FEED] = "not_connected_requires_approved_google_cloud_project"
            types.forEach { vault.unavailable(FEED, it, "not_connected") }
            return
        }
        var access = try { token(context) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            vault.feedStatus[FEED] = "authorization_required"
            types.forEach { vault.unavailable(FEED, it, "authorization_required") }
            return
        }
        vault.feedStatus[FEED] = "connected"
        for (type in types) vault.stream(FEED, type) { emit ->
            readEveryPage({ pageToken ->
                val size = if (type in setOf("sleep", "exercise")) 25 else 10000
                val url = "$ROOT/dataTypes/$type/dataPoints?pageSize=$size" +
                    (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                // No lower time filter: retrieve all history that this endpoint makes available.
                val response = try { get(url, access) } catch (e: CloudApiException) {
                    if (!e.safeReason.startsWith("HTTP_401_")) throw e
                    Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(access).build()).await()
                    access = token(context)
                    get(url, access)
                }
                val points = response.getAsJsonArray("dataPoints")?.map { it.asJsonObject } ?: emptyList()
                Page(points, response.get("nextPageToken")?.asString)
            }) { raw ->
                val source = raw.getAsJsonObject("dataSource")
                // Keep the complete original object, including source/device data and native units.
                emit(raw, source?.toString() ?: "source_not_reported")
            }
        }
    }

    private suspend fun get(url: String, token: String): JsonObject {
        require(URI(url).host == "health.googleapis.com")
        for (attempt in 0..5) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if ((status == 429 || status in 500..599) && attempt < 5) {
                    val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                    delay((retry ?: (2000L shl attempt)).coerceIn(1000, 120_000))
                    continue
                }
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    val reason = runCatching {
                        JsonParser.parseString(body ?: "{}").asJsonObject.getAsJsonObject("error")["status"].asString
                    }.getOrNull()?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "REQUEST_FAILED"
                    throw CloudApiException("HTTP_${status}_$reason")
                }
                return connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            } finally { connection.disconnect() }
        }
        throw CloudApiException("retry_exhausted")
    }
}
