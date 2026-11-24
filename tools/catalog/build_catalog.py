#!/usr/bin/env python3
"""Builds the catalog that the Israel App Manager app reads.

    build_catalog.py build --catalog catalog/catalog.json --out DIR [--cache DIR] [--report FILE]

`build` reads the owner's catalog (schemaVersion 2, see docs/CATALOG_MANAGEMENT.md), resolves
every app: the app-manager.json of `link` entries, and the newest release of every app (GitHub
Releases, a direct APK link or an external JSON file). Each APK is inspected with aapt2 (package,
version, minimum Android, ABIs, form factor) and apksigner (Android must accept the signature);
its launcher icon is rendered. The result is DIR/catalog.json plus DIR/icons/<id>.png, which
tools/catalog/publish_release.py uploads to the fixed `catalog` GitHub Release.

The run only downloads APKs it has not seen (it compares release asset ids with the previous
DIR/catalog.json), and keeps the last good entry of an app whose release could not be read.

GitHub is reached through the `gh` CLI, so GH_TOKEN (CI) or `gh auth login` (local) is all the
authentication there is. Other https downloads (direct APKs, external JSON, icons) use urllib.
"""
from __future__ import annotations

import argparse
import datetime
import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apk_icon import IconError, extract_icon  # noqa: E402
import source_catalog  # noqa: E402

SCHEMA = 2
SELF_REPO = os.environ.get("GITHUB_REPOSITORY") or "il90il90/israel-app-manager"
CATALOG_TAG = "catalog"
DASHBOARD_URL = os.environ.get("CATALOG_DASHBOARD_URL") or "https://il90il90.github.io/israel-app-manager/"
MAX_NOTES = 6000
MAX_ICON_BYTES = 2 * 1024 * 1024
MAX_EXTERNAL_JSON_BYTES = 256 * 1024
USER_AGENT = "israel-app-manager-catalog"


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def release_base_url(repo=SELF_REPO):
    return f"https://github.com/{repo}/releases/download/{CATALOG_TAG}/"


def icon_asset_name(app_id, digest8):
    """The name of an icon on the catalog release. Content-addressed, so it never changes in place."""
    return f"icon-{app_id}-{digest8}.png"


# --------------------------------------------------------------------------- gh
class GhError(Exception):
    pass


def gh_env(token):
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    return env


def gh_api(path, token=None):
    r = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github+json", path],
                       capture_output=True, text=True, env=gh_env(token))
    if r.returncode != 0:
        raise GhError((r.stderr or r.stdout).strip()[:300])
    return json.loads(r.stdout)


def gh_download(repo, asset_id, dest, token=None):
    tmp = f"{dest}.part"
    with open(tmp, "wb") as f:
        r = subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"repos/{repo}/releases/assets/{asset_id}"],
                           stdout=f, stderr=subprocess.PIPE, env=gh_env(token))
    if r.returncode != 0:
        os.unlink(tmp)
        raise GhError(r.stderr.decode(errors="replace").strip()[:300])
    os.replace(tmp, dest)


def pick_release(source, token=None):
    """Newest published release of `source` that carries a matching APK."""
    releases = gh_api(f"repos/{source['repo']}/releases?per_page=40", token)
    tag_re = re.compile(source.get("tag", ""))
    asset_re = re.compile(source.get("asset", r"\.apk$"), re.I)
    exclude_re = re.compile(source["exclude"], re.I) if source.get("exclude") else None
    best = None
    for rel in releases:
        if rel.get("draft") or (rel.get("prerelease") and not source.get("prerelease")):
            continue
        if not tag_re.search(rel["tag_name"]):
            continue
        assets = [a for a in rel.get("assets", [])
                  if asset_re.search(a["name"]) and not (exclude_re and exclude_re.search(a["name"]))
                  and a.get("state", "uploaded") == "uploaded"]
        if not assets:
            continue
        # Several names for one file (App.apk next to App-1.2.3.apk): keep the versioned one.
        by_size = {}
        for a in assets:
            kept = by_size.get(a["size"])
            if kept is None or len(a["name"]) > len(kept["name"]):
                by_size[a["size"]] = a
        key = rel.get("published_at") or rel.get("created_at") or ""
        if best is None or key > best[0]:
            best = (key, rel, sorted(by_size.values(), key=lambda a: a["name"]))
    if best is None:
        return None
    return best[1], best[2]


def latest_link(source, rel):
    """A GitHub link that keeps pointing at the newest release of this app.

    A direct download when the release carries one APK under a name that never changes
    (App.apk); otherwise the repository's "latest release" page. Repositories that also
    publish other things get the page of this release, because their "latest" may not be it.
    """
    repo = source["repo"]
    try:
        newest = gh_api(f"repos/{repo}/releases/latest")
    except GhError:
        newest = None
    if not newest or newest.get("tag_name") != rel["tag_name"]:
        return rel.get("html_url")
    stable = [a["name"] for a in rel.get("assets", [])
              if a["name"].lower().endswith(".apk") and not re.search(r"\d", a["name"])]
    apks = {a["size"] for a in rel.get("assets", []) if a["name"].lower().endswith(".apk")}
    if len(stable) == 1 and len(apks) == 1:
        return f"https://github.com/{repo}/releases/latest/download/{urllib.parse.quote(stable[0])}"
    return f"https://github.com/{repo}/releases/latest"


# ------------------------------------------------------------------------ aapt2
def find_aapt2():
    if os.environ.get("AAPT2"):
        return os.environ["AAPT2"]
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if root:
            found = sorted(glob.glob(os.path.join(root, "build-tools", "*", "aapt2")))
            if found:
                return found[-1]
    path = shutil.which("aapt2")
    if not path:
        sys.exit("aapt2 not found: set AAPT2 or ANDROID_HOME")
    return path


def find_apksigner(aapt2):
    beside = os.path.join(os.path.dirname(aapt2), "apksigner")
    return beside if os.path.exists(beside) else shutil.which("apksigner")


class NotInstallable(Exception):
    """An APK Android would refuse: it must not be offered for download."""


def signature_problem(path, min_sdk, apksigner):
    """None when Android accepts the APK's signature, else why it does not.

    An unsigned APK looks like any other file on a release page, and every device refuses it.
    Devices older than Android 7 are not held against a v2-only signature.
    """
    if not apksigner:
        return None
    r = subprocess.run([apksigner, "verify", "--min-sdk-version", str(max(min_sdk, 24)), path],
                       capture_output=True, text=True, errors="replace")
    if r.returncode == 0:
        return None
    lines = [l.strip() for l in (r.stdout + r.stderr).splitlines()
             if l.strip() and "JAVA_TOOL_OPTIONS" not in l and l.strip() != "DOES NOT VERIFY"]
    return (lines[0] if lines else "the signature does not verify")[:200]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def inspect_apk(path, aapt2):
    out = subprocess.run([aapt2, "dump", "badging", path], capture_output=True, text=True, errors="replace").stdout
    m = re.search(r"^package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'", out, re.M)
    if not m:
        raise ValueError("not an APK aapt2 can read")
    info = {"packageName": m.group(1), "versionCode": int(m.group(2) or 0), "versionName": m.group(3)}
    m = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", out, re.M)
    info["minSdk"] = int(m.group(1)) if m else 1
    m = re.search(r"^targetSdkVersion:'(\d+)'", out, re.M)
    info["targetSdk"] = int(m.group(1)) if m else info["minSdk"]
    m = re.search(r"^application-label:'(.*)'$", out, re.M)
    info["label"] = m.group(1) if m else ""
    m = re.search(r"^native-code: (.*)$", out, re.M)
    info["abis"] = re.findall(r"'([^']+)'", m.group(1)) if m else []
    features = set(re.findall(r"^\s*uses-feature: name='([^']+)'", out, re.M))
    launcher = bool(re.search(r"^launchable-activity:", out, re.M))
    leanback = bool(re.search(r"^leanback-launchable-activity:", out, re.M))
    info["_watch"] = "android.hardware.type.watch" in features
    info["_tv_only"] = leanback and not launcher
    info["tvCapable"] = leanback
    return info


def form_factor(file_name, info, alone=False):
    """Which kind of device an APK is for.

    The manifest decides when it can. The file name ("-tv", "-watch") only tells the builds of
    one release apart: an app that ships a single APK is not a TV build because its own name
    ends in "tv" (israel-tv-tv.apk has a phone launcher as well as a leanback one).
    """
    name = file_name.lower()
    hints = not alone
    if info["_watch"] or (hints and re.search(r"(^|[-_.])(watch|wear|wearos)([-_.]|$)", name)):
        return "watch"
    if info["_tv_only"] or (hints and re.search(r"(^|[-_.])(tv|androidtv|leanback)([-_.]|$)", name)):
        return "tv"
    return "phone"


def make_variant(path, file_name, url, aapt2, alone=False, apksigner=None):
    info = inspect_apk(path, aapt2)
    problem = signature_problem(path, info["minSdk"], apksigner)
    if problem:
        raise NotInstallable(problem)
    return {
        "formFactor": form_factor(file_name, info, alone),
        "abis": info["abis"],
        "packageName": info["packageName"],
        "versionName": info["versionName"],
        "versionCode": info["versionCode"],
        "minSdk": info["minSdk"],
        "targetSdk": info["targetSdk"],
        "tvCapable": info["tvCapable"],
        "label": info["label"],
        "fileName": file_name,
        "url": url,
        "size": os.path.getsize(path),
        "sha256": sha256_of(path),
    }


# ------------------------------------------------------------------------ icons
def accent_of(png):
    """A vivid colour that represents the icon, as #RRGGBB."""
    import colorsys
    from PIL import Image
    img = Image.open(png).convert("RGBA").resize((48, 48))
    px = img.load()
    buckets = {}
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s < 0.25 or v < 0.3:
                continue
            acc = buckets.setdefault((int(h * 18), int(s * 3), int(v * 3)), [0, 0, 0, 0, 0.0, 0.0])
            acc[0] += 1
            acc[1] += r
            acc[2] += g
            acc[3] += b
            acc[4] += s
            acc[5] += v
    if not buckets:
        return None

    def score(acc):
        n = acc[0]
        return (n ** 0.5) * (acc[4] / n) * (acc[5] / n) ** 2
    n, r, g, b, _, _ = max(buckets.values(), key=score)
    return "#%02X%02X%02X" % (r // n, g // n, b // n)


def file_hash8(path):
    return sha256_of(path)[:8]


# ------------------------------------------------------------------------- http
def http_get(url, max_bytes, dest=None):
    """GET an https URL (no credentials). Returns bytes, or writes to `dest` and returns None."""
    if not source_catalog.is_https(url):
        raise ValueError(f"not an https URL: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        if not response.geturl().startswith("https://"):
            raise ValueError("redirected away from https")
        if dest is None:
            data = response.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise ValueError(f"larger than {max_bytes} bytes")
            return data
        tmp = f"{dest}.part"
        total = 0
        with open(tmp, "wb") as out:
            while chunk := response.read(1 << 20):
                total += len(chunk)
                if total > max_bytes:
                    out.close()
                    os.unlink(tmp)
                    raise ValueError(f"larger than {max_bytes} bytes")
                out.write(chunk)
        os.replace(tmp, dest)
        return None


def http_head(url):
    """(etag/last-modified/length fingerprint) of a URL, or None when the server does not say."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            parts = [response.headers.get("ETag"), response.headers.get("Last-Modified"), response.headers.get("Content-Length")]
            return "|".join(p or "" for p in parts) if any(parts) else None
    except (urllib.error.URLError, OSError, ValueError):
        return None


def save_icon(data, dest):
    """Checks that `data` is an image and stores it as a PNG of at most 512 px."""
    from PIL import Image
    img = Image.open(io.BytesIO(data))
    img.load()
    img = img.convert("RGBA")
    if max(img.size) > 512:
        img.thumbnail((512, 512))
    img.save(dest, "PNG")


# ------------------------------------------------------------------------ build
MAX_APK_BYTES = 1024 * 1024 * 1024


def github_manifest(repo):
    """(app-manager.json of `repo` or None, default branch)."""
    info = gh_api(f"repos/{repo}")
    branch = info.get("default_branch") or "HEAD"
    for path in source_catalog.MANIFEST_PATHS:
        try:
            payload = gh_api(f"repos/{repo}/contents/{path}?ref={urllib.parse.quote(branch)}")
        except GhError as e:
            if "404" in str(e) or "Not Found" in str(e):
                continue
            raise
        text = source_catalog.decode_contents(payload)
        if text is None:
            continue
        try:
            return json.loads(text), branch
        except json.JSONDecodeError as e:
            return {"specVersion": None, "_error": str(e)}, branch
    return None, branch


def github_repo(repo):
    try:
        return gh_api(f"repos/{repo}")
    except GhError:
        return None


def resolve_github(plan, source, ctx):
    prev_variants = ctx["prev_variants"](plan["id"])
    picked = pick_release(source)
    if picked is None:
        return None
    rel, assets = picked
    variants, icon_apk = [], None
    for a in assets:
        key = (a["id"], a.get("updated_at"))
        if key in prev_variants and ctx["icon_exists"](plan["id"]):
            variants.append(prev_variants[key])
            continue
        path = ctx["cache"] / f"{a['id']}.apk"
        if not path.exists() or path.stat().st_size != a["size"]:
            log(f"{plan['id']}: downloading {a['name']} ({a['size']} bytes)")
            gh_download(source["repo"], a["id"], str(path))
        try:
            v = checked_variant(plan, str(path), a["name"], a["browser_download_url"], ctx, alone=len(assets) == 1)
        except NotInstallable as e:
            ctx["warn"](plan["id"], f"{a['name']} in {rel['tag_name']} cannot be installed and is left out: {e}")
            continue
        v["assetId"], v["assetUpdatedAt"] = a["id"], a.get("updated_at")
        variants.append(v)
        if icon_apk is None or (v["formFactor"] == "phone" and "arm64-v8a" in (v["abis"] or ["arm64-v8a"])):
            icon_apk = str(path)
    if not variants:
        return None
    release = {"tag": rel["tag_name"], "name": rel.get("name") or rel["tag_name"],
               "notes": rel.get("body") or "", "publishedAt": rel.get("published_at") or rel.get("created_at"),
               "url": rel.get("html_url")}
    extra = {"repoUrl": f"https://github.com/{source['repo']}", "latestUrl": latest_link(source, rel),
             # The app looks here itself for a release newer than this catalog knows.
             "source": {k: source[k] for k in ("repo", "tag", "asset", "exclude", "prerelease") if source.get(k)}}
    return release, variants, icon_apk, extra


def resolve_url_apk(plan, url, version_name, ctx, fingerprint_extra="", notes="", published_at=None, sha256=None):
    """An APK at a fixed https URL. Downloaded again only when the server says it changed."""
    fingerprint = (http_head(url) or "") + fingerprint_extra
    prev = ctx["prev_variants_by_url"](plan["id"]).get(url)
    if prev and fingerprint and prev.get("assetId") == f"url:{fingerprint}" and ctx["icon_exists"](plan["id"]):
        variants, icon_apk = [prev], None
    else:
        path = ctx["cache"] / f"url-{hashlib.sha256(url.encode()).hexdigest()[:16]}.apk"
        log(f"{plan['id']}: downloading {url}")
        http_get(url, MAX_APK_BYTES, dest=str(path))
        if sha256 and sha256_of(str(path)) != sha256.lower():
            raise NotInstallable("the downloaded file does not match the published SHA-256")
        name = urllib.parse.unquote(urllib.parse.urlsplit(url).path.rsplit("/", 1)[-1]) or f"{plan['id']}.apk"
        v = checked_variant(plan, str(path), name, url, ctx, alone=True)
        if fingerprint:
            v["assetId"] = f"url:{fingerprint}"
        variants, icon_apk = [v], str(path)
    v = variants[0]
    tag = version_name or v["versionName"]
    release = {"tag": tag, "name": tag, "notes": notes or "", "publishedAt": published_at, "url": None}
    return release, variants, icon_apk, {"latestUrl": url}


def resolve_external(plan, url, ctx):
    """An https JSON file: {"versionName", "url", "sha256"?, "notes"?, "publishedAt"?}."""
    data = json.loads(http_get(url, MAX_EXTERNAL_JSON_BYTES).decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("the release JSON is not an object")
    apk = data.get("url") or data.get("apkUrl")
    if not source_catalog.is_https(apk):
        raise ValueError("the release JSON has no https url of an APK")
    version = data.get("versionName") or data.get("version")
    sha = data.get("sha256") if isinstance(data.get("sha256"), str) and re.fullmatch(r"[0-9a-fA-F]{64}", data["sha256"]) else None
    notes = data.get("notes") if isinstance(data.get("notes"), str) else ""
    published = data.get("publishedAt") if isinstance(data.get("publishedAt"), str) else None
    return resolve_url_apk(plan, apk, str(version) if version else None, ctx,
                           fingerprint_extra=f"|{version}|{sha}", notes=notes, published_at=published, sha256=sha)


def checked_variant(plan, path, file_name, url, ctx, alone):
    v = make_variant(path, file_name, url, ctx["aapt2"], alone=alone, apksigner=ctx.get("apksigner"))
    expected = plan.get("packageName")
    if expected and v["packageName"] != expected:
        raise NotInstallable(f"package {v['packageName']} is not the {expected} that app-manager.json declares")
    return v


def resolve_release(plan, ctx):
    """-> (release, variants, icon apk path | None, extra fields) or None when there is nothing to install."""
    if plan["direct"]:
        return resolve_url_apk(plan, plan["direct"]["url"], plan["direct"].get("versionName"), ctx)
    if plan["external"]:
        return resolve_external(plan, plan["external"], ctx)
    for source in plan["sources"]:
        resolved = resolve_github(plan, source, ctx)
        if resolved:
            return resolved
    return None


def license_of(value, catalog):
    """Premium apps: `"license": true` (or {"contact": ...}). None for free apps."""
    if not value:
        return None
    own = value if isinstance(value, dict) else {}
    return {"required": True, "contact": own.get("contact") or (catalog.get("contact") or {}).get("telegram")}


def store_icon(plan, entry, icons, overrides, icon_apk, previous, ctx):
    """Writes icons/<id>.png from the best source there is. Returns the icon style or None."""
    app_id = plan["id"]
    icon_file = icons / f"{app_id}.png"
    if (overrides / f"{app_id}.png").exists():
        shutil.copyfile(overrides / f"{app_id}.png", icon_file)
        return plan.get("iconStyle") or "adaptive"
    if plan.get("iconUrl"):
        try:
            save_icon(http_get(plan["iconUrl"], MAX_ICON_BYTES), str(icon_file))
            return plan.get("iconStyle") or "square"
        except Exception as e:  # noqa: BLE001 - any failure falls back to the APK's icon
            ctx["warn"](app_id, f"icon {plan['iconUrl']} could not be used ({e}); using the APK's icon")
    if icon_apk:
        try:
            return extract_icon(icon_apk, ctx["aapt2"], str(icon_file))
        except IconError as e:
            ctx["warn"](app_id, f"icon not rendered: {e}")
    if icon_file.exists():
        return previous.get(app_id, {}).get("iconStyle") or "adaptive"
    return None


def cmd_build(args):
    catalog_path = Path(args.catalog)
    try:
        spec = source_catalog.load_catalog(catalog_path.read_text("utf-8"))
    except (OSError, source_catalog.CatalogError) as e:
        log(f"::error::{e}")
        write_report(args.report, {"ok": False, "errors": [str(e)], "warnings": []})
        return 2
    out = Path(args.out)
    icons = out / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    cache = Path(args.cache or tempfile.mkdtemp(prefix="apk-cache-"))
    cache.mkdir(parents=True, exist_ok=True)
    overrides = catalog_path.parent / "icons"
    catalog_file = out / "catalog.json"
    old = json.loads(catalog_file.read_text("utf-8")) if catalog_file.exists() else {}
    previous = {a["id"]: a for a in old.get("apps", []) if isinstance(a, dict) and "id" in a}
    aapt2 = find_aapt2()
    report = {"ok": True, "errors": [], "warnings": []}

    def warn(app_id, message):
        log(f"::warning::{app_id}: {message}")
        report["warnings"].append(f"{app_id}: {message}")

    ctx = {
        "aapt2": aapt2, "apksigner": find_apksigner(aapt2), "cache": cache, "warn": warn,
        "icon_exists": lambda app_id: (icons / f"{app_id}.png").exists(),
        "prev_variants": lambda app_id: {(v.get("assetId"), v.get("assetUpdatedAt")): v
                                         for v in (previous.get(app_id, {}).get("variants") or []) if v.get("assetId")},
        "prev_variants_by_url": lambda app_id: {v.get("url"): v for v in (previous.get(app_id, {}).get("variants") or [])},
    }

    entries, failures = [], 0
    order = 0
    for app in spec["apps"]:
        app_id = app["id"]
        if app.get("hidden"):
            log(f"{app_id}: hidden, not published")
            continue
        try:
            plan = source_catalog.plan_app(app, spec, fetch_manifest=github_manifest, fetch_repo=github_repo)
            for w in plan["warnings"]:
                warn(app_id, w)
            resolved = resolve_release(plan, ctx)
        except Exception as e:  # noqa: BLE001 - keep serving the last good entry for this app
            failures += 1
            log(f"::warning::{app_id}: FAILED: {e}")
            report["warnings"].append(f"{app_id}: could not be resolved ({str(e)[:200]}); the last published entry is kept")
            if app_id in previous:
                kept = dict(previous[app_id])
                kept["order"] = order
                entries.append(kept)
                order += 1
            continue
        entry = {
            "id": app_id,
            "type": plan["type"],
            "name": plan["name"],
            "tagline": plan["tagline"],
            "description": plan["description"],
            # `category` is the first one, for app versions that know only one.
            "category": plan["categories"][0],
            "categories": plan["categories"],
            "developer": plan["developer"],
            "website": plan["website"],
            "storeUrl": plan["storeUrl"],
            "repoUrl": plan["repoUrl"],
            "latestUrl": None,
            "source": None,
            "featured": plan["featured"],
            "license": license_of(plan["license"], spec),
            "self": plan["self"],
            "order": order,
            "screenshots": plan["screenshots"],
        }
        order += 1
        icon_apk = None
        if resolved is None:
            log(f"{app_id}: nothing to install yet")
            entry.update({"variants": [], "release": None})
        else:
            release, variants, icon_apk, extra = resolved
            release["notes"] = release["notes"].strip()[:MAX_NOTES]
            entry.update({"variants": variants, "release": release,
                          "repoUrl": plan["repoUrl"] or extra.get("repoUrl"),
                          "latestUrl": extra.get("latestUrl"), "source": extra.get("source")})
            before = {v.get("packageName") for v in previous.get(app_id, {}).get("variants") or []} - {None}
            now = {v["packageName"] for v in variants}
            if before and not (before & now):
                warn(app_id, f"package changed from {', '.join(sorted(before))} to {', '.join(sorted(now))}: check this is intended")
            log(f"{app_id}: {release['tag']} -> " + ", ".join(
                f"{v['formFactor']}{'/' + '+'.join(v['abis']) if len(v['abis']) == 1 else ''} "
                f"{v['versionName']} ({v['versionCode']})" for v in variants))
        style = store_icon(plan, entry, icons, overrides, icon_apk, previous, ctx)
        icon_file = icons / f"{app_id}.png"
        if icon_file.exists():
            entry["iconStyle"] = style or "adaptive"
            entry["icon"] = icon_asset_name(app_id, file_hash8(icon_file))
            entry["accent"] = plan.get("accent") or accent_of(icon_file)
        else:
            entry["icon"] = None
            entry["accent"] = plan.get("accent")
        entries.append(entry)

    wanted = {f"{e['id']}.png" for e in entries}
    for stale in icons.glob("*.png"):
        if stale.name not in wanted:
            stale.unlink()

    catalog = {
        "schemaVersion": SCHEMA,
        # Read by app versions up to 1.1.0, which call it `schema`.
        "schema": SCHEMA,
        "generatedAt": old.get("generatedAt"),
        "baseUrl": release_base_url(),
        "dashboardUrl": DASHBOARD_URL,
        "announcement": spec.get("announcement"),
        "contact": spec.get("contact"),
        "categories": source_catalog.categories_of(spec),
        "apps": entries,
    }
    comparable_old = {k: v for k, v in old.items() if k not in ("sourceCommit", "sourceBlob")}
    if catalog != comparable_old:
        catalog["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        catalog_file.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", "utf-8")
        log(f"catalog written: {len(entries)} apps, {failures} failed")
    else:
        if old:
            # Keep the file as it is (with its source stamp) when nothing changed.
            log(f"catalog unchanged: {len(entries)} apps, {failures} failed")
        else:
            catalog_file.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", "utf-8")
    problems = check_published(json.loads(catalog_file.read_text("utf-8")))
    report["errors"].extend(problems)
    # A run where nothing resolved is an outage, not an empty catalog.
    installable = [e for e in entries if e.get("variants")]
    expected = [a for a in spec["apps"] if not a.get("hidden") and (a.get("type") == "link" or a.get("release"))]
    if expected and not installable:
        report["errors"].append("no app could be resolved: GitHub or the network is probably down")
    report["ok"] = not report["errors"]
    report["apps"] = len(entries)
    report["installable"] = len(installable)
    write_report(args.report, report)
    for e in report["errors"]:
        log(f"::error::{e}")
    return 0 if report["ok"] else 1


def check_published(catalog):
    """Last checks on the file the app will download. Returns a list of problems."""
    problems = []
    if catalog.get("schemaVersion") != SCHEMA:
        problems.append("published catalog has the wrong schemaVersion")
    ids = [a.get("id") for a in catalog.get("apps", [])]
    if len(ids) != len(set(ids)):
        problems.append("published catalog has duplicate app ids")
    for app in catalog.get("apps", []):
        for v in app.get("variants") or []:
            if not source_catalog.is_https(v.get("url")):
                problems.append(f"{app.get('id')}: a download is not an https URL")
            if v.get("sha256") and not re.fullmatch(r"[0-9a-f]{64}", v["sha256"]):
                problems.append(f"{app.get('id')}: a download has a malformed SHA-256")
            if not v.get("packageName"):
                problems.append(f"{app.get('id')}: a download has no package name")
        icon = app.get("icon")
        if icon and not re.fullmatch(r"icon-[a-z0-9-]+-[0-9a-f]{8}\.png", icon) and not source_catalog.is_https(icon):
            problems.append(f"{app.get('id')}: icon {icon!r} is neither a catalog asset nor an https URL")
    return problems


def write_report(path, report):
    if path:
        Path(path).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", "utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("--catalog", "--apps", dest="catalog", default="catalog/catalog.json")
    b.add_argument("--out", required=True)
    b.add_argument("--cache")
    b.add_argument("--report")
    b.set_defaults(func=cmd_build)
    args = parser.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
