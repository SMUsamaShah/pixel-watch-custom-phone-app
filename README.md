# Pixel Data Vault — first proof of concept

This is the first milestone for the Pixel Watch data-control project. It is a
phone-side Android app that reads `HeartRateRecord` data from Health Connect,
preserves every timestamped BPM sample, reports the observed sample cadence,
and exports the unaggregated records as JSON.

It intentionally does not impersonate Google's package, access private Google
Wearable Data Layer paths, or send health data to a server. The user grants the
Health Connect permissions explicitly, and the only export is initiated with a
system file picker.

## What the APK audit established

The supplied Google Wear OS companion APK is centered on
`CompanionDeviceManager` and Google Play Services' Wearable Data Layer. Its
Data Layer listeners and companion service are app/package scoped, so copying
Google's paths into another package would not give that package access to
Google's private data items.

The supplied Fitbit Mobile APK has a direct Health Connect adapter. Its read
path builds a `ReadRecordsRequestUsingFilters`, calls
`HealthConnectManager.readRecords`, follows page tokens, and converts
`HeartRateRecord.getSamples()` into timestamped BPM samples. The same APK's
cloud time-series client exposes only `1min` and `15min` granularity modes; the
heart-rate mapping selects `1min`. That is useful evidence about the app's
presentation/cloud path, but the phone-side Health Connect audit is the
correct way to test what data is actually available for this user and device.

## Build

Open this directory in Android Studio with Android SDK 36 installed, or run:

```bash
./gradlew --no-daemon assembleDebug
```

The checked-in GitHub Actions workflow builds the debug APK on every push and
can also be started manually from the Actions tab.

## Test on the phone

1. Install the debug APK on the Android phone paired with the Pixel Watch.
2. Let the normal watch/phone sync complete.
3. Open Pixel Data Vault and grant heart-rate access plus historical access.
4. Tap **Scan last 7 days**.
5. Read the per-origin minimum/median/maximum sample interval.
6. Export the JSON and keep it as the first raw-data fixture for the project.

Interpretation:

- roughly `1.00 s` shows that second-level samples reached Health Connect;
- roughly `60.00 s` shows the sync path has already reduced them to one-minute samples;
- gaps or multiple origins indicate sync boundaries, source duplication, or missing watch periods.

## Next milestones

1. Add a local SQLite/Parquet-style vault and incremental Health Connect change-log sync.
2. Add raw records for calories, steps, distance, exercise, sleep, oxygen,
   respiratory rate, HRV, skin temperature, and metadata/device attribution.
3. Add an optional Wear OS companion using Health Services for live readings and
   the app's own Wearable Data Layer channel.
4. Add a desktop viewer that consumes the exported local vault over USB, local
   Wi-Fi, or an explicitly chosen file sync path.

The direct-Bluetooth route remains a research track. It may be needed only if
the phone-side stores do not contain the resolution we want; it should not be
the first implementation because pairing, authentication, encryption, and
Google/Fitbit private protocols make it considerably more brittle.
