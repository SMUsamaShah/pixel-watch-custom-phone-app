# Pixel Data Vault

A personal Android exporter with independent Health Connect and Google
Health/Fitbit cloud readers. The app exports raw records, including individual
heart samples, sleep stages, source metadata and explicit Health Connect
quantity units. It reads every API page without an app record or history cap.

**Current access limitation, checked 6 October 2026:** Google's Health API is
not onboarding new projects. The older Fitbit Web API stops functioning on
**30 October 2026**. The cloud reader targets the current Google Health API;
it cannot become a live connection until Google approves the project's access.
Installing this app alone does not restore missing Fitbit sleep data.
See [Google's current setup notice](https://developers.google.com/health/setup).

Health Connect export works independently of cloud registration. This app has
no developer server, paid service, analytics or advertising. The phone performs
the export; a separate always-running computer is not required.

## Use on the phone

1. Install the APK on the Android phone that holds your Health Connect data.
   If a previous proof-of-concept APK uses a different signing key, Android
   requires uninstalling that app first. This does not remove the Health Connect
   records maintained by the system.
2. Open Pixel Data Vault and tap **Grant Health Connect access**. Choose the
   types you want to export and allow historical/background access where your
   installed provider supports them. Denied types are reported in the ZIP.
3. Optionally enable medical records. This reads only FHIR resources actually
   stored in Health Connect on this phone, where supported. It does not connect
   to NHS services or import separate blood reports.
4. **Today on this phone** loads automatically. Heart rate, steps and last night's
   sleep appear first; expand **Show / hide other readings** for nine more types.
   Readings show measurement times and sources. Missing today's readings show
   the newest accessible historical reading rather than treating old samples
   or modification times as fresh. Steps use Health Connect's deduplicated total;
   raw interval records and source counts remain visible separately.
5. Tap **Choose export file (Google Drive or local)**. In Android's picker,
   choose your private Drive folder, for example `Health connect bkp`, and create
   `Health-Data-Vault.zip`. The original native `Health Connect.zip` is a
   separate export and can remain in that folder.
6. Tap **Export all accessible history** after the preview finishes. The app and
   notification show the active type, record/sample counts, latest measurement,
   local ZIP size, elapsed time and time since the last progress update. Each
   Health Connect request has a 60-second timeout; rate-limited reads retry three
   times with visible pauses. Timeout/partial-read errors remain in the report.
   **Cancel** stops a pending/running export or preview. The previous completed
   local ZIP is retained. The destination is written after the local ZIP finishes;
   file-copy progress is separate from source reads. A full first scan can still
   take several minutes for large histories. Keep the app open during setup.
7. After a successful export, optionally enable daily updates. Android controls
   execution time and can delay background jobs. The chosen document provider
   must support persistent write access; failures are shown in the app and the
   completed local ZIP remains available to save again.

**Version 0.3 update:** install the APK over the supplied 0.2 app. It uses the
same signing key, preserving granted permissions and the chosen destination.
On first open, the update cancels older jobs, including daily work; enable daily
updates again after checking the new export. **Save last completed ZIP** copies
the retained archive to the selected destination without rereading history.
Choosing a file alone never silently waits for a running full-history scan.

Daily updates currently rescan accessible history to reflect provider edits and
deletions without treating historical copies as current observations. Large
histories may make this expensive. Incremental caching is a future optimization.
Foreground export, background export and Drive-provider synchronization still
require validation on the user's phone.

Exercise sessions retain `ConsentRequired` route states. Route access can
require additional consent through **Health Connect settings**, separately from
normal exercise read permission. The exporter never claims that session access
implies route access.

## Connect Google Health / Fitbit

An approved Google Health API project is required. The following setup only
applies once Google has granted access; it is not a workaround for onboarding.

1. Enable the Google Health API in your approved Google Cloud project.
2. Configure its OAuth consent screen and allowed read scopes. Add yourself as
   a test user when applicable. Read Google's guidance on testing-mode expiry.
3. Create an **Android OAuth client** with package
   `com.usamashah.pixelvault` and the installed APK's **SHA-1 signing certificate
   fingerprint**. It must be the certificate used for that exact APK. Google
   Identity Services identifies the app from the package/certificate pair;
   there is no client secret to paste into the phone or source repository.
   The delivered APK's public fingerprint is in
   [docs/apk-signing-certificate.txt](docs/apk-signing-certificate.txt).
4. Sign into Google Health with the Google account holding the Fitbit history.
5. In Pixel Data Vault tap **Connect Google Health / Fitbit**. Approve the read
   scopes you want. The app verifies the linked account through `users/me/identity`.
   Unavailable types or scopes remain explicit in the coverage report.

The Android client uses `AuthorizationClient` to obtain short-lived access
tokens from Google Play services, including subsequent authorized sessions.
Tokens are not written to export files, preferences, logs or the repository.
There is no embedded webview, developer backend or stored OAuth client secret.
See [Android authorization guidance](https://developer.android.com/identity/authorization).
Cloud authorization and real data retrieval have not been tested against an
approved project.

## ZIP format and coverage

Each line in `health-connect/<RecordType>.ndjson` is a Health Connect parent
record with public properties and nested samples/stages.
`google-health/<data-type>.ndjson` preserves the original cloud data-point JSON.
Medical resources, when enabled, go in `health-connect-medical/<TYPE>.ndjson`.

Health Connect quantities use explicit canonical unit/value objects. Cloud JSON
stays in the API's own schema and units. Enum codes and raw source/device fields
are retained. Medical FHIR JSON remains intact inside `fhirResource.data`.

`manifest.json` has schema version 2 and contains:

- separate connection statuses for each feed;
- the attempted type catalog, record counts and origin counts;
- actual earliest/latest measurement timestamps and date-only daily metrics;
- `read_complete`, `incomplete`, permission-missing, unavailable-feature and
  disconnected states, with safe failure codes;
- history, route-consent and completeness limitations.

`read_complete` means the API's returned pages were exhausted. It does not prove
that every original wearable measurement reached that API, or that historical
access was unrestricted. The catalog covers all **42 fitness record types** in
the pinned Health Connect SDK and all **42 data types** in the current Google
Health v4 discovery schema; `docs/data-catalog.json` records the verification.
Medical resources use separate feature/permission checks.

Keep sources separate during analysis. Raw step, distance and calorie records
can overlap. Sleep session duration is not staged time asleep. Sample-average
HR is not resting HR; use actual resting-HR and RMSSD data. Export creation times
and source modification times are not measurement freshness. Google Health's
cloud endpoints can have different resolution and available types from the
phone's Health Connect store.

The app finishes a ZIP in private storage before copying it to the chosen
document. Document-provider writes are not guaranteed atomic: a failed write
can leave the destination incomplete. The local finished copy is retained, and
the reader rejects invalid archives/count mismatches.

## Read an export

The Python reader uses only the standard library and has no record cap:

```bash
python3 tools/read-vault-export.py Health-Data-Vault.zip \
  --timezone Europe/London --out coverage.json
```

It streams the records, verifies counts and ZIP integrity, and reports actual
measurement freshness and source coverage. It does not add overlapping sources,
invent units or produce health advice from stale measurements.

## Build and verification

Use JDK 17 and Android SDK 37 (build tools 36.0.0). The app still supports
Android 9 and later; compiling against SDK 37 does not change its minimum API:

```bash
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

The GitHub Actions workflow builds the APK and runs these checks. The APK is at
`app/build/outputs/apk/debug/app-debug.apk`. Debug APK certificates vary by build
environment; register the certificate of the APK that you install.

The pinned Health Connect alpha SDK needs AGP 9.1.1 and compile SDK 37.0,
selected explicitly with `release(37) { minorApiLevel = 0 }`.
Its companion status constants cause false `WrongConstant` lint errors under
this toolchain. Only the two SDK/feature availability comparisons suppress
that rule, using the SDK's own declared constants. Other lint errors still
fail the build. Personal-app text/localization and KTX style warnings remain.

Regression tests cover pagination beyond 2,000 records, empty pages with more
data, repeated-token failure, raw sample/stage preservation, explicit distance
units, stale samples inside newer parent intervals, date-only RHR, overlapping
origins, independent feeds, late page failures, archive counts and London DST.
These are code-level checks; the app still needs a first phone export to verify
the installed Health Connect and Drive providers.

The earlier [APK architecture audit](docs/reverse-engineering-findings.md)
remains available. The current app uses official public data APIs.
