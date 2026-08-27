"""Filesystem discovery and transfer identity logic with no Azure dependency."""

from __future__ import annotations

import hashlib
import mimetypes
import os
import re
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

PHOTO_EXTENSIONS = frozenset(
    {
        ".avif",
        ".bmp",
        ".cr2",
        ".cr3",
        ".dng",
        ".gif",
        ".heic",
        ".heif",
        ".jfif",
        ".jpe",
        ".jpeg",
        ".jpg",
        ".jxl",
        ".nef",
        ".nrw",
        ".orf",
        ".pef",
        ".png",
        ".raf",
        ".raw",
        ".rw2",
        ".srw",
        ".tif",
        ".tiff",
        ".webp",
    }
)

VIDEO_EXTENSIONS = frozenset(
    {
        ".3g2",
        ".3gp",
        ".avi",
        ".flv",
        ".hevc",
        ".m2ts",
        ".m4v",
        ".mkv",
        ".mov",
        ".mp4",
        ".mpeg",
        ".mpg",
        ".mts",
        ".ogv",
        ".ts",
        ".webm",
        ".wmv",
    }
)

SUPPORTED_EXTENSIONS = PHOTO_EXTENSIONS | VIDEO_EXTENSIONS
_CONTAINER_PATTERN = re.compile(r"^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$")

_MIME_OVERRIDES = {
    ".avif": "image/avif",
    ".cr2": "image/x-canon-cr2",
    ".cr3": "image/x-canon-cr3",
    ".dng": "image/x-adobe-dng",
    ".heic": "image/heic",
    ".heif": "image/heif",
    ".jxl": "image/jxl",
    ".m2ts": "video/mp2t",
    ".mkv": "video/x-matroska",
    ".mts": "video/mp2t",
    ".nef": "image/x-nikon-nef",
}


@dataclass(frozen=True)
class MediaFile:
    path: Path
    relative_path: str
    size: int
    modified_utc_ms: int
    content_type: str

    def identity_key(self, device_id: str) -> str:
        return upload_identity(
            device_id,
            self.relative_path,
            self.size,
            self.modified_utc_ms,
        )

    def blob_name(self, device_id: str) -> str:
        safe_name = self.path.name.replace("/", "_").replace("\\", "_")[-240:]
        return f"{device_id}/{self.identity_key(device_id)}/{safe_name or 'unnamed'}"


def upload_identity(
    device_id: str,
    media_id: str,
    file_size: int,
    modified_utc_ms: int,
) -> str:
    """Match the Android PhotoSync upload identity calculation."""

    raw = f"{device_id}|{media_id}|{file_size}|{modified_utc_ms}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def content_type_for(path: Path) -> str:
    suffix = path.suffix.casefold()
    if suffix in _MIME_OVERRIDES:
        return _MIME_OVERRIDES[suffix]
    guessed, _ = mimetypes.guess_type(path.name)
    if guessed:
        return guessed
    return "image/*" if suffix in PHOTO_EXTENSIONS else "video/*"


def is_media_file(path: Path) -> bool:
    return path.suffix.casefold() in SUPPORTED_EXTENSIONS


def _is_reparse_point(path: Path) -> bool:
    """Do not leave the selected tree through symlinks or Windows junctions."""

    try:
        stat_result = path.lstat()
    except OSError:
        return True
    attributes = getattr(stat_result, "st_file_attributes", 0)
    reparse_flag = getattr(stat_result, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return path.is_symlink() or bool(attributes & reparse_flag)


def scan_media(
    source_root: Path,
    on_error: Callable[[str], None] | None = None,
) -> Iterator[MediaFile]:
    """Recursively yield supported media beneath *source_root*.

    Directory links/junctions are deliberately not followed, keeping discovery
    within the directory the user selected and preventing traversal loops.
    """

    root = source_root.expanduser().resolve(strict=True)
    if not root.is_dir():
        raise ValueError(f"Source is not a directory: {root}")

    def report_walk_error(error: OSError) -> None:
        if on_error:
            on_error(
                f"Could not read {error.filename or 'a directory'}: {error.strerror or error}"
            )

    for directory, dir_names, file_names in os.walk(
        root,
        topdown=True,
        onerror=report_walk_error,
        followlinks=False,
    ):
        directory_path = Path(directory)
        dir_names[:] = sorted(
            (
                name
                for name in dir_names
                if not _is_reparse_point(directory_path / name)
            ),
            key=str.casefold,
        )

        for file_name in sorted(file_names, key=str.casefold):
            path = directory_path / file_name
            if not is_media_file(path) or _is_reparse_point(path):
                continue
            try:
                stat_result = path.stat()
                if not path.is_file():
                    continue
                relative_path = path.relative_to(root).as_posix()
                yield MediaFile(
                    path=path,
                    relative_path=relative_path,
                    size=stat_result.st_size,
                    modified_utc_ms=stat_result.st_mtime_ns // 1_000_000,
                    content_type=content_type_for(path),
                )
            except OSError as error:
                if on_error:
                    on_error(f"Could not inspect {path}: {error}")


def validate_azure_settings(
    account_url: str, container: str, sas_token: str
) -> tuple[str, str, str]:
    account_url = account_url.strip().rstrip("/")
    container = container.strip()
    sas_token = sas_token.strip().removeprefix("?")

    parsed = urlsplit(account_url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("Account URL must be an HTTPS Azure Blob service URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError(
            "Account URL must not contain credentials, a query, or a fragment"
        )
    if parsed.path not in ("", "/"):
        raise ValueError("Account URL must not include a container or blob path")
    if not _CONTAINER_PATTERN.fullmatch(container):
        raise ValueError("Container must be a valid lowercase Azure container name")
    if not sas_token:
        raise ValueError("A container or account SAS token is required")

    fields = parse_qs(sas_token, keep_blank_values=True)
    permissions = fields.get("sp", [""])[0]
    if "r" not in permissions:
        raise ValueError(
            "The SAS token must include Read (r) permission for verification"
        )
    if "w" not in permissions:
        raise ValueError("The SAS token must include Write (w) permission")
    protocols = fields.get("spr", [""])[0]
    if protocols and "https" not in protocols.split(","):
        raise ValueError("The SAS token must allow HTTPS")

    return account_url, container, sas_token


def human_size(byte_count: int) -> str:
    size = float(byte_count)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024.0 or unit == "TiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{byte_count} B"
