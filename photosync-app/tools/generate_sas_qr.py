#!/usr/bin/env python3
"""Generate a PhotoSync provisioning QR without printing the SAS token."""

import argparse
import getpass
import os
import re
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a QR code that provisions PhotoSync with an Azure Blob SAS."
    )
    parser.add_argument(
        "--account-url",
        required=True,
        help="Blob service URL, for example https://ACCOUNT.blob.core.windows.net",
    )
    parser.add_argument("--upload-container", required=True)
    parser.add_argument("--download-container", required=True)
    parser.add_argument(
        "--sas-env",
        default="AZURE_STORAGE_SAS_TOKEN",
        help="Environment variable containing the SAS (default: AZURE_STORAGE_SAS_TOKEN)",
    )
    parser.add_argument("--output", type=Path, default=Path("photosync-sas.png"))
    return parser.parse_args()


def valid_container(value: str) -> bool:
    return (
        3 <= len(value) <= 63
        and value[0].isalnum()
        and value[-1].isalnum()
        and value == value.lower()
        and re.fullmatch(r"[a-z0-9-]+", value) is not None
        and "--" not in value
    )


def main() -> int:
    args = parse_args()
    account_url = args.account_url.rstrip("/")
    parsed_url = urlparse(account_url)
    if (
        parsed_url.scheme != "https"
        or not parsed_url.hostname
        or parsed_url.path not in ("", "/")
        or parsed_url.username is not None
        or parsed_url.password is not None
        or parsed_url.params
        or parsed_url.query
        or parsed_url.fragment
    ):
        raise SystemExit("--account-url must be an HTTPS blob service URL without a path")

    for label, container in (
        ("upload", args.upload_container),
        ("download", args.download_container),
    ):
        if not valid_container(container):
            raise SystemExit(f"Invalid Azure Blob {label} container name")

    sas_token = os.environ.get(args.sas_env)
    if not sas_token:
        sas_token = getpass.getpass("Paste the Azure Blob SAS token (input is hidden): ")
    sas_token = sas_token.strip().removeprefix("?")

    sas_fields = parse_qs(sas_token, keep_blank_values=True)
    for required in ("sig", "se", "sp"):
        if not sas_fields.get(required, [""])[0]:
            raise SystemExit(f"SAS token is missing {required}")
    permissions = sas_fields["sp"][0]
    if not {"r", "l"}.issubset(permissions):
        raise SystemExit("SAS token must include read and list permissions")
    if "w" not in permissions:
        raise SystemExit("SAS token must include write permission for resumable uploads")

    payload = "photosync://azure-sas/v1?" + urlencode(
        {
            "accountUrl": account_url,
            "uploadContainer": args.upload_container,
            "downloadContainer": args.download_container,
            "sas": sas_token,
        }
    )

    try:
        import qrcode
    except ImportError:
        print("Install the QR dependency first: python -m pip install 'qrcode[pil]'", file=sys.stderr)
        return 2

    image = qrcode.make(payload)
    image.save(args.output)
    print(f"Wrote {args.output} (SAS expires {sas_fields['se'][0]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
