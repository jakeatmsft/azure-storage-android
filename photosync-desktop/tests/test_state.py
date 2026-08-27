from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from photosync_desktop.core import scan_media
from photosync_desktop.state import StateStore


class StateStoreTests(unittest.TestCase):
    def test_device_identity_is_stable_and_completion_is_persisted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "state" / "photosync.sqlite3"
            source = root / "source"
            source.mkdir()
            (source / "photo.jpg").write_bytes(b"photo")
            media = next(scan_media(source))

            with StateStore(database) as state:
                device_id = state.device_id()
                identity = media.identity_key(device_id)
                self.assertFalse(state.is_completed(identity))
                state.record_completed(
                    media, identity, media.blob_name(device_id), "abc"
                )

            with StateStore(database) as reopened:
                self.assertEqual(reopened.device_id(), device_id)
                self.assertTrue(reopened.is_completed(identity))
                self.assertEqual(reopened.completed_count(), 1)


if __name__ == "__main__":
    unittest.main()
