from __future__ import annotations

import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import ClassVar
from unittest.mock import patch

from photosync_desktop.uploader import (
    SHA256_METADATA_KEY,
    AzureUploader,
    UploadConfiguration,
    redact_error,
)


class FakeResourceExistsError(Exception):
    pass


class FakeContentSettings:
    def __init__(self, content_type: str):
        self.content_type = content_type


class FakeBlob:
    def __init__(self, name: str):
        self.name = name
        self.data = b""
        self.metadata: dict[str, str] = {}
        self.content_type = ""

    def upload_blob(self, source: object, **kwargs: object) -> None:
        if self.data:
            raise FakeResourceExistsError("blob already exists")
        self.data = source.read()  # type: ignore[attr-defined]
        self.metadata = dict(kwargs["metadata"])  # type: ignore[arg-type]
        self.content_type = kwargs["content_settings"].content_type  # type: ignore[union-attr]
        progress = kwargs["progress_hook"]
        progress(len(self.data), len(self.data))  # type: ignore[operator]

    def get_blob_properties(self) -> SimpleNamespace:
        return SimpleNamespace(size=len(self.data), metadata=self.metadata)


class FakeContainer:
    def __init__(self):
        self.blobs: dict[str, FakeBlob] = {}

    def get_blob_client(self, name: str) -> FakeBlob:
        return self.blobs.setdefault(name, FakeBlob(name))


class FakeBlobServiceClient:
    instances: ClassVar[list[FakeBlobServiceClient]] = []
    next_container: ClassVar[FakeContainer | None] = None

    def __init__(self, account_url: str, credential: str):
        self.account_url = account_url
        self.credential = credential
        self.container = self.__class__.next_container or FakeContainer()
        self.__class__.next_container = None
        self.closed = False
        self.__class__.instances.append(self)

    def get_container_client(self, _name: str) -> FakeContainer:
        return self.container

    def close(self) -> None:
        self.closed = True


def fake_azure_modules() -> dict[str, types.ModuleType]:
    azure = types.ModuleType("azure")
    azure.__path__ = []  # type: ignore[attr-defined]
    core = types.ModuleType("azure.core")
    core.__path__ = []  # type: ignore[attr-defined]
    exceptions = types.ModuleType("azure.core.exceptions")
    exceptions.ResourceExistsError = FakeResourceExistsError  # type: ignore[attr-defined]
    storage = types.ModuleType("azure.storage")
    storage.__path__ = []  # type: ignore[attr-defined]
    blob = types.ModuleType("azure.storage.blob")
    blob.BlobServiceClient = FakeBlobServiceClient  # type: ignore[attr-defined]
    blob.ContentSettings = FakeContentSettings  # type: ignore[attr-defined]
    return {
        "azure": azure,
        "azure.core": core,
        "azure.core.exceptions": exceptions,
        "azure.storage": storage,
        "azure.storage.blob": blob,
    }


class UploaderTests(unittest.TestCase):
    def setUp(self) -> None:
        FakeBlobServiceClient.instances.clear()
        FakeBlobServiceClient.next_container = None

    def test_uploads_nested_media_and_records_completion_without_changing_source(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_root = root / "source"
            nested = source_root / "year" / "month"
            nested.mkdir(parents=True)
            source = nested / "clip.MP4"
            original = b"video-bytes"
            source.write_bytes(original)
            events = []
            configuration = UploadConfiguration(
                account_url="https://example.blob.core.windows.net",
                container="photo-sync",
                sas_token="sv=1&sp=rw&sig=secret",
                source_root=source_root,
                database_path=root / "data" / "photosync.sqlite3",
            )

            with patch.dict(sys.modules, fake_azure_modules()):
                summary = AzureUploader(
                    configuration,
                    events.append,
                    threading.Event(),
                ).run()

            self.assertEqual(summary.uploaded, 1)
            self.assertEqual(summary.failed, 0)
            self.assertEqual(source.read_bytes(), original)
            service = FakeBlobServiceClient.instances[0]
            self.assertTrue(service.closed)
            self.assertEqual(len(service.container.blobs), 1)
            blob_name, blob = next(iter(service.container.blobs.items()))
            self.assertEqual(len(blob_name.split("/")), 3)
            self.assertTrue(blob_name.endswith("/clip.MP4"))
            self.assertEqual(blob.data, original)
            self.assertEqual(blob.content_type, "video/mp4")
            self.assertIn(SHA256_METADATA_KEY, blob.metadata)
            self.assertTrue(any(event.kind == "done" for event in events))

    def test_recovers_verified_remote_blob_when_local_receipt_was_not_saved(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_root = root / "source"
            source_root.mkdir()
            (source_root / "photo.jpg").write_bytes(b"photo-bytes")
            database = root / "data" / "photosync.sqlite3"
            configuration = UploadConfiguration(
                account_url="https://example.blob.core.windows.net",
                container="photo-sync",
                sas_token="sv=1&sp=rw&sig=secret",
                source_root=source_root,
                database_path=database,
            )

            with patch.dict(sys.modules, fake_azure_modules()):
                first = AzureUploader(
                    configuration, lambda _event: None, threading.Event()
                ).run()
            self.assertEqual(first.uploaded, 1)

            # Simulate termination after Azure committed the blob but before the receipt survived.
            import sqlite3

            with sqlite3.connect(database) as connection:
                connection.execute("DELETE FROM completed_uploads")
            FakeBlobServiceClient.next_container = FakeBlobServiceClient.instances[
                0
            ].container

            events = []
            with patch.dict(sys.modules, fake_azure_modules()):
                second = AzureUploader(
                    configuration, events.append, threading.Event()
                ).run()

            self.assertEqual(second.uploaded, 0)
            self.assertEqual(second.recovered, 1)
            self.assertEqual(second.failed, 0)
            self.assertTrue(any(event.kind == "recovered" for event in events))

    def test_completed_local_identity_is_skipped_on_later_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_root = root / "source"
            source_root.mkdir()
            (source_root / "photo.jpg").write_bytes(b"photo-bytes")
            configuration = UploadConfiguration(
                account_url="https://example.blob.core.windows.net",
                container="photo-sync",
                sas_token="sv=1&sp=rw&sig=secret",
                source_root=source_root,
                database_path=root / "data" / "photosync.sqlite3",
            )

            with patch.dict(sys.modules, fake_azure_modules()):
                first = AzureUploader(
                    configuration, lambda _event: None, threading.Event()
                ).run()
                second = AzureUploader(
                    configuration, lambda _event: None, threading.Event()
                ).run()

            self.assertEqual(first.uploaded, 1)
            self.assertEqual(second.uploaded, 0)
            self.assertEqual(second.skipped, 1)
            self.assertEqual(second.failed, 0)
            self.assertEqual(
                len(FakeBlobServiceClient.instances[1].container.blobs),
                0,
            )

    def test_error_redaction_removes_sas_query(self) -> None:
        sas = "sv=1&sp=rw&sig=very-secret"
        error = RuntimeError(
            f"request failed: https://account.blob.core.windows.net/c/b?{sas}"
        )
        message = redact_error(error, sas)
        self.assertNotIn("very-secret", message)
        self.assertIn("<redacted>", message)


if __name__ == "__main__":
    unittest.main()
