#!/usr/bin/env python3
"""Select an official stable core with a matching Android AAR."""
import json
import os
import re
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

API = "https://api.github.com"
AAR_REPO = "singbox-android/libbox"


def get_json(url, token=None, allow_missing=False):
    headers = {"User-Agent": "F-SingBox-Release"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        raise


def version(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError(f"Invalid stable version: {value!r}")
    return tuple(map(int, value.split(".")))


def libbox_digest(core, settings):
    if core == settings["fCoreVersion"]:
        return settings["fLibboxSha256"]
    aar_release = get_json(f"{API}/repos/{AAR_REPO}/releases/tags/{core}", allow_missing=True)
    if not aar_release or aar_release["draft"] or aar_release["prerelease"]:
        print(f"Waiting for stable Android libbox {core}")
        return None
    asset = next((a for a in aar_release["assets"]
                  if a["name"] == "libbox.aar" and a["state"] == "uploaded"), None)
    if not asset:
        print(f"Waiting for the Android libbox {core} AAR")
        return None
    checksum = re.fullmatch(r"sha256:([0-9a-f]{64})", asset.get("digest") or "")
    if not checksum:
        raise ValueError("Android libbox asset is missing a SHA-256 digest")
    return checksum[1]


def plan(repo, token, settings):
    official = get_json(f"{API}/repos/SagerNet/sing-box/releases/latest")
    tag = official["tag_name"]
    if official["draft"] or official["prerelease"] or not tag.startswith("v"):
        raise ValueError("Official latest release is not stable")
    latest_core = tag[1:]
    latest_version = version(latest_core)
    baseline = settings["fCoreVersion"]
    if latest_version < version(baseline):
        print("Official latest is older than the local baseline")
        return {}

    app = version(settings["fAppVersionName"])
    code = int(settings["fAppVersionCode"])
    published = get_json(f"{API}/repos/{repo}/releases/latest", token, allow_missing=True)
    published_core = None
    app_changed = True
    if published:
        release_tag = published["tag_name"]
        tag_match = re.fullmatch(r"f-core-([0-9]+\.[0-9]+\.[0-9]+)(?:-app-([0-9]+\.[0-9]+\.[0-9]+))?", release_tag)
        if not tag_match:
            raise ValueError("Latest release must be managed by F core workflow")
        published_core, tag_app = tag_match.groups()
        if latest_version < version(published_core):
            print("Official latest is older than the published core")
            return {}
        manifest = get_json(f"https://github.com/{repo}/releases/download/{release_tag}/f-updates.json")
        published_app = manifest["version_name"]
        if manifest["core_version"] != published_core or (tag_app and published_app != tag_app):
            raise ValueError("Latest release and update manifest disagree")
        published_code = manifest["version_code"]
        if type(published_code) is not int or published_code < 1:
            raise ValueError("Latest manifest has an invalid version code")
        app_changed = app > version(published_app)
        if app < version(published_app):
            print("Local app version is older than the published app")
            return {}
        code = max(code, published_code)

    # A newer core may not have a matching Android AAR yet. Publish an app
    # update against the current stable core in that case.
    for core in dict.fromkeys((latest_core, published_core or baseline)):
        if core == published_core and not app_changed:
            continue
        digest = libbox_digest(core, settings)
        if digest:
            return {"core": core, "libbox_sha256": digest, "version_code": code + 1}
    return {}


if __name__ == "__main__":
    properties = Path("gradle.properties").read_text(encoding="utf-8").splitlines()
    settings = dict(line.split("=", 1) for line in properties if line.startswith("f"))
    outputs = plan(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"], settings)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as target:
        for name, value in outputs.items():
            print(f"{name}={value}", file=target)
