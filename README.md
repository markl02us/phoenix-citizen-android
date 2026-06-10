# PHOENIX Citizen — Android (Kotlin + Compose)

Native Android app for PHOENIX wildfire citizen reporting. Lets Sicilian (and
eventually other) residents instantly report fires from their phone. Reports
are validated against satellite detections (FIRMS / VIIRS / EUMETSAT / SLSTR)
and farmer-network corroboration on the PHOENIX backend.

- **Backend:** `https://adr-wildfire.com/`
- **Stack:** Kotlin 1.9.24, Jetpack Compose (Material 3), AGP 8.5.x
- **Min SDK:** 26 (Android 8.0) — covers ~95% of Italian Android users
- **Target SDK:** 34 (Android 14)
- **License:** MIT

---

## 1. Quick start

```bash
git clone https://github.com/markl02us/phoenix-citizen-android.git
cd phoenix-citizen-android
# Open in Android Studio Iguana+ (or run from CLI):
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can install/sideload that APK on any Android 8.0+ device with USB debugging
enabled or via the device's "Install unknown apps" permission.

---

## 2. Architecture overview

```
ui/screens/        Compose screens (Quick, Map, ReportForm, History, Settings)
ui/theme/          PHOENIX Material 3 theme (red primary, ember secondary, signal-blue tertiary)
viewmodel/         AndroidViewModels per screen — StateFlow-driven
data/api/          Retrofit + Kotlinx Serialization
data/db/           Room (reports table, single source of truth for history + queue)
data/repository/   ReportRepository facade + DevicePrefs (DataStore)
worker/            WorkManager periodic SyncWorker (15-min) drains queue, refreshes corroboration
notification/      FCM stub service + 2 channels (nearby_fires, confirmed)
util/              LocationProvider (FusedLocationProviderClient), TimeUtils, IntegrityProvider (Play Integrity)
```

**Offline-first:** Every report is written to Room first, then submitted.
WorkManager retries failed/queued rows whenever the device has network.

---

## 3. Building from source

Requires:

- JDK 17 (Temurin recommended)
- Android Studio Iguana (2023.2) or newer
- Android SDK with API 34 platform

```bash
# Debug
./gradlew assembleDebug

# Release (after signing config is set up)
./gradlew assembleRelease

# Run all unit tests
./gradlew testDebugUnitTest

# Clean
./gradlew clean
```

Gradle 8.7 is fetched automatically by the wrapper.

---

## 4. Backend API contract

The app calls four endpoints on `https://adr-wildfire.com/`:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/citizen_report` | POST | Submit a fire report |
| `/api/citizen_report_status?report_id=X` | GET | Check corroboration of a submitted report |
| `/api/detections?period=24h&bbox=south,west,north,east` | GET | Active detections for map |
| `/api/source_health` | GET | Backend health for Settings diagnostic |

POST body schema (sent as JSON):

```json
{
  "device_hash":  "stable-uuid-per-install",
  "lat":          37.5,
  "lon":          14.0,
  "ts_utc":       "2026-06-08T12:34:56Z",
  "observation_type": "flame" | "smoke" | "unsure",
  "wind_direction_observed": "N|NE|E|SE|S|SW|W|NW" (optional),
  "photo_path":   "content://..." (optional),
  "note":         "free text up to 500 chars" (optional)
}
```

Every POST carries `X-Integrity-Token` (Play Integrity API token) for
device attestation. **Backend TODO:** verify this token against Google's
attestation service before accepting submissions.

---

## 5. Google services setup

The app uses three Google APIs. Each requires a one-time setup in Google
Cloud / Firebase Console.

### 5.1 Maps SDK for Android

1. Go to <https://console.cloud.google.com/google/maps-apis/credentials>
2. Create an API key restricted to the Android package `com.phoenix.citizen` (and `com.phoenix.citizen.debug` for debug builds) with the SHA-1 of your signing key.
3. Enable **Maps SDK for Android**.
4. Add the key to `local.properties` (NOT committed):

    ```
    MAPS_API_KEY=AIzaSy...your_real_key
    ```

   Or set the `MAPS_API_KEY` env var in CI.

### 5.2 Firebase Cloud Messaging (push notifications)

1. Create a Firebase project at <https://console.firebase.google.com>
2. Add an Android app with package `com.phoenix.citizen`
3. Download the generated `google-services.json` and place it at `app/google-services.json` (excluded from git).
4. In `app/build.gradle.kts`, uncomment:

    ```kotlin
    // implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    // implementation("com.google.firebase:firebase-messaging-ktx")
    ```

5. In root `build.gradle.kts`, uncomment the `com.google.gms.google-services` plugin.
6. In `app/build.gradle.kts`, uncomment `id("com.google.gms.google-services")`.
7. In `PhoenixMessagingService.kt`, change the superclass to `com.google.firebase.messaging.FirebaseMessagingService` and override `onMessageReceived`.

### 5.3 Play Integrity API

1. Link your app to a Google Cloud project (Firebase Console → Project settings → Integrations → Google Cloud).
2. Note the **Project number** (under Project info).
3. Enable **Play Integrity API** in <https://console.cloud.google.com/apis/library/playintegrity.googleapis.com>
4. Set the constant in `util/IntegrityProvider.kt`:

    ```kotlin
    const val CLOUD_PROJECT_NUMBER: Long = 123456789012L
    ```

5. **Backend TODO:** Server must call `playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken` to validate the `X-Integrity-Token` header.

---

## 6. Sideloading the debug APK from CI

After every push to `main`/`master`, GitHub Actions builds a debug APK.

1. Open <https://github.com/markl02us/phoenix-citizen-android/actions>
2. Click the latest successful `build` run.
3. Download the `phoenix-citizen-debug-apk` artifact (a `.zip` containing `app-debug.apk`).
4. Unzip, transfer to phone (USB, ADB, or upload to Drive/email link), and tap to install. On the device enable "Install unknown apps" for the source.

```bash
# Via ADB
adb install -r ~/Downloads/app-debug.apk
```

---

## 7. Publishing to Google Play

### 7.1 Signing config

Generate an upload keystore (do this **once**):

```bash
keytool -genkey -v -keystore phoenix-upload.jks -keyalg RSA -keysize 2048 -validity 25000 -alias phoenix-upload
```

Store keystore + password in a `release-keystore.properties` file (not committed):

```properties
storeFile=/abs/path/to/phoenix-upload.jks
storePassword=...
keyAlias=phoenix-upload
keyPassword=...
```

Wire it into `app/build.gradle.kts` `signingConfigs { release { ... } }`
and reference it from `buildTypes.release.signingConfig`.

Generate signed AAB:

```bash
./gradlew bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

### 7.2 Play Console checklist

1. Create app in <https://play.google.com/console>
2. App content questionnaire: target audience, data safety form (declare: location, photo, device ID; all required for fire reporting)
3. Content rating: Wildfire / public safety / Everyone
4. **Required assets:**
    - App icon (512×512, already in `mipmap-anydpi-v26/`)
    - Feature graphic (1024×500)
    - Phone screenshots (min 2, max 8, 16:9 or 9:16)
    - Short description (≤80 chars), Full description (≤4000 chars)
5. Privacy policy URL: `https://adr-wildfire.com/privacy` (server TODO: publish the page)
6. Upload signed AAB to Internal testing track first → Closed testing → Production
7. Country availability: Italy first, then EU
8. Pricing: Free

---

## 8. Privacy policy template

A minimal privacy policy is required by Google Play. Suggested outline
(host at `https://adr-wildfire.com/privacy`):

- What we collect: lat/lon (when you submit a report), optional photo, optional note, device ID (random per-install UUID, NOT ANDROID_ID), Play Integrity attestation token.
- Why: to confirm wildfires and surface them publicly through PHOENIX.
- Retention: indefinite for confirmed reports (research record); rejected reports purged 30 days after rejection.
- Sharing: With Italian fire authorities (VVF) and research collaborators. Never sold.
- User rights: GDPR — email contact for deletion requests.
- Contact: <your-email@adr-wildfire.com>

---

## 9. Acknowledgments

- **PHOENIX team** — research, backend, satellite data pipeline.
- **Gaetano** — ground truth and Sicily operations.
- **FIRMS, VIIRS, MODIS, SLSTR, EUMETSAT** — every detection we corroborate against.
- **VVF, ANSA, Italian regional press** — sole-reporter signal we never want to compete with.

---

## 10. TODO list (what Mark must complete to ship)

- [ ] Create Firebase project, drop `google-services.json` into `app/`, enable FCM dependencies in gradle.
- [ ] Get Google Maps Android SDK key, restrict to package + SHA-1, add to `local.properties` as `MAPS_API_KEY=...`.
- [ ] Set `CLOUD_PROJECT_NUMBER` in `util/IntegrityProvider.kt`.
- [ ] Build backend endpoint `/api/citizen_report` if not yet live (handle X-Integrity-Token validation).
- [ ] Build backend endpoint `/api/citizen_report_status`.
- [ ] Confirm backend endpoint `/api/detections` returns the JSON shape in `data/model/Detection.kt`.
- [ ] Build backend endpoint `/api/source_health`.
- [ ] Publish `https://adr-wildfire.com/privacy`.
- [ ] Publish `https://adr-wildfire.com/come-funziona`.
- [ ] Generate upload keystore, wire signingConfig.
- [ ] Capture 4-8 phone screenshots for Play Store listing.
- [ ] Create 1024×500 feature graphic for Play Store.
- [ ] Run app store text descriptions through editor (IT + EN).
- [ ] Complete Play Console data-safety form + content rating.
- [ ] Internal testing track for 1-2 weeks before promoting to production.

---

**Generated 2026-06-08 — PHOENIX Citizen Android v0.1.0**
