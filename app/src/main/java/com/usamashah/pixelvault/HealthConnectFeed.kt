@file:OptIn(androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi::class)

package com.usamashah.pixelvault

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.HealthConnectFeatures.Companion.FEATURE_STATUS_AVAILABLE
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.reflect.KClass

object HealthConnectFeed {
    const val FEED = "health-connect"
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val recordTypes: List<KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class, ActivityIntensityRecord::class,
        BasalBodyTemperatureRecord::class, BasalMetabolicRateRecord::class,
        BloodGlucoseRecord::class, BloodPressureRecord::class, BodyFatRecord::class,
        BodyTemperatureRecord::class, BodyWaterMassRecord::class, BoneMassRecord::class,
        CervicalMucusRecord::class, CyclingPedalingCadenceRecord::class, DistanceRecord::class,
        ElevationGainedRecord::class, ExerciseSessionRecord::class, FloorsClimbedRecord::class,
        HeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, HeightRecord::class,
        HydrationRecord::class, IntermenstrualBleedingRecord::class, LeanBodyMassRecord::class,
        MenstruationFlowRecord::class, MenstruationPeriodRecord::class, MindfulnessSessionRecord::class,
        NutritionRecord::class, OvulationTestRecord::class, OxygenSaturationRecord::class,
        PlannedExerciseSessionRecord::class, PowerRecord::class, RespiratoryRateRecord::class,
        RestingHeartRateRecord::class, SexualActivityRecord::class, SkinTemperatureRecord::class,
        SleepSessionRecord::class, SpeedRecord::class, StepsCadenceRecord::class, StepsRecord::class,
        TotalCaloriesBurnedRecord::class, Vo2MaxRecord::class, WeightRecord::class, WheelchairPushesRecord::class
    )
    private val features = mapOf(
        ActivityIntensityRecord::class to HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY,
        MindfulnessSessionRecord::class to HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION,
        SkinTemperatureRecord::class to HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE,
        PlannedExerciseSessionRecord::class to HealthConnectFeatures.FEATURE_PLANNED_EXERCISE
    )
    private val medicalSuffixes = listOf(
        "VACCINES", "ALLERGIES_INTOLERANCES", "PREGNANCY", "SOCIAL_HISTORY", "VITAL_SIGNS",
        "LABORATORY_RESULTS", "CONDITIONS", "PROCEDURES", "MEDICATIONS", "PERSONAL_DETAILS",
        "PRACTITIONER_DETAILS", "VISITS"
    )

    // AGP 9.1.1 lint misidentifies the pinned alpha SDK's companion IntDef constants.
    // Use the SDK's declared constants; suppress only these two status comparisons.
    @SuppressLint("WrongConstant")
    fun providerAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == SDK_AVAILABLE

    @SuppressLint("WrongConstant")
    fun available(client: HealthConnectClient, feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == FEATURE_STATUS_AVAILABLE

    fun supported(client: HealthConnectClient, type: KClass<out Record>): Boolean =
        features[type]?.let { available(client, it) } ?: true

    fun permissions(client: HealthConnectClient, medical: Boolean = false): Set<String> = buildSet {
        addAll(recordTypes.filter { supported(client, it) }.map { HealthPermission.getReadPermission(it) })
        if (available(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HISTORY)
        if (available(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(BACKGROUND)
        if (medical && available(client, HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD)) {
            addAll(medicalSuffixes.map { "android.permission.health.READ_MEDICAL_DATA_$it" })
        }
    }

    suspend fun export(client: HealthConnectClient, vault: VaultArchive, background: Boolean, medical: Boolean) {
        val granted = client.permissionController.getGrantedPermissions()
        val backgroundAllowed = !background || BACKGROUND in granted
        vault.feedStatus[FEED] = if (backgroundAllowed) "connected" else "background_permission_missing"
        vault.notes += if (HISTORY in granted) "Health Connect history permission granted."
            else "Health Connect history permission missing: the provider may restrict reads to the accessible recent history."
        if (HealthPermission.PERMISSION_READ_EXERCISE_ROUTES !in granted) {
            vault.notes += "Exercise routes can require separate consent in Health Connect settings. ConsentRequired/NoData states are retained; session access does not imply access to every route."
        }
        for (type in recordTypes) {
            val name = type.java.simpleName
            when {
                !supported(client, type) -> vault.unavailable(FEED, name, "device_feature_unavailable")
                HealthPermission.getReadPermission(type) !in granted -> vault.unavailable(FEED, name, "permission_missing")
                !backgroundAllowed -> vault.unavailable(FEED, name, "background_permission_missing")
                else -> vault.stream(FEED, name) { emit ->
                    // Open lower boundary: read every accessible historical record, without a date cap.
                    readEveryPage({ token ->
                        val response = readWithBackoff {
                            client.readRecords(ReadRecordsRequest(
                                type, TimeRangeFilter.before(vault.startedAt), pageSize = 1000, pageToken = token
                            ))
                        }
                        Page(response.records, response.pageToken)
                    }) { record ->
                        emit(PublicRecordJson.encode(record).asJsonObject, record.metadata.dataOrigin.packageName)
                    }
                }
            }
        }
        if (!medical) {
            vault.feedStatus["health-connect-medical"] = "not_enabled"
            return
        }
        if (!available(client, HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD)) {
            vault.feedStatus["health-connect-medical"] = "device_feature_unavailable"
            return
        }
        vault.feedStatus["health-connect-medical"] = "enabled"
        val sourceIds = mutableSetOf<String>()
        for ((i, suffix) in medicalSuffixes.withIndex()) {
            val feed = "health-connect-medical"
            if ("android.permission.health.READ_MEDICAL_DATA_$suffix" !in granted) {
                vault.unavailable(feed, suffix, "permission_missing")
            } else if (!backgroundAllowed) {
                vault.unavailable(feed, suffix, "background_permission_missing")
            } else vault.stream(feed, suffix) { emit ->
                readEveryPage({ token ->
                    val request = if (token == null) ReadMedicalResourcesInitialRequest(i + 1, emptySet(), 1000)
                        else ReadMedicalResourcesPageRequest(token, 1000)
                    val response = readWithBackoff { client.readMedicalResources(request) }
                    Page(response.medicalResources, response.nextPageToken)
                }) { resource ->
                    sourceIds += resource.dataSourceId
                    emit(PublicRecordJson.encode(resource).asJsonObject, resource.dataSourceId)
                }
            }
        }
        if (sourceIds.isNotEmpty()) vault.stream("health-connect-medical", "sources") { emit ->
            for (ids in sourceIds.toList().chunked(100)) {
                for (source in client.getMedicalDataSources(ids)) {
                    emit(PublicRecordJson.encode(source).asJsonObject, source.id)
                }
            }
        }
    }

    private suspend fun <T> readWithBackoff(block: suspend () -> T): T {
        for (attempt in 0..7) {
            try {
                val value = block()
                delay(250)
                return value
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (attempt == 7 || e.message?.contains("rate limit", ignoreCase = true) != true) throw e
                delay((15_000L shl attempt).coerceAtMost(120_000L))
            }
        }
        error("Unreachable")
    }
}
