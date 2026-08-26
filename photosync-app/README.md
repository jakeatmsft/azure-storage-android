# photosync-app

Phase 1/2 implementation of the Android Azure photo-sync application spec
(automated upload of files from selected Android folders to Azure Blob
Storage, plus automated download to a user-selected folder), scoped to
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
  (`FilenameConflictResolver`), projected free-space enforcement
  (`DownloadStoragePolicy`), and the API request/response DTOs
  (`api/ApiContracts.kt`) matching spec section 10.
- **`:app`** — the Android application module: Room database, WorkManager
  workers, Storage Access Framework upload/download folders, Retrofit API
  client, and a minimal UI (Dashboard,
  Settings, Transfer History) per spec section 14.

## Provision direct Azure access with a QR code

PhotoSync can operate without the optional backend API. A PC-side helper
puts the Blob service URL, upload/download container names, and SAS into a
PhotoSync-only QR payload. The Android app validates that payload and stores
the credential in Android Keystore-backed encrypted preferences. The SAS is
never displayed or logged by the app.

1. Create the shared container in Azure Storage. In that container's **Shared
   access tokens** screen, create a short-lived SAS with HTTPS only and Read,
   List, and Write permissions. Create is optional. This container-scoped SAS
   can be used by both devices without granting access to other containers.
2. On the PC, install the QR dependency:

   ```bash
   python -m pip install "qrcode[pil]"
   ```

3. Run the helper. It prompts for the SAS with hidden input, so the token does
   not need to appear in the command line or shell history:

   ```bash
   cd photosync-app
   python tools/generate_sas_qr.py \
     --account-url https://ACCOUNT.blob.core.windows.net \
     --upload-container photo-sync \
     --download-container photo-sync \
     --output photosync-sas.png
   ```

4. Open `photosync-sas.png` on the PC. In the Android app, open **Settings →
   Scan SAS provisioning QR** and scan the screen. Grant camera permission if
   prompted. The Settings screen shows only the account host, containers, and
   expiry—not the SAS.

Treat the PNG as a password: delete it after provisioning, use a short expiry,
and rescan a newly generated code to rotate the credential. Scanning or
clearing a credential discards unfinished queue entries and rediscovers them;
completed duplicate-tracking records are retained. Direct QR configuration
takes precedence over a configured backend API until **Clear stored SAS** is
selected.

Scan the same QR on both devices. Select **Upload only** and one or more upload
folders on the source device. Select **Download only** and a destination folder
on the receiving device. The upload and download container arguments should be
the same for this two-device flow; separate container names are supported only
for deployments whose SAS covers both containers.

## Architecture summary

The app supports two storage authorization modes:

- **Direct SAS mode** (the simplest setup): the scanned encrypted SAS is used
  to list the download container and transfer blobs directly. Upload blob
  names are deterministic, making an interrupted upload safe to retry.
- **Backend API mode**: `PhotoSyncApiService` obtains short-lived, single-blob
  SAS URLs from a separate Azure API. Table Storage schemas and device
  authentication remain that API's responsibility.

Neither mode stores an Azure Storage account key on the phone. Blob data is
transferred with `CloudBlockBlob` and
`StorageCredentialsSharedAccessSignature`.

Key `:app` packages:

- `data/local` — Room entities/DAOs (`LocalFile`, `LocalTransfer`,
  `PendingStateUpdate`, `AppSetting`) and `SettingsRepository`.
- `data/remote` — direct `AzureSasBlobClient`, plus
  `PhotoSyncApiService`/`ApiClientFactory` for optional backend mode (the
  logging interceptor never logs tokens or SAS query strings).
- `upload` — persisted SAF-folder scanning, `DiscoveryRepository`,
  `UploadRepository`, `PhotoDiscoveryWorker`, `UploadWorker`.
- `download` — `DownloadRepository`, `DownloadManifestWorker`,
  `DownloadWorker` (oldest-first persistent queue, free-space guard, temp
  file → verify size/checksum → rename → complete).
- `reconciliation` — `ReconciliationWorker` (repairs interrupted
  transfers), `StateUpdateWorker` (retries only failed completion calls,
  never re-transfers content, per spec 11.3), `CleanupWorker` (removes
  abandoned temp files and old completed-transfer history).
- `security` — `CredentialStore` (Android Keystore-backed
  `EncryptedSharedPreferences` for the scanned Azure SAS configuration and
  optional backend API token).
- `ui` — `DashboardActivity`, `SettingsActivity`, `TransferHistoryActivity`.
- `WorkScheduler` — configures WorkManager constraints per spec section 6
  (uploads: configurable Wi-Fi/charging; downloads: configurable Wi-Fi) and
  reschedules on every relevant setting change.

## Download storage and resume behavior

The Settings screen exposes upload-only, download-only, and combined modes.
On the upload device, **Add upload folder** can be used repeatedly; only those
explicitly selected folder trees are scanned, recursively, and Android keeps
the read grants across restarts. On the download device, **Choose destination
folder** selects where downloaded files are written.

The minimum-free-storage percentage is configurable from 1–99% (default 10%).
Before every download, the app checks the physical volume containing the
selected SAF folder and does not start the next file if that file would take
free space below the configured percentage.

Queued downloads remain in Room in stable manifest order. When storage is
freed, WorkManager retries the same oldest unfinished item and then continues
forward. Completed upload and download identities are retained rather than
aged out, so rescanning an upload folder or deleting local downloads does not
cause unchanged content to be transferred again.

## Build and test

This independent build requires JDK 17, Gradle 8.7, Android SDK Platform 34,
and access to Google Maven. It does not currently contain its own Gradle
wrapper, so invoke an installed Gradle 8.7:

```bash
cd photosync-app
gradle build          # builds :core and :app, runs unit tests
gradle :core:test     # just the pure-Kotlin business logic tests
```

To validate the app compilation without running the full build:

```bash
gradle :app:compileDebugKotlin
```

### Note on the legacy SDK dependency

`app/build.gradle.kts` consumes the published
`com.microsoft.azure.android:azure-storage-android:2.0.0` AAR. This keeps the
app independent from the repository's legacy Gradle 3.3 SDK build.

## What is intentionally not included (out of scope for this module)

Per the phased delivery plan (spec section 17), this module targets
**Phase 1/2** (device registration, automated selected-folder upload,
Room-backed duplicate prevention, WorkManager scheduling) plus a first pass
at **Phase 3** (SAF-based download, manifest polling, integrity
verification) and the reconciliation/state-update pieces of **Phase 4**
needed for correctness. The following remain explicitly out of scope for
this repository and require separate infrastructure/decisions:

- The optional Azure-hosted API itself (Azure Functions/App Service/Container
  Apps implementing `PhotoSyncApiService`'s server side, managed identity,
  Table Storage schemas, idempotency/optimistic concurrency enforcement).
- The actual device/user authentication flow (Entra ID sign-in, admin
  portal device activation, or bootstrap-token exchange) — `AppContainer`
  and `CredentialStore` are written generically against any bearer token
  so any of these can be plugged in later.
- Monitoring/alerting and administrative device disablement (these require an
  API/backend; direct SAS mode supports manual rotation by rescanning).
- Optional video upload support (disabled by default; the folder scanner
  already supports it behind
  `SettingsRepository.isUploadIncludeVideos()`).
