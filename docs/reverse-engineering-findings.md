# Reverse-engineering notes

These notes record the first APK audit that informed the proof of concept. They
are observations about the supplied APK versions, not claims about private APIs
being stable.

## Google Wear OS companion

- The phone companion is centered on `CompanionDeviceManager` and Google Play
  Services' Wearable Data Layer.
- Data Layer listeners and companion services are package-scoped. Copying a
  Google path into another application does not grant access to Google's
  private data items.
- The practical first integration point is therefore the official Health
  Connect store on the phone, followed by an app-owned Wear OS channel for
  future live readings.

## Fitbit Mobile

- The APK contains a Health Connect adapter that builds a read request,
  follows page tokens, and converts `HeartRateRecord` samples into timestamped
  BPM values.
- Its cloud time-series client exposes `1min` and `15min` modes for the
  inspected data path. The heart-rate mapping selects `1min`.
- This makes Health Connect the right place to test whether higher-resolution
  samples survived synchronization before assuming that the cloud UI is the
  source of the data loss.

## What this app tests

`Pixel Data Vault` asks for heart-rate read access and historical access, reads
the last seven days, preserves every returned `HeartRateRecord.sample`, reports
the minimum/median/maximum observed sample interval per data origin, and lets
the user export the unaggregated records as JSON.

It deliberately does not impersonate Google's package, access private Wearable
Data Layer paths, or send health data to a server.
