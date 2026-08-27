# PhotoSync Desktop Uploader

This is a separate, portable Windows application that recursively finds photos
and videos beneath a folder you choose and uploads them to the existing Azure
Blob container. The container defaults to `photo-sync`.

It behaves as a new PhotoSync device. On first launch it generates a UUID and
stores the ID plus completed-upload history in `data/photosync.sqlite3`. Its
blob names match the Android app's direct-SAS layout:

```text
<desktop-device-id>/<transfer-identity>/<original-filename>
```

The application does not share or alter the Android app's identity or state.

On an Android device in **Download only** mode, deleting a downloaded local
copy does not cause the unchanged Azure blob to download again. The Android
app retains its completed-download receipt independently of that local file.
It will treat a changed blob version as new content, and clearing/uninstalling
the Android app removes those local receipts.

## Local-safety behavior

- Source folders and files are opened read-only. Nothing is renamed, moved,
  edited, or deleted.
- Directory symlinks and Windows junctions are not followed, so a scan stays
  within the selected tree and cannot loop through linked folders.
- There is no background service, scheduled task, registry entry, or startup
  entry. Uploading happens only while this application is open and after you
  click **Upload** and confirm.
- The SAS token is held in memory for the upload and cleared from the UI when
  the upload starts. It is never written to the database or a configuration
  file.
- The only local runtime data is the `data` folder beside this README. Keep it
  to retain the same desktop device identity and duplicate history.

## Setup on Windows

Python 3.10 or newer is required. From a Command Prompt in this directory:

```bat
py -3 -m venv .venv
.venv\Scripts\activate
python -m pip install -r requirements.txt
run.bat
```

The repository does not install these packages or run the app automatically.

In the app:

1. Enter the same Blob service URL used by the Android app, for example
   `https://ACCOUNT.blob.core.windows.net`.
2. Leave the container as `photo-sync`.
3. Paste a container- or account-level SAS that allows HTTPS plus Read and
   Write. Read is used only to verify the uploaded blob. Use a short expiry and
   grant only the access needed. An Azure account key is not accepted or
   stored.
4. Choose the top-level folder containing the media. The app scans every
   ordinary subfolder and includes both photos and videos.
5. Use **Scan only** to review the number and total size without contacting
   Azure, then use **Upload** when ready.

You may instead provide `AZURE_STORAGE_ACCOUNT_URL` and
`AZURE_STORAGE_SAS_TOKEN` in the Command Prompt session before launching. The
values populate the UI but are still not persisted by this application.

## Duplicate and retry behavior

The transfer identity includes this app's device UUID, the path relative to
the selected root, file size, and modified time. Successfully uploaded files
are skipped on later runs. Blob size and SHA-256 metadata are checked before a
transfer is recorded complete. If Azure received a blob but the app stopped
before saving local state, the deterministic blob name lets the next run
verify and recover that completion without overwriting it.

Recovered remote completions are shown separately from newly uploaded files in
the final summary.

If a source file changes, its next scan produces a new transfer identity. A
failure on one file is logged and does not stop the rest of the directory
tree. **Cancel** stops between files; an in-progress Azure request may finish
first.

## Supported formats

Common JPEG, PNG, GIF, BMP, TIFF, WebP, HEIC/HEIF, AVIF, camera RAW formats,
and MP4, MOV, M4V, AVI, MKV, WebM, 3GP, MTS/M2TS, MPEG, WMV, and related video
formats are recognized by extension. Matching is case-insensitive.

## Tests

The core tests do not contact Azure or inspect any real photo directory:

```bat
python -m unittest discover -s tests -v
```
