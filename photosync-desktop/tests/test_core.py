from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from photosync_desktop.core import (
    content_type_for,
    scan_media,
    upload_identity,
    validate_azure_settings,
)


class CoreTests(unittest.TestCase):
    def test_upload_identity_matches_android_delimited_sha256(self) -> None:
        self.assertEqual(
            upload_identity("device", "nested/photo.jpg", 123, 456),
            "425e68bc1fd1bce7e5b4ef06473e0795e6d8e433dae3a11f68c464723aaf54a3",
        )

    def test_scan_media_recurses_and_filters_extensions_case_insensitively(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            nested = root / "year" / "month"
            nested.mkdir(parents=True)
            (root / "cover.JPG").write_bytes(b"photo")
            (nested / "clip.MP4").write_bytes(b"video")
            (nested / "notes.txt").write_text("not media", encoding="utf-8")

            found = list(scan_media(root))

            self.assertEqual(
                {item.relative_path for item in found},
                {"cover.JPG", "year/month/clip.MP4"},
            )

    def test_scan_does_not_follow_directory_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "source"
            outside = base / "outside"
            root.mkdir()
            outside.mkdir()
            (outside / "outside.jpg").write_bytes(b"outside")
            try:
                (root / "linked").symlink_to(outside, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"Directory links are unavailable: {error}")

            self.assertEqual(list(scan_media(root)), [])

    def test_blob_name_is_android_compatible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            photo = root / "nested" / "photo.jpg"
            photo.parent.mkdir()
            photo.write_bytes(b"photo")
            media = next(scan_media(root))

            blob_name = media.blob_name("device-id")

            self.assertTrue(blob_name.startswith("device-id/"))
            self.assertTrue(blob_name.endswith("/photo.jpg"))
            self.assertEqual(len(blob_name.split("/")), 3)

    def test_content_type_overrides_heic(self) -> None:
        self.assertEqual(content_type_for(Path("photo.HEIC")), "image/heic")

    def test_validate_azure_settings(self) -> None:
        result = validate_azure_settings(
            " https://example.blob.core.windows.net/ ",
            "photo-sync",
            "?sv=1&sp=rwl&spr=https&sig=secret",
        )
        self.assertEqual(result[0], "https://example.blob.core.windows.net")
        self.assertEqual(result[1], "photo-sync")
        self.assertFalse(result[2].startswith("?"))

    def test_validate_azure_settings_requires_write(self) -> None:
        with self.assertRaisesRegex(ValueError, "Write"):
            validate_azure_settings(
                "https://example.blob.core.windows.net",
                "photo-sync",
                "sv=1&sp=rl&spr=https&sig=secret",
            )

    def test_validate_azure_settings_requires_read_for_verification(self) -> None:
        with self.assertRaisesRegex(ValueError, "Read"):
            validate_azure_settings(
                "https://example.blob.core.windows.net",
                "photo-sync",
                "sv=1&sp=w&spr=https&sig=secret",
            )


if __name__ == "__main__":
    unittest.main()
