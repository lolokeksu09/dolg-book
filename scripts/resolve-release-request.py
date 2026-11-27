#!/usr/bin/env python3
"""Resolve an authorized release push; no publishing, signing or external calls."""
import json
import os
import re
from pathlib import Path


def resolve(root, ref):
    gradle = (root / "android/app/build.gradle.kts").read_text()
    versions = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", gradle, re.M)
    if len(versions) != 1 or len(codes) != 1:
        raise ValueError("Expected exactly one Android version and code")
    version, code = versions[0], int(codes[0])
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*)?", version):
        raise ValueError("Invalid Android version")
    if ref == "refs/heads/main":
        request = json.loads((root / "android/release-request.json").read_text())
        if set(request) != {"tag", "versionName", "versionCode", "notes"}:
            raise ValueError("Invalid release request")
        if request["versionName"] != version or type(request["versionCode"]) is not int or request["versionCode"] != code:
            raise ValueError("Release request does not match Gradle")
        if not isinstance(request["notes"], str) or not request["notes"].strip():
            raise ValueError("Missing release notes")
        tag = request["tag"]
    elif ref.startswith("refs/tags/"):
        tag = ref.removeprefix("refs/tags/")
    else:
        raise ValueError("Unsupported release ref")
    if not isinstance(tag, str) or not re.fullmatch(r"raschet-v" + re.escape(version) + r"-rc\.[1-9][0-9]*", tag):
        raise ValueError("Invalid release tag")
    if code <= 0:
        raise ValueError("Invalid version code")
    return tag, code


if __name__ == "__main__":
    tag, code = resolve(Path(__file__).resolve().parents[1], os.environ["GITHUB_REF"])
    print(f"tag={tag}")
    print(f"version_code={code}")
