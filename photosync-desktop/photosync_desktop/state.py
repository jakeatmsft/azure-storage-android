"""Portable SQLite state for a stable, independent PhotoSync device."""

from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .core import MediaFile


class StateStore:
    def __init__(self, database_path: Path):
        self.database_path = database_path
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(database_path)
        self._connection.execute("PRAGMA foreign_keys = ON")
        self._connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS completed_uploads (
                identity_key TEXT PRIMARY KEY,
                source_path TEXT NOT NULL,
                blob_name TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                modified_utc_ms INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                completed_utc TEXT NOT NULL
            );
            """
        )
        self._connection.commit()

    def close(self) -> None:
        self._connection.close()

    def __enter__(self) -> StateStore:  # noqa: PYI034 - Python 3.10 has no typing.Self.
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def device_id(self) -> str:
        row = self._connection.execute(
            "SELECT value FROM settings WHERE key = 'device_id'"
        ).fetchone()
        if row:
            return str(row[0])
        new_id = str(uuid.uuid4())
        self._connection.execute(
            "INSERT INTO settings(key, value) VALUES ('device_id', ?)",
            (new_id,),
        )
        self._connection.commit()
        return new_id

    def is_completed(self, identity_key: str) -> bool:
        row = self._connection.execute(
            "SELECT 1 FROM completed_uploads WHERE identity_key = ?",
            (identity_key,),
        ).fetchone()
        return row is not None

    def record_completed(
        self,
        media: MediaFile,
        identity_key: str,
        blob_name: str,
        sha256: str,
    ) -> None:
        completed_utc = datetime.now(timezone.utc).isoformat(timespec="seconds")
        self._connection.execute(
            """
            INSERT INTO completed_uploads(
                identity_key, source_path, blob_name, file_size,
                modified_utc_ms, sha256, completed_utc
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(identity_key) DO UPDATE SET
                source_path = excluded.source_path,
                blob_name = excluded.blob_name,
                file_size = excluded.file_size,
                modified_utc_ms = excluded.modified_utc_ms,
                sha256 = excluded.sha256,
                completed_utc = excluded.completed_utc
            """,
            (
                identity_key,
                media.relative_path,
                blob_name,
                media.size,
                media.modified_utc_ms,
                sha256,
                completed_utc,
            ),
        )
        self._connection.commit()

    def completed_count(self) -> int:
        row = self._connection.execute(
            "SELECT COUNT(*) FROM completed_uploads"
        ).fetchone()
        return int(row[0]) if row else 0
