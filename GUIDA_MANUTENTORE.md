# PHOENIX Citizen — Maintainer & Developer Guide

Application package: `com.phoenix.citizen` · App name: **PHOENIX Cittadino** (ADRIZ v2 citizen reporting)
Backend: `https://adr-wildfire.com/` · Stack: Kotlin 1.9.24 + Jetpack Compose (Material 3) · Min SDK 26 (Android 8.0) · Target SDK 34 (Android 14) · License: MIT

This document is the official hand-off package for the PHOENIX citizen Android application. It is written so that a developer who has never seen the project before can open it, understand every module, find exactly where a given change has to be made, build it, and ship it. The structure and reference tables are in English. The walkthrough explanations — the parts that actually tell you what the software does and how to change it — are written by Mark, in Italian, right next to the part of the code they describe.

---

## 1. Overview

PHOENIX Cittadino is the native Android app that lets a resident report a wildfire from their phone with a single tap. Each report is written to the phone first (offline-first), then sent to the PHOENIX backend at `adr-wildfire.com`, where it is cross-checked against satellite detections (FIRMS / VIIRS / EUMETSAT / SLSTR) and the corroboration network. The app also shows a live map of active detections, a personal history of the user's own reports with their corroboration status, and a settings screen.

> Ciao Gaetano. Questa è l'app intera, sorgente grezzo, niente nascosto. Tu la puoi aprire, leggere, e cambiare come vuoi — è tutta tua da modificare. L'idea è semplice: una persona vede fumo o fiamme, apre l'app, preme un bottone, e la segnalazione parte verso PHOENIX con posizione e ora. Tutto il resto del documento ti dice dove mettere le mani se vuoi cambiare una cosa precisa. Quando scrivo "cambia qui se vuoi X", intendo proprio il file e la riga giusta, così non devi cercare a caso.

Architectural shape: single-Activity Compose app, MVVM, with a Room database as the single source of truth for both the offline queue and the history list. A 15-minute WorkManager job drains anything that failed to send and refreshes corroboration status.

```
ui/screens/        Compose screens (Quick, Map, ReportForm, History, Settings)
ui/theme/          Material 3 theme (red primary, ember secondary, signal-blue tertiary)
viewmodel/         One AndroidViewModel per screen, StateFlow-driven
data/api/          Retrofit + Kotlinx Serialization — the PHOENIX HTTP contract
data/db/           Room: the `reports` table (queue + history in one place)
data/repository/   ReportRepository facade + DevicePrefs (DataStore)
worker/            WorkManager periodic SyncWorker (15 min)
notification/      FCM stub service + 2 notification channels
util/              LocationProvider, TimeUtils, IntegrityProvider
```

---

## 2. Full file & module map

Each row: path → what it does → where to make a change.

### Project root

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `build.gradle.kts` | Top-level Gradle, declares plugin versions (AGP 8.5.2, Kotlin 1.9.24). | Bump Android Gradle Plugin / Kotlin / KSP versions. |
| `settings.gradle.kts` | Declares the single `:app` module and repositories. | Add a second module or a new Maven repo. |
| `gradle.properties` | JVM args, AndroidX flags, `MAPS_API_KEY` placeholder. | Change memory limits or the default Maps-key placeholder. |
| `gradle/wrapper/gradle-wrapper.properties` | Pins Gradle 8.7. | Upgrade the Gradle version. |
| `gradlew` / `gradlew.bat` | Gradle wrapper launchers (Linux/macOS · Windows). | (Normally never edited.) |
| `.github/workflows/build.yml` | GitHub Actions: builds a debug APK on every push, uploads it as an artifact. | Change the CI build, add a signed-release job, or change triggers. |
| `.gitignore` | Excludes secrets (`local.properties`, `google-services.json`, keystores) and build output. | Add new files that must never be committed. |
| `LICENSE` | MIT license. | Change licensing terms. |

> Tutta la configurazione di compilazione sta in questi file alla radice. Se l'app non compila dopo un aggiornamento di Android Studio, il 90% delle volte è qui che cambi una versione (in `build.gradle.kts` in alto, o in `gradle-wrapper.properties`). Non toccare `gradlew`: è solo il lanciatore.

### `app/` build configuration

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `app/build.gradle.kts` | The real build file: `applicationId`, SDK levels, **the backend URL** (`API_BASE_URL = "https://adr-wildfire.com/"`), all dependencies, the Maps-key wiring. | Change the backend URL, version number, min SDK, or add/remove a library. |
| `app/proguard-rules.pro` | ProGuard/R8 rules for release builds. | Add keep-rules if a release build strips something it shouldn't. |
| `app/src/main/AndroidManifest.xml` | Permissions, the launcher Activity, the Maps key meta-data, the FileProvider, the FCM service. | Add a permission, register a new screen/service, change the app icon/label reference. |

> Il file più importante per te è `app/build.gradle.kts`. Lì dentro, cerca `API_BASE_URL`: è scritto tre volte (debug, release e default) e punta a `https://adr-wildfire.com/`. Se un giorno vuoi puntare l'app a un altro server — per esempio un tuo server di prova, o un server tutto tuo per i tuoi sensori — cambi quelle tre righe e basta. Il numero di versione (`versionName`, `versionCode`) lo alzi sempre prima di pubblicare una versione nuova.

### Application & entry point

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `…/citizen/MainActivity.kt` | The single Activity. Hosts the Compose `NavHost` and the bottom navigation bar (Quick / Map / History / Settings). | Add a new tab/screen, reorder the bottom nav, change navigation. |
| `…/citizen/PhoenixApp.kt` | `Application` subclass. Creates the two notification channels and schedules the 15-min `SyncWorker`. | Change notification channels or the background-sync interval. |

> `MainActivity.kt` è la mappa di tutte le schermate. Le quattro voci in basso (Rapido, Mappa, Storico, Impostazioni) sono definite qui. Se vuoi aggiungere una schermata nuova, la registri qui dentro nel `NavHost`. `PhoenixApp.kt` è dove decidi ogni quanto l'app prova a re-inviare le segnalazioni rimaste in coda: ora è 15 minuti.

### Screens (`ui/screens/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `QuickReportScreen.kt` | The big one-tap "🔥 SEGNALA INCENDIO" button. Asks for location permission, then fires a one-tap flame report. | Change the main report button, its text, or the one-tap behaviour. |
| `ReportFormScreen.kt` | The detailed form: photo, observation type (flame/smoke/unsure), wind direction, note, editable lat/lon. | Add/remove a field on the detailed report form. |
| `MapScreen.kt` | Google Map of active detections in the visible bounding box, with a PHOENIX/FIRMS/EUMETSAT legend. | Change the map, markers, legend, or detection filtering. |
| `HistoryScreen.kt` | List of the user's own past reports with their sync + corroboration status. | Change how report history is displayed. |
| `SettingsScreen.kt` | Language, push toggle, reputation tier, backend health diagnostic. | Add a setting or change diagnostics. |

> Le schermate sono tutte qui. Quella che conta di più è `QuickReportScreen.kt`: è il bottone grosso rosso. Il testo del bottone NON è scritto dentro questo file — è in `strings.xml` (vedi sotto), così resta tradotto. Se vuoi che un tocco solo mandi "fumo" invece di "fiamma", il punto da cambiare è il ViewModel collegato, non la schermata.

### ViewModels (`viewmodel/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `QuickReportViewModel.kt` | One-tap logic: get GPS, build a `FLAME` report, persist, attempt POST. | Change what the one-tap button sends (e.g. default to SMOKE). |
| `ReportFormViewModel.kt` | Holds the detailed-form state and submits it. | Change validation or submission of the detailed form. |
| `MapViewModel.kt` | Fetches detections for the current map bounds. | Change detection refresh/filtering logic. |
| `HistoryViewModel.kt` | Streams the local report list from Room. | Change ordering/grouping of history. |
| `SettingsViewModel.kt` | Reads/writes preferences, pulls `source_health`. | Change settings persistence or diagnostics. |

> I ViewModel sono il "cervello" di ogni schermata: la schermata disegna, il ViewModel decide. In `QuickReportViewModel.kt`, nel metodo `submitFlameAtCurrentLocation()`, vedi tutta la sequenza: prendi GPS → costruisci la segnalazione → salva → invia. Se cambi una sola riga lì, cambi cosa fa il bottone principale.

### Data layer — API (`data/api/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `PhoenixApi.kt` | The Retrofit interface — the exact list of PHOENIX endpoints the app calls. | Add a new backend endpoint or change an existing route/params. |
| `NetworkModule.kt` | Builds the Retrofit/OkHttp client, timeouts, JSON config; reads `API_BASE_URL` from BuildConfig. | Change timeouts, add an auth header globally, swap JSON config. |

> Questo è il cuore del collegamento con PHOENIX. `PhoenixApi.kt` è la lista esatta di tutto quello che l'app chiede al server: una riga per ogni endpoint. `NetworkModule.kt` è chi costruisce la connessione. L'indirizzo del server NON è scritto qui dentro: arriva da `API_BASE_URL` in `app/build.gradle.kts`. Lo dico apposta due volte perché è la domanda numero uno: "dove cambio il server?" → in `build.gradle.kts`.

### Data layer — models (`data/model/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `Models.kt` | All wire types: `CitizenReportPost` (what gets sent), `CitizenReportResponse`, `CitizenReportStatus`, `Detection`, `SourceHealth`, plus the `ObservationType` / `WindDirection` enums. | Add a field to a report, add an observation type, or match a backend schema change. |

> Qui c'è la "forma" esatta dei dati che viaggiano tra app e server. `CitizenReportPost` è quello che parte quando segnali: device, lat, lon, ora, tipo (fiamma/fumo/non sicuro), vento, foto, nota. Se il server un giorno aggiunge un campo nuovo, lo aggiungi qui e i nomi devono combaciare esattamente (`@SerialName`).

### Data layer — database (`data/db/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `ReportEntity.kt` | The Room row for one report: `QUEUED` / `SYNCED` / `FAILED` status, corroboration fields. | Add a stored column to a report. |
| `ReportDao.kt` | Room queries (insert, update, by-status, pending-corroboration). | Add a new query over stored reports. |
| `PhoenixDatabase.kt` | The Room database definition + singleton. | Bump DB version / add a migration when the schema changes. |

> Il telefono salva SEMPRE la segnalazione in locale prima di provare a mandarla. Per questo c'è il database `reports`. Stato `QUEUED` = ancora da inviare, `SYNCED` = il server l'ha presa, `FAILED` = invio non riuscito (riproverà da solo). Così, se non c'è rete sul monte, non perdi niente: parte appena torna il segnale.

### Data layer — repository (`data/repository/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `ReportRepository.kt` | **The center of gravity.** Saves every report locally first, then attempts the POST, drains the queue, refreshes corroboration, fetches detections + health. | Change the offline-first logic, retry behaviour, or how a report reaches PHOENIX. |
| `DevicePrefs.kt` | DataStore: stable per-install device hash (random UUID, NOT ANDROID_ID), language, push toggle, reputation tier. | Change device identity or stored preferences. |

> Se devi capire "come arriva una segnalazione a PHOENIX", leggi `ReportRepository.kt`, metodo `submitOrQueue()` e `attemptSend()`. È tutto lì: salva → costruisce il corpo JSON → chiede un token di sicurezza → fa la POST → aggiorna lo stato nel database. Il `device_hash` è un codice casuale generato la prima volta che apri l'app: NON è il numero di telefono, NON è l'ID del telefono. Serve solo per legare insieme le tue segnalazioni senza identificarti.

### Background, notifications, utilities

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `worker/SyncWorker.kt` | The 15-min job: drains queued/failed reports, refreshes corroboration. | Change background retry frequency or what runs in the background. |
| `notification/PhoenixMessagingService.kt` | Push-notification service (stubbed until Firebase is configured) + builds notifications. | Wire up Firebase push, change notification content. |
| `util/LocationProvider.kt` | Coroutine wrapper around fused GPS. | Change how/when location is acquired. |
| `util/IntegrityProvider.kt` | Play Integrity attestation → the `X-Integrity-Token` header (anti-spam, replaces CAPTCHA). | Enable device attestation (set `CLOUD_PROJECT_NUMBER`). |
| `util/TimeUtils.kt` | UTC ISO timestamp helper. | Change time formatting. |

> Due cose importanti qui. `SyncWorker.kt` è l'angelo custode: ogni 15 minuti controlla se c'è qualcosa rimasto da inviare e lo manda. `IntegrityProvider.kt` è l'antispam: invece di far digitare un CAPTCHA alla gente (che davanti a un incendio è l'ultima cosa che vuoi), il telefono dimostra da solo di essere un telefono vero. Finché non metti il `CLOUD_PROJECT_NUMBER`, è spento e l'app funziona lo stesso.

### Resources (`app/src/main/res/`)

| Path | What it does | Change here if you want to… |
|------|--------------|------------------------------|
| `values/strings.xml` | English (default) UI text. | Change English wording. |
| `values-it/strings.xml` | **Italian UI text** — what your users actually read. | Change any Italian label/button text. |
| `values/colors.xml`, `values/themes.xml`, `values-night/themes.xml` | Colours and light/dark themes. | Restyle the app. |
| `drawable/` | Vector icons (fire icon, launcher foreground). | Change icons. |
| `mipmap-anydpi-v26/` | Adaptive launcher icon. | Change the home-screen icon. |
| `xml/` | FileProvider paths, backup & data-extraction rules. | Change file sharing or backup behaviour. |

> Vuoi cambiare una scritta nell'app? Quasi sempre la trovi in `values-it/strings.xml` (italiano) e in `values/strings.xml` (inglese). Per esempio, il bottone grande "🔥 SEGNALA INCENDIO" sta lì, in `quick_report_button`. NON cercare il testo dentro le schermate `.kt`: il testo è sempre nei file `strings.xml`, così resta ordinato e traducibile.

---

## 3. Build & run

Requirements: **JDK 17** (Temurin), **Android Studio Iguana (2023.2)** or newer, **Android SDK with API 34** installed. Gradle 8.7 is fetched automatically by the wrapper.

```bash
git clone https://github.com/markl02us/phoenix-citizen-android.git
cd phoenix-citizen-android

# Put your Maps key in a local.properties file (never committed):
echo "MAPS_API_KEY=AIza...your_real_key" > local.properties

# Debug build:
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Install on a connected phone (USB debugging on):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Unit tests / clean:
./gradlew testDebugUnitTest
./gradlew clean
```

There is also a GitHub Actions workflow (`.github/workflows/build.yml`): every push to `main`/`master` builds a debug APK and uploads it as a downloadable artifact named `phoenix-citizen-debug-apk` (Actions tab → latest run → Artifacts), so you can get an installable APK without a local Android Studio at all.

> Per provarla velocemente: la cosa più facile è non installare niente sul computer. Fai una piccola modifica, la carichi su GitHub, e nella scheda "Actions" del repository GitHub ti compila da solo un APK già pronto da scaricare e installare sul telefono. Se invece vuoi lavorarci sul serio, ti serve Android Studio con JDK 17 e l'SDK Android 34, e poi `./gradlew assembleDebug`.

---

## 4. Dependencies (what must be installed / configured)

**Toolchain (install once):**
- JDK 17 (Temurin recommended)
- Android Studio Iguana 2023.2 or newer
- Android SDK Platform 34 + build-tools
- Gradle 8.7 — auto-downloaded by the wrapper; no manual install needed

**Libraries (pulled automatically by Gradle on first build — no manual install):**
Jetpack Compose (BOM 2024.06.00, Material 3), AndroidX Core/Lifecycle/Activity, Navigation Compose, Room 2.6.1, WorkManager 2.9.1, Retrofit 2.11.0 + Kotlinx Serialization 1.6.3 + OkHttp 4.12.0, Coroutines 1.8.1, Play Services Location 21.3.0, Maps Compose 4.4.1, CameraX 1.3.4, Coil 2.6.0, Accompanist Permissions 0.34.0, Play Integrity 1.3.0, (Firebase Messaging — commented out until configured).

**Keys / external services (provide your own — all are PLACEHOLDERS in the repo, never real secrets):**

| What | Where it goes | Placeholder today | Notes |
|------|---------------|-------------------|-------|
| Google Maps Android SDK key | `local.properties` → `MAPS_API_KEY=…` (or CI secret) | `YOUR_MAPS_API_KEY_HERE` | Restrict to package `com.phoenix.citizen` + signing SHA-1. Needed for the Map screen. |
| Firebase `google-services.json` | `app/google-services.json` (git-ignored) | absent (FCM stubbed) | Needed only for push notifications. Then uncomment Firebase lines in both `build.gradle.kts` files and switch `PhoenixMessagingService` to extend `FirebaseMessagingService`. |
| Play Integrity cloud project number | `util/IntegrityProvider.kt` → `CLOUD_PROJECT_NUMBER` | `0L` (attestation disabled) | Needed only to enable anti-spam device attestation. |
| Release signing keystore | `release-keystore.properties` (git-ignored) | absent | Needed only to publish to Google Play. |

> Niente di tutto questo è dentro il codice come segreto vero: sono tutti segnaposto. Le librerie se le scarica Gradle da solo, non installi niente a mano. Le uniche cose che devi procurarti TU sono: una chiave Google Maps (se vuoi la mappa), il file Firebase (se vuoi le notifiche push), e il numero progetto Play Integrity (se vuoi l'antispam). L'app si compila e funziona anche senza, solo che mappa/notifiche/antispam restano spenti. Importante: NON mettere mai chiavi vere dentro file che finiscono su GitHub — vanno in `local.properties` o nei file `*.properties` che sono già esclusi.

---

## 5. PHOENIX integration — how a report reaches the backend

This is the part that explains exactly how the app interchanges with PHOENIX.

### 5.1 Endpoints (defined in `data/api/PhoenixApi.kt`, base URL `https://adr-wildfire.com/`)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `api/citizen_report` | POST | Submit a fire report |
| `api/citizen_report_status?report_id=X` | GET | Check corroboration status of a submitted report |
| `api/detections?period=24h&bbox=south,west,north,east` | GET | Active detections for the map |
| `api/source_health` | GET | Backend health (used in Settings diagnostic) |

### 5.2 The report submission flow (end to end)

1. **User taps** the big button on `QuickReportScreen` (or fills the detailed `ReportFormScreen`).
2. `QuickReportViewModel.submitFlameAtCurrentLocation()` asks `LocationProvider` for current GPS.
3. It calls `ReportRepository.submitOrQueue(...)`, which **writes the report to Room first** with status `QUEUED` (offline-first — nothing is lost even with no signal).
4. `ReportRepository.attemptSend()` then:
   - asks `IntegrityProvider` for an attestation token (may be `null` if not yet configured),
   - builds the `CitizenReportPost` JSON body,
   - calls `PhoenixApi.submitReport(token, body)` → **POST `https://adr-wildfire.com/api/citizen_report`** with the `X-Integrity-Token` header.
5. On HTTP success: the local row flips to `SYNCED` and stores the backend's `report_id`. On failure: it flips to `FAILED`, and `SyncWorker` retries it every 15 minutes until it goes through.
6. Later, `SyncWorker` → `refreshCorroboration()` calls **GET `/api/citizen_report_status`** to learn whether PHOENIX has corroborated the report (with satellite sources and witness count), which is what the History screen shows.

### 5.3 The exact POST payload (`CitizenReportPost` in `data/model/Models.kt`)

```json
{
  "device_hash": "stable-random-uuid-per-install",
  "lat": 37.5,
  "lon": 14.0,
  "ts_utc": "2026-06-08T12:34:56Z",
  "observation_type": "flame" | "smoke" | "unsure",
  "wind_direction_observed": "N|NE|E|SE|S|SW|W|NW",  // optional
  "photo_path": "content://...",                       // optional
  "note": "free text"                                  // optional
}
```

Header on every POST: `X-Integrity-Token: <Play Integrity token>` (device attestation; the backend should validate it before trusting a submission).

Backend success response (`CitizenReportResponse`): a numeric `report_id`, an optional `message`, an optional `validation_status`, and an optional `forecast_context`.

> Riassunto in parole semplici, come funziona lo scambio con PHOENIX: la persona preme il bottone, il telefono prende posizione e ora, salva tutto in locale per sicurezza, poi manda un piccolo pacchetto JSON al server `adr-wildfire.com` sull'indirizzo `api/citizen_report`. Dentro c'è: chi (un codice anonimo), dove (lat/lon), quando (ora UTC), cosa (fiamma, fumo o non sicuro) e, se vuole, vento, foto e una nota. Il server risponde con un numero di segnalazione. Da lì in poi l'app ogni tanto richiede `api/citizen_report_status` per vedere se PHOENIX ha confermato l'incendio incrociandolo con i satelliti e con altre segnalazioni. Quello che vedi nello "Storico" è esattamente questo stato. Se vuoi cambiare COSA viene mandato, tocchi `Models.kt`. Se vuoi cambiare DOVE viene mandato, tocchi `API_BASE_URL`. Se vuoi cambiare COME viene mandato (ritenta, coda, ecc.), tocchi `ReportRepository.kt`.

---

## 6. Benefits

### 6.1 For the user (the citizen / resident)

- **Informed:** through the Map screen and push notifications, residents see active and confirmed fires near them faster — they get better fire information sooner, on the device already in their pocket.
- **Being informed (giving information):** a single tap turns a person who smells smoke into a precise, time-stamped, geolocated signal — no forms, no account, no CAPTCHA. Their observation actually reaches the people who can act on it.

### 6.2 For ADRIZ (the project / association)

- **Informed:** every report is corroborated against satellite and network sources, so ADRIZ builds a continuously growing, ground-truthed record of real fires across Sicily — exactly the data that makes the detection models better.
- **Being informed (receiving information):** ADRIZ receives human eyes-on-the-ground signal that satellites alone cannot produce (early smoke, sub-pixel fires, fires under cloud), feeding the PHOENIX corroboration and reputation system directly.

### 6.3 For the government / authorities (e.g. VVF, regional civil protection)

- **Informed:** authorities get a live, map-based picture of where citizens are reporting fires, cross-checked with satellites — a clearer operational picture than any single source gives them.
- **Being informed (receiving information):** they get this data **faster and more accurately than they otherwise could** — a citizen tap arrives within seconds with exact coordinates, instead of relayed phone descriptions, and it is automatically corroborated, so the signal that reaches them is already filtered and located.

> In due parole: il cittadino sa prima e segnala in un tocco; ADRIZ raccoglie dati veri da terra che i satelliti da soli non vedono; le autorità ricevono l'informazione più in fretta e più precisa di quanto potrebbero altrimenti. Tutti e tre guadagnano sia perché sono informati, sia perché informano.

---

## 7. Things still to configure before a public release

These are not bugs — they are the keys and accounts that only the owner can create. The app builds and runs without them; each one just turns on one feature.

- Google Maps Android SDK key → `local.properties` (turns on the map).
- Firebase `google-services.json` + uncomment Firebase lines → push notifications.
- Play Integrity `CLOUD_PROJECT_NUMBER` in `IntegrityProvider.kt` → anti-spam attestation.
- Release signing keystore → Google Play upload.
- Publish `https://adr-wildfire.com/privacy` → required by the Play Store.

> Queste cose le completa Mark dal lato Google/Play. A te per modificare l'app non servono: puoi cambiare schermate, testi, campi e logica senza nessuna di queste chiavi. Servono solo quando si va a pubblicare sul Play Store o ad accendere mappa, notifiche e antispam.

---

*PHOENIX Cittadino — `com.phoenix.citizen` — maintainer guide. Sorgente completo nel repository. Per qualsiasi dubbio su dove mettere le mani, parti dalla tabella della sezione 2.*

— Mark
