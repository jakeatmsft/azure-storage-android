# photosync-app

Phase 1/2 implementation of the Android Azure photo-sync application spec
(automated upload of device photos to Azure Blob Storage, plus
charging-gated automated download to a user-selected folder), scoped to
what can be delivered and validated inside this repository.

This is an **independent Gradle build**, separate from the root
`azure-storage-android` build (which still uses a legacy AGP/Gradle
toolchain for the `microsoft-azure-storage` SDK module). It is not wired
into the root `settings.gradle`/`build.gradle` and does not change the
existing SDK build in any way.

## Modules

- **`:core`** — plain Kotlin/JVM module (no Android dependency) containing
  the business logic that is fully unit-testable in any environment:
  transfer identity/duplicate-prevention (`TransferIdentity`), the transfer
  state machine (`TransferState`, `TransferStateMachine`), retry/backoff
  policy (`RetryPolicy`), filename-conflict resolution
  (`FilenameConflictResolver`), and the API request/response DTOs
  (`api/ApiContracts.kt`) matching spec section 10.
- **`:app`** — the Android application module: Room database, WorkManager
  workers, MediaStore-based photo discovery, Storage Access Framework
  download destination, Retrofit API client, and a minimal UI (Dashboard,
  Settings, Transfer History) per spec section 14.

## Architecture summary

The app never talks to Azure Storage account keys or Table Storage
directly. It only calls a `PhotoSyncApiService` (Retrofit interface,
section 10) hosted by a separate Azure API (Functions/App Service/Container
Apps — **out of scope for this module**, see "What is intentionally not
included" below). The API authenticates the device/user, and returns
short-lived, single-blob SAS URLs that the app uses with the existing
`microsoft-azure-storage` SDK (`CloudBlockBlob` +
`StorageCredentialsSharedAccessSignature`) to transfer blob content
directly. Table Storage schemas (`FileCatalog`, `TransferState`,
`DeviceRegistry`, section 8) are the API's responsibility, not the app's.

Key `:app` packages:

- `data/local` — Room entities/DAOs (`LocalFile`, `LocalTransfer`,
  `PendingStateUpdate`, `AppSetting`) and `SettingsRepository`.
- `data/remote` — `PhotoSyncApiService` (Retrofit) and `ApiClientFactory`
  (OkHttp client with an auth interceptor and a header-redacting logging
  interceptor; never logs tokens or SAS query strings, per spec 5.2).
- `upload` — `MediaStoreScanner`, `DiscoveryRepository`,
  `UploadRepository`, `PhotoDiscoveryWorker`, `UploadWorker`.
- `download` — `DownloadRepository`, `DownloadManifestWorker`,
  `DownloadWorker` (temp file → verify size/checksum → rename → complete).
- `reconciliation` — `ReconciliationWorker` (repairs interrupted
  transfers), `StateUpdateWorker` (retries only failed completion calls,
  never re-transfers content, per spec 11.3), `CleanupWorker` (removes
  abandoned temp files and old completed-transfer history).
- `security` — `CredentialStore` (Android Keystore-backed
  `EncryptedSharedPreferences` for the device's API token; spec 5.2).
- `ui` — `DashboardActivity`, `SettingsActivity`, `TransferHistoryActivity`.
- `WorkScheduler` — configures WorkManager constraints per spec section 6
  (uploads: configurable Wi-Fi/charging; downloads: always require
  charging) and reschedules on every relevant setting change.

## Known limitation: `:app` cannot be built in this sandbox

The Android Gradle Plugin and all AndroidX artifacts (Room, WorkManager,
core-ktx, etc.) are served from Google's Maven repository
(`dl.google.com` / `maven.google.com`). **That host is unreachable from
this development sandbox** (network egress restriction), so
`:app:compileDebugKotlin` and any other `:app` task fail immediately at
plugin/dependency resolution — this is a sandbox network limitation, not a
code defect. `:core` has no such dependency and builds/tests normally
everywhere.

To build and test everything in a normal environment with full internet
access:

```bash
cd photosync-app
./gradlew build          # builds :core and :app, runs unit tests
./gradlew :core:test     # just the pure-Kotlin business logic tests
```

In this sandbox, only the following is validated:

```bash
cd photosync-app
gradle :core:test --configure-on-demand   # 21 passing tests
```

The `--configure-on-demand` flag is required so Gradle does not attempt to
configure the unbuildable `:app` module when only `:core` is requested.

### Note on the legacy SDK dependency

`app/build.gradle.kts` currently references the existing SDK via a
placeholder file-based dependency on
`microsoft-azure-storage/build/libs/microsoft-azure-storage.jar`. In a real
build/deployment pipeline this should be replaced with the published AAR
(`com.microsoft.azure.android:azure-storage-android`) or a proper
cross-build module dependency, since `:app` and the root SDK build are
independent Gradle builds.

## What is intentionally not included (out of scope for this module)

Per the phased delivery plan (spec section 17), this module targets
**Phase 1/2** (device registration, automated MediaStore-based upload,
Room-backed duplicate prevention, WorkManager scheduling) plus a first pass
at **Phase 3** (SAF-based download, manifest polling, integrity
verification) and the reconciliation/state-update pieces of **Phase 4**
needed for correctness. The following remain explicitly out of scope for
this repository and require separate infrastructure/decisions:

- The Azure-hosted API itself (Azure Functions/App Service/Container Apps
  implementing `PhotoSyncApiService`'s server side, managed identity,
  Table Storage schemas, idempotency/optimistic concurrency enforcement).
- The actual device/user authentication flow (Entra ID sign-in, admin
  portal device activation, or bootstrap-token exchange) — `AppContainer`
  and `CredentialStore` are written generically against any bearer token
  so any of these can be plugged in later.
- Credential rotation, monitoring/alerting, and administrative device
  disablement (these live in the API/backend).
- Optional video upload support (spec 3.2: disabled by default,
  `MediaStoreScanner` already supports it behind
  `SettingsRepository.isUploadIncludeVideos()`).
