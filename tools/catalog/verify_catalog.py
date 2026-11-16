#!/usr/bin/env python3
"""Checks that every download in the published catalog can really be installed.

    python3 tools/catalog/verify_catalog.py [--catalog URL-or-file] [--keep DIR]

For each APK the catalog offers it fetches the public link exactly as the app would (no
credentials), and checks what would otherwise only show up on somebody's phone:

  * the link answers, and the file has the size and SHA-256 the catalog promises;
  * it is the package and version the catalog says it is;
  * its signature verifies (an unsigned APK is refused by every device);
  * an app that targets Android 11 or newer carries a v2+ signature;
  * it is not a test-only build, which the system installer refuses;
  * its targetSdk is not below what current Android still installs.

Prints one line per file and exits 1 when anything is wrong. Needs ANDROID_HOME (build-tools).
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

LIVE = "https://raw.githubusercontent.com/il90il90/israel-app-manager/catalog/catalog.json"
# Android 14 refuses apps that target below 23, Android 15 below 24.
MIN_TARGET_SDK = 24


def find_tool(name):
    """The newest build-tools copy of aapt2 / apksigner, or the one on PATH."""
    found = []
    for root in filter(None, (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"))):
        found += glob.glob(os.path.join(root, "build-tools", "*", name))
    if found:
        return sorted(found, key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.split(os.sep)[-2])])[-1]
    return shutil.which(name)


def fetch(url, dest):
    request = urllib.request.Request(url, headers={"User-Agent": "israel-app-manager-verify"})
    with urllib.request.urlopen(request, timeout=120) as response, open(dest, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)


def check(path, variant, aapt2, apksigner):
    """-> (facts for the report, list of problems)."""
    problems = []
    size = os.path.getsize(path)
    if size != variant["size"]:
        problems.append(f"size is {size}, the catalog says {variant['size']}")
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    if digest.hexdigest() != variant["sha256"]:
        problems.append("SHA-256 differs from the catalog")

    badging = subprocess.run([aapt2, "dump", "badging", path], capture_output=True, text=True, errors="replace").stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'", badging, re.M)
    if not package:
        return {"signature": "-"}, problems + ["not an APK that can be read"]
    if package.group(1) != variant["packageName"] or int(package.group(2) or 0) != variant["versionCode"]:
        problems.append(f"is {package.group(1)} {package.group(2)}, the catalog says "
                        f"{variant['packageName']} {variant['versionCode']}")
    min_sdk = int((re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M) or [0, 1])[1])
    target = int((re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M) or [0, min_sdk])[1])
    if target < MIN_TARGET_SDK:
        problems.append(f"targets SDK {target}; current Android installs {MIN_TARGET_SDK} and up")
    if "testOnly='-1'" in badging:
        problems.append("is a test-only build, which the system installer refuses")

    signed = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", str(max(min_sdk, 24)), path],
                            capture_output=True, text=True, errors="replace")
    schemes = [name for name in ("v1", "v2", "v3", "v3.1")
               if re.search(rf"Verified using {re.escape(name)} scheme[^:]*: true", signed.stdout)]
    if signed.returncode != 0:
        why = [l.strip() for l in (signed.stdout + signed.stderr).splitlines()
               if l.strip() and "JAVA_TOOL_OPTIONS" not in l and l.strip() != "DOES NOT VERIFY"]
        problems.append("signature does not verify: " + (why[0] if why else "no reason given")[:160])
    elif target >= 30 and not {"v2", "v3", "v3.1"} & set(schemes):
        problems.append("targets Android 11+ with only a v1 signature, which Android 11+ refuses")
    signer = (re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", signed.stdout) or [0, ""])[1]
    debug_key = "CN=Android Debug" in signed.stdout
    return {"signature": "+".join(schemes) or "none", "signer": signer[:12], "debugKey": debug_key,
            "minSdk": min_sdk, "targetSdk": target}, problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalog", default=LIVE, help="catalog.json: a URL or a file (default: the published one)")
    ap.add_argument("--keep", help="keep the downloaded files in this directory (and reuse them)")
    args = ap.parse_args()

    aapt2, apksigner = find_tool("aapt2"), find_tool("apksigner")
    if not aapt2 or not apksigner:
        sys.exit("aapt2 / apksigner not found: set ANDROID_HOME to an SDK with build-tools")

    with tempfile.TemporaryDirectory() as scratch:
        folder = args.keep or scratch
        os.makedirs(folder, exist_ok=True)
        if re.match(r"https?://", args.catalog):
            fetch(args.catalog, os.path.join(scratch, "catalog.json"))
            catalog = json.load(open(os.path.join(scratch, "catalog.json"), encoding="utf-8"))
        else:
            catalog = json.load(open(args.catalog, encoding="utf-8"))
        print(f"catalog of {catalog.get('generatedAt')}: {len(catalog['apps'])} apps")

        failed = 0
        for app in catalog["apps"]:
            if not app["variants"]:
                print(f"::warning::{app['id']}: no download at all")
                continue
            for variant in app["variants"]:
                name = f"{app['id']}--{variant['fileName']}"
                path = os.path.join(folder, name)
                problems = []
                facts = {"signature": "-"}
                try:
                    if not (os.path.exists(path) and os.path.getsize(path) == variant["size"]):
                        fetch(variant["url"], path)
                    facts, problems = check(path, variant, aapt2, apksigner)
                except OSError as e:
                    problems = [f"download failed: {e}"]
                note = " (debug key)" if facts.get("debugKey") else ""
                line = (f"{app['id']:<20} {variant['formFactor']:<6} {variant['versionName']:<12} "
                        f"signed {facts['signature']}{note}")
                if problems:
                    failed += 1
                    print(line + "  FAILED")
                    for problem in problems:
                        print(f"::error::{app['id']} {variant['fileName']}: {problem}")
                else:
                    print(line + "  ok")
        if failed:
            sys.exit(f"{failed} download(s) cannot be installed")
        print("every download can be installed")


if __name__ == "__main__":
    main()
