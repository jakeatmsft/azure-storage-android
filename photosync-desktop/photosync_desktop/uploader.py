"""Azure Blob upload orchestration."""

from __future__ import annotations

import base64
import hashlib
import re
import threading
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .core import MediaFile, scan_media
from .state import StateStore


@dataclass(frozen=True)
class UploadConfiguration:
    account_url: str
    container: str
    sas_token: str
    source_root: Path
    database_path: Path


@dataclass(frozen=True)
class UploadEvent:
    kind: str
    message: str
    current: int = 0
    total: int = 0
    bytes_current: int = 0
    bytes_total: int = 0


@dataclass
class UploadSummary:
    discovered: int = 0
    uploaded: int = 0
    recovered: int = 0
    skipped: int = 0
    failed: int = 0
    cancelled: bool = False


EventSink = Callable[[UploadEvent], None]
SHA256_METADATA_KEY = "photosync_sha256"


def sha256_open_file(handle: object) -> str:
    digest = hashlib.sha256()
    while True:
        chunk = handle.read(1024 * 1024)  # type: ignore[attr-defined]
        if not chunk:
            break
        digest.update(chunk)
    handle.seek(0)  # type: ignore[attr-defined]
    return digest.hexdigest()


def redact_error(error: BaseException, sas_token: str) -> str:
    message = str(error) or error.__class__.__name__
    if sas_token:
        message = message.replace(sas_token, "<redacted>")
        message = message.replace("?" + sas_token, "?<redacted>")
    message = re.sub(r"(https://[^\s?]+)\?[^\s]+", r"\1?<redacted>", message)
    return message


class AzureUploader:
    def __init__(
        self,
        configuration: UploadConfiguration,
        emit: EventSink,
        cancel_event: threading.Event,
    ) -> None:
        self.configuration = configuration
        self.emit = emit
        self.cancel_event = cancel_event

    def run(self) -> UploadSummary:
        summary = UploadSummary()
        scan_errors: list[str] = []
        media_files: list[MediaFile] = []
        for media in scan_media(self.configuration.source_root, scan_errors.append):
            if self.cancel_event.is_set():
                break
            media_files.append(media)
        summary.discovered = len(media_files)
        self.emit(
            UploadEvent(
                "scan_complete",
                f"Found {len(media_files)} supported photo/video file(s).",
                total=len(media_files),
            )
        )
        for message in scan_errors:
            self.emit(UploadEvent("warning", message, total=len(media_files)))

        if not media_files or self.cancel_event.is_set():
            summary.cancelled = self.cancel_event.is_set()
            self.emit(
                UploadEvent(
                    "done", self._summary_message(summary), total=len(media_files)
                )
            )
            return summary

        try:
            from azure.core.exceptions import ResourceExistsError
            from azure.storage.blob import BlobServiceClient, ContentSettings
        except ImportError:
            summary.failed = len(media_files)
            self.emit(
                UploadEvent(
                    "fatal",
                    "Azure dependency is missing. Run: python -m pip install -r requirements.txt",
                    total=len(media_files),
                )
            )
            return summary

        service = BlobServiceClient(
            account_url=self.configuration.account_url,
            credential=self.configuration.sas_token,
        )
        container_client = service.get_container_client(self.configuration.container)

        try:
            with StateStore(self.configuration.database_path) as state:
                device_id = state.device_id()
                for index, media in enumerate(media_files, start=1):
                    if self.cancel_event.is_set():
                        summary.cancelled = True
                        break

                    identity_key = media.identity_key(device_id)
                    blob_name = media.blob_name(device_id)
                    if state.is_completed(identity_key):
                        summary.skipped += 1
                        self.emit(
                            UploadEvent(
                                "skipped",
                                f"Already uploaded: {media.relative_path}",
                                index,
                                len(media_files),
                            )
                        )
                        continue

                    self.emit(
                        UploadEvent(
                            "file_start",
                            f"Uploading: {media.relative_path}",
                            index,
                            len(media_files),
                            bytes_total=media.size,
                        )
                    )
                    try:
                        with media.path.open("rb") as source:
                            before = media.path.stat()
                            if (
                                before.st_size != media.size
                                or before.st_mtime_ns // 1_000_000
                                != media.modified_utc_ms
                            ):
                                raise OSError(
                                    "File changed after discovery; it will be retried next run"
                                )

                            checksum = sha256_open_file(source)
                            if self.cancel_event.is_set():
                                summary.cancelled = True
                                break

                            relative_path_b64 = base64.urlsafe_b64encode(
                                media.relative_path.encode("utf-8")
                            ).decode("ascii")
                            blob = container_client.get_blob_client(blob_name)

                            def progress(
                                current: int,
                                total: int | None,
                                relative_path: str = media.relative_path,
                                file_index: int = index,
                                file_size: int = media.size,
                            ) -> None:
                                self.emit(
                                    UploadEvent(
                                        "progress",
                                        relative_path,
                                        file_index,
                                        len(media_files),
                                        current,
                                        total or file_size,
                                    )
                                )

                            recovered = False
                            try:
                                blob.upload_blob(
                                    source,
                                    length=media.size,
                                    overwrite=False,
                                    content_settings=ContentSettings(
                                        content_type=media.content_type
                                    ),
                                    metadata={
                                        SHA256_METADATA_KEY: checksum,
                                        "photosync_device_id": device_id,
                                        "photosync_relative_path_b64": relative_path_b64,
                                    },
                                    max_concurrency=2,
                                    progress_hook=progress,
                                )
                            except ResourceExistsError:
                                # A prior run may have uploaded the blob before local state was saved.
                                recovered = True

                            after = media.path.stat()
                            if (
                                after.st_size != media.size
                                or after.st_mtime_ns // 1_000_000
                                != media.modified_utc_ms
                            ):
                                raise OSError(
                                    "File changed during upload; it was not marked complete"
                                )

                            properties = blob.get_blob_properties()
                            remote_size = int(properties.size)
                            remote_checksum = (properties.metadata or {}).get(
                                SHA256_METADATA_KEY
                            )
                            if remote_size != media.size:
                                raise OSError(
                                    f"Remote size mismatch (expected {media.size}, got {remote_size})"
                                )
                            if not remote_checksum:
                                raise OSError(
                                    "Remote blob is missing PhotoSync checksum metadata"
                                )
                            if remote_checksum.casefold() != checksum:
                                raise OSError(
                                    "Remote checksum metadata does not match the source"
                                )

                        state.record_completed(media, identity_key, blob_name, checksum)
                        if recovered:
                            summary.recovered += 1
                            event_kind = "recovered"
                            event_message = (
                                f"Recovered prior completion: {media.relative_path}"
                            )
                        else:
                            summary.uploaded += 1
                            event_kind = "uploaded"
                            event_message = f"Complete: {media.relative_path}"
                        self.emit(
                            UploadEvent(
                                event_kind,
                                event_message,
                                index,
                                len(media_files),
                                media.size,
                                media.size,
                            )
                        )
                    except Exception as error:  # noqa: BLE001 - isolate each media failure.
                        summary.failed += 1
                        self.emit(
                            UploadEvent(
                                "error",
                                f"Failed: {media.relative_path} — "
                                f"{redact_error(error, self.configuration.sas_token)}",
                                index,
                                len(media_files),
                            )
                        )
        finally:
            service.close()

        self.emit(
            UploadEvent(
                "done",
                self._summary_message(summary),
                summary.discovered,
                summary.discovered,
            )
        )
        return summary

    @staticmethod
    def _summary_message(summary: UploadSummary) -> str:
        prefix = "Cancelled. " if summary.cancelled else "Finished. "
        return (
            f"{prefix}{summary.uploaded} uploaded, {summary.recovered} recovered, "
            f"{summary.skipped} already complete, {summary.failed} failed, "
            f"{summary.discovered} discovered."
        )
