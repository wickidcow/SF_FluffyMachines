#!/usr/bin/env python3
"""Read-only discovery of a proposed *official* stable Paper native fixture.

This command NEVER downloads a server JAR or updates the committed fixture.
The output is a candidate for independent review, not runtime evidence.
"""

from __future__ import annotations

import argparse
import json
import re
from urllib.parse import urlparse
from urllib.request import Request, urlopen

BASE = "https://fill.papermc.io/v3/projects/paper/versions"
USER_AGENT = "FluffyMachines-Native-Fixture-Audit (https://github.com/wickidcow/SF_FluffyMachines)"
SUPPORTED = ("1.21.11", "26.2", "26.3")


def official_json(url: str) -> object:
    if not url.startswith(BASE + "/"):
        raise ValueError("Only official PaperMC metadata endpoints are permitted")
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def newest_stable_id(builds: object) -> int:
    if not isinstance(builds, list):
        raise ValueError("Official Paper builds response is not a list")
    ids = [row["id"] for row in builds if isinstance(row, dict)
           and row.get("channel") == "STABLE" and type(row.get("id")) is int and row["id"] > 0]
    if not ids:
        raise ValueError("No official STABLE build exists; historical fixture remains unchanged")
    return max(ids)


def proposed_pin(version: str, build: int, metadata: object) -> dict:
    if version not in SUPPORTED or type(build) is not int or build <= 0:
        raise ValueError("Unknown version or invalid exact build")
    if not isinstance(metadata, dict) or metadata.get("id") != build or metadata.get("channel") != "STABLE":
        raise ValueError("Exact official metadata does not identify the requested STABLE build")
    if metadata.get("version", version) != version or metadata.get("project", "paper") != "paper":
        raise ValueError("Official metadata project/version mismatch")
    download = metadata.get("downloads", {}).get("server:default", {})
    if not isinstance(download, dict):
        raise ValueError("Missing server:default download")
    name, url, size = download.get("name"), download.get("url"), download.get("size")
    checksums = download.get("checksums")
    digest = checksums.get("sha256") if isinstance(checksums, dict) else None
    expected_name = f"paper-{version}-{build}.jar"
    if name != expected_name or type(size) is not int or size <= 0:
        raise ValueError("Unexpected official server filename or size")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Official metadata lacks a valid SHA-256 digest")
    if not isinstance(url, str):
        raise ValueError("Official metadata lacks a download URL")
    parsed = urlparse(url)
    if (parsed.scheme != "https" or parsed.netloc != "fill-data.papermc.io"
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path != f"/v1/objects/{digest}/{expected_name}"):
        raise ValueError("Official download URL is not bound to the exact server digest")
    return {
        "project": "paper", "version": version, "build": build, "channel": "STABLE",
        "metadata_url": f"{BASE}/{version}/builds/{build}",
        "name": name, "url": url, "size": size, "sha256": digest,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", choices=SUPPORTED, default="26.3")
    parser.add_argument("--build", type=int, help="Optional exact stable build ID; otherwise discover newest STABLE")
    args = parser.parse_args()
    listing = f"{BASE}/{args.version}/builds"
    build = args.build if args.build is not None else newest_stable_id(official_json(listing))
    metadata_url = f"{BASE}/{args.version}/builds/{build}"
    pin = proposed_pin(args.version, build, official_json(metadata_url))
    print(json.dumps({"purpose": "review-only-stable-native-pin-proposal", "pin": pin}, indent=2))


if __name__ == "__main__":
    main()
