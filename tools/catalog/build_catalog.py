#!/usr/bin/env python3
"""Builds the catalog that the Israel App Manager app reads.

    build_catalog.py build   --apps catalog/apps.json --out DIR [--mirror DIR]
    build_catalog.py mirror  --apps catalog/apps.json --mirror DIR --sources FILE

`build` resolves the newest release of every app in apps.json, inspects each
APK with aapt2 (package, version, minimum Android, ABIs, form factor), renders
its launcher icon and writes DIR/catalog.json plus DIR/icons/<id>.png.

`mirror` fills DIR for the apps whose source is {"mirror": true}: apps that are
released somewhere the public cannot download from. Where each one comes from is
not part of this repository: it is given as FILE (or the PRIVATE_SOURCES_JSON
environment variable), a JSON object {app id: {"repo": ..., "tag": ..., "asset": ...}},
and read with a token that can see those repositories (PRIVATE_REPOS_TOKEN, or
the ambient gh login when run by hand).

GitHub is reached through the `gh` CLI, so GH_TOKEN (CI) or `gh auth login`
(local) is all the authentication there is.
"""
from __future__ import annotations

import argparse
import datetime
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apk_icon import IconError, extract_icon  # noqa: E402

SCHEMA = 1
SELF_REPO = os.environ.get("GITHUB_REPOSITORY") or "il90il90/israel-app-manager"
MIRROR_BRANCH = "apks"
CATALOG_BRANCH = "catalog"
MAX_NOTES = 6000


def log(*a):
    print(*a, file=sys.stderr, flush=True)


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


# ----------------------------------------------------------------------- mirror
def mirror_url(app_id, file_name):
    return (f"https://raw.githubusercontent.com/{SELF_REPO}/{MIRROR_BRANCH}/"
            f"{urllib.parse.quote(app_id)}/{urllib.parse.quote(file_name)}")


def public_release_exists(app):
    for source in app.get("sources", []):
        if source.get("mirror"):
            continue
        try:
            if pick_release(source):
                return True
        except GhError:
            continue
    return False


def cmd_mirror(args):
    apps = json.loads(Path(args.apps).read_text("utf-8"))["apps"]
    if args.sources:
        private_sources = json.loads(Path(args.sources).read_text("utf-8"))
    else:
        private_sources = json.loads(os.environ.get("PRIVATE_SOURCES_JSON") or "{}")
    token = os.environ.get("PRIVATE_REPOS_TOKEN") or None
    mirror = Path(args.mirror)
    mirror.mkdir(parents=True, exist_ok=True)
    changed = False
    for app in apps:
        if not any(s.get("mirror") for s in app.get("sources", [])):
            continue
        target = mirror / app["id"]
        if public_release_exists(app):
            if target.exists():
                log(f"{app['id']}: public releases exist now, dropping the mirror")
                shutil.rmtree(target)
                changed = True
            continue
        private = private_sources.get(app["id"])
        if not private:
            log(f"{app['id']}: no private source given, leaving the mirror as it is")
            continue
        try:
            picked = pick_release(private, token)
        except GhError:
            log(f"{app['id']}: cannot read its private source")
            continue
        if picked is None:
            log(f"{app['id']}: no release with an APK in its private source")
            continue
        rel, assets = picked
        meta_file = target / "release.json"
        old = json.loads(meta_file.read_text("utf-8")) if meta_file.exists() else {}
        names = sorted(a["name"] for a in assets)
        if old.get("tag") == rel["tag_name"] and old.get("files") == names and all((target / n).exists() for n in names):
            log(f"{app['id']}: mirror is current ({rel['tag_name']})")
            continue
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        for a in assets:
            log(f"{app['id']}: mirroring {a['name']} ({a['size']} bytes)")
            gh_download(private["repo"], a["id"], str(target / a["name"]), token)
        meta_file.write_text(json.dumps({
            "tag": rel["tag_name"],
            "name": rel.get("name") or rel["tag_name"],
            "body": rel.get("body") or "",
            "publishedAt": rel.get("published_at") or rel.get("created_at"),
            "files": names,
        }, ensure_ascii=False, indent=2) + "\n", "utf-8")
        changed = True
    log("mirror changed" if changed else "mirror unchanged")
    return 0


# ------------------------------------------------------------------------ build
def localized(value):
    if value is None:
        return None
    if isinstance(value, str):
        return {"en": value}
    return value


def resolve_app(app, ctx):
    """-> (release dict, variants, icon source path|None) or None."""
    aapt2, cache, mirror, previous = ctx["aapt2"], ctx["cache"], ctx["mirror"], ctx["previous"]
    prev_variants = {(v.get("assetId"), v.get("assetUpdatedAt")): v
                     for v in (previous.get(app["id"], {}).get("variants") or []) if v.get("assetId")}
    for source in app.get("sources", []):
        if source.get("mirror"):
            folder = mirror / app["id"] if mirror else None
            if not folder or not (folder / "release.json").exists():
                continue
            meta = json.loads((folder / "release.json").read_text("utf-8"))
            variants, icon_apk = [], None
            for name in meta.get("files", []):
                path = folder / name
                if not path.exists():
                    continue
                try:
                    v = make_variant(str(path), name, mirror_url(app["id"], name), aapt2,
                                     alone=len(meta.get("files", [])) == 1, apksigner=ctx.get("apksigner"))
                except NotInstallable as e:
                    log(f"::warning::{app['id']}: {name} cannot be installed and is left out: {e}")
                    continue
                variants.append(v)
                if icon_apk is None or v["formFactor"] == "phone":
                    icon_apk = icon_apk if (icon_apk and v["formFactor"] != "phone") else str(path)
            if variants:
                release = {"tag": meta.get("tag"), "name": meta.get("name"), "notes": meta.get("body") or "",
                           "publishedAt": meta.get("publishedAt"), "url": None, "repoUrl": None, "latestUrl": None}
                return release, variants, icon_apk
            continue
        try:
            picked = pick_release(source)
        except GhError as e:
            log(f"{app['id']}: {source['repo']}: {e}")
            raise
        if picked is None:
            continue
        rel, assets = picked
        variants, icon_apk = [], None
        for a in assets:
            key = (a["id"], a.get("updated_at"))
            if key in prev_variants and ctx["icon_exists"](app["id"]):
                variants.append(prev_variants[key])
                continue
            path = cache / f"{a['id']}.apk"
            if not path.exists() or path.stat().st_size != a["size"]:
                log(f"{app['id']}: downloading {a['name']} ({a['size']} bytes)")
                gh_download(source["repo"], a["id"], str(path))
            try:
                v = make_variant(str(path), a["name"], a["browser_download_url"], aapt2,
                                 alone=len(assets) == 1, apksigner=ctx.get("apksigner"))
            except NotInstallable as e:
                log(f"::warning::{app['id']}: {a['name']} in {rel['tag_name']} cannot be installed and is left out: {e}")
                continue
            v["assetId"], v["assetUpdatedAt"] = a["id"], a.get("updated_at")
            variants.append(v)
            if icon_apk is None or (v["formFactor"] == "phone" and "arm64-v8a" in (v["abis"] or ["arm64-v8a"])):
                icon_apk = str(path)
        if not variants:
            # Nothing in this release can be installed: the app has no download until one can.
            continue
        release = {"tag": rel["tag_name"], "name": rel.get("name") or rel["tag_name"],
                   "notes": rel.get("body") or "", "publishedAt": rel.get("published_at") or rel.get("created_at"),
                   "url": rel.get("html_url"), "repoUrl": f"https://github.com/{source['repo']}",
                   "latestUrl": latest_link(source, rel),
                   # The app looks here itself for a release newer than this catalog knows.
                   "source": {k: source[k] for k in ("repo", "tag", "asset", "exclude", "prerelease") if source.get(k)}}
        return release, variants, icon_apk
    return None


def license_of(app, spec):
    """Premium apps: `"license": true` (or an object) in apps.json. None for free apps."""
    value = app.get("license")
    if not value:
        return None
    own = value if isinstance(value, dict) else {}
    return {"required": True, "contact": own.get("contact") or (spec.get("contact") or {}).get("telegram")}


def cmd_build(args):
    spec = json.loads(Path(args.apps).read_text("utf-8"))
    out = Path(args.out)
    icons = out / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    mirror = Path(args.mirror) if args.mirror and Path(args.mirror).is_dir() else None
    cache = Path(args.cache or tempfile.mkdtemp(prefix="apk-cache-"))
    cache.mkdir(parents=True, exist_ok=True)
    overrides = Path(args.apps).parent / "icons"
    catalog_file = out / "catalog.json"
    old = json.loads(catalog_file.read_text("utf-8")) if catalog_file.exists() else {}
    previous = {a["id"]: a for a in old.get("apps", [])}
    aapt2 = find_aapt2()
    ctx = {"aapt2": aapt2, "apksigner": find_apksigner(aapt2), "cache": cache, "mirror": mirror, "previous": previous,
           "icon_exists": lambda app_id: (icons / f"{app_id}.png").exists()}

    entries, failures = [], 0
    for order, app in enumerate(spec["apps"]):
        app_id = app["id"]
        try:
            resolved = resolve_app(app, ctx)
        except Exception as e:  # keep serving the last good entry for this app
            failures += 1
            log(f"{app_id}: FAILED: {e}")
            if app_id in previous:
                entries.append(previous[app_id])
            continue
        entry = {
            "id": app_id,
            "name": app["name"],
            "tagline": localized(app.get("tagline")),
            "description": localized(app.get("description")),
            "category": app.get("category", "Apps"),
            "developer": app.get("developer", spec.get("developer", "")),
            "website": app.get("website"),
            "storeUrl": app.get("store"),
            "repoUrl": app.get("repoUrl"),
            "latestUrl": None,
            "source": None,
            "featured": bool(app.get("featured")),
            "license": license_of(app, spec),
            "self": bool(app.get("self")),
            "order": order,
        }
        icon_file = icons / f"{app_id}.png"
        if resolved is None:
            log(f"{app_id}: no release yet")
            entry.update({"variants": [], "release": None})
        else:
            release, variants, icon_apk = resolved
            release["notes"] = release["notes"].strip()[:MAX_NOTES]
            repo_url = release.pop("repoUrl", None)
            entry.update({"variants": variants, "release": release, "repoUrl": app.get("repoUrl", repo_url),
                          "latestUrl": release.pop("latestUrl", None), "source": release.pop("source", None)})
            if icon_apk and not (overrides / f"{app_id}.png").exists():
                try:
                    style = extract_icon(icon_apk, ctx["aapt2"], str(icon_file))
                    entry["iconStyle"] = style
                except IconError as e:
                    log(f"{app_id}: icon not rendered: {e}")
            log(f"{app_id}: {release['tag']} -> " + ", ".join(
                f"{v['formFactor']}{'/' + '+'.join(v['abis']) if len(v['abis']) == 1 else ''} "
                f"{v['versionName']} ({v['versionCode']})" for v in variants))
        if (overrides / f"{app_id}.png").exists():
            shutil.copyfile(overrides / f"{app_id}.png", icon_file)
            entry["iconStyle"] = app.get("iconStyle", "adaptive")
        if icon_file.exists():
            entry.setdefault("iconStyle", previous.get(app_id, {}).get("iconStyle", "adaptive"))
            entry["icon"] = f"icons/{app_id}.png?v={file_hash8(icon_file)}"
            entry["accent"] = app.get("accent") or accent_of(icon_file)
        else:
            entry["icon"] = None
            entry["accent"] = app.get("accent")
        entries.append(entry)

    wanted = {f"{e['id']}.png" for e in entries}
    for stale in icons.glob("*.png"):
        if stale.name not in wanted:
            stale.unlink()

    catalog = {
        "schema": SCHEMA,
        "generatedAt": old.get("generatedAt"),
        "baseUrl": f"https://raw.githubusercontent.com/{SELF_REPO}/{CATALOG_BRANCH}/",
        "announcement": spec.get("announcement"),
        "contact": spec.get("contact"),
        "apps": entries,
    }
    if catalog != old:
        catalog["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        catalog_file.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", "utf-8")
        log(f"catalog written: {len(entries)} apps, {failures} failed")
    else:
        log(f"catalog unchanged: {len(entries)} apps, {failures} failed")
    # A run where nothing resolved is an outage, not an empty catalog.
    if entries and all(not e.get("variants") for e in entries):
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("--apps", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--mirror")
    b.add_argument("--cache")
    b.set_defaults(func=cmd_build)
    m = sub.add_parser("mirror")
    m.add_argument("--apps", required=True)
    m.add_argument("--mirror", required=True)
    m.add_argument("--sources")
    m.set_defaults(func=cmd_mirror)
    args = parser.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
