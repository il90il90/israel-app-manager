#!/usr/bin/env python3
"""Publishes the built catalog as assets of the fixed `catalog` GitHub Release.

    publish_release.py fetch    --out DIR
    publish_release.py publish  --dir DIR --report FILE [--source-blob SHA] [--commit SHA] [--run-url URL] [--event NAME]
    publish_release.py fail     --report FILE [--source-blob SHA] [--commit SHA] [--run-url URL] [--stage NAME]
    publish_release.py rollback [--run-url URL]
    publish_release.py status

The `catalog` release is separate from the app's own releases: it is created as a pre-release
that is never marked "latest", so it can never be mistaken for a version of the Android app,
and the app's "latest release" links never lead to it.

What the release holds:
  catalog.json            the catalog the Android app downloads (always the last good one)
  catalog-previous.json   the one before it, for rollback
  icon-<id>-<hash>.png    app icons, content-addressed so a file never changes in place

Publishing never removes the last good catalog before the new one is in place: the new file is
uploaded under a temporary name first and then renamed. A failed run only updates the status
in the release description, which the admin dashboard reads to show the outcome.

`fetch` downloads the published catalog and its icons, so the builder can reuse what it already
knows (no APK is downloaded twice). The first time, before the release exists, it reads the
old `catalog` branch instead.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPOSITORY") or "il90il90/israel-app-manager"
TAG = "catalog"
CATALOG = "catalog.json"
PREVIOUS = "catalog-previous.json"
DASHBOARD_URL = os.environ.get("CATALOG_DASHBOARD_URL") or "https://il90il90.github.io/israel-app-manager/"
DOCS_URL = f"https://github.com/{REPO}/blob/main/docs/CATALOG_MANAGEMENT.md"
LEGACY_URL = f"https://raw.githubusercontent.com/{REPO}/catalog/catalog.json"
STATUS_RE = re.compile(r"<!-- catalog-status (\{.*?\}) -->", re.S)
ICON_RE = re.compile(r"^icon-[a-z0-9-]+-[0-9a-f]{8}\.png$")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class GhError(Exception):
    pass


class Gh:
    """The few GitHub operations this script needs, through the `gh` CLI."""

    def __init__(self, repo=REPO):
        self.repo = repo

    def _run(self, args, input_text=None):
        r = subprocess.run(["gh", *args], capture_output=True, text=True, input=input_text)
        if r.returncode != 0:
            raise GhError((r.stderr or r.stdout).strip()[:500])
        return r.stdout

    def api(self, path, method="GET", body=None):
        args = ["api", "-X", method, "-H", "Accept: application/vnd.github+json", path]
        if body is not None:
            args += ["--input", "-"]
        out = self._run(args, json.dumps(body) if body is not None else None)
        return json.loads(out) if out.strip() else None

    def release(self):
        try:
            return self.api(f"repos/{self.repo}/releases/tags/{TAG}")
        except GhError as e:
            if "404" in str(e) or "Not Found" in str(e):
                return None
            raise

    def create_release(self, body):
        default = self.api(f"repos/{self.repo}")["default_branch"]
        return self.api(f"repos/{self.repo}/releases", "POST", {
            "tag_name": TAG,
            "target_commitish": default,
            "name": "App catalog",
            "body": body,
            "draft": False,
            # Never an app release: a pre-release is never "latest".
            "prerelease": True,
            "make_latest": "false",
        })

    def edit_release(self, release_id, body):
        return self.api(f"repos/{self.repo}/releases/{release_id}", "PATCH",
                        {"body": body, "prerelease": True, "make_latest": "false"})

    def upload(self, path, name):
        """Uploads `path` to the release under `name` (a fresh name: nothing is overwritten)."""
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / name
            target.write_bytes(Path(path).read_bytes())
            self._run(["release", "upload", TAG, str(target), "--repo", self.repo])

    def rename(self, asset_id, name):
        return self.api(f"repos/{self.repo}/releases/assets/{asset_id}", "PATCH", {"name": name})

    def delete(self, asset_id):
        self.api(f"repos/{self.repo}/releases/assets/{asset_id}", "DELETE")

    def download(self, asset_id, dest):
        with open(dest, "wb") as f:
            r = subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                                f"repos/{self.repo}/releases/assets/{asset_id}"], stdout=f, stderr=subprocess.PIPE)
        if r.returncode != 0:
            raise GhError(r.stderr.decode(errors="replace")[:300])


# ----------------------------------------------------------------------- status
def read_status(body):
    match = STATUS_RE.search(body or "")
    if not match:
        return None
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return None


def release_body(status):
    """The release description: what this release is, where to manage it, and the last outcome."""
    state = status.get("state")
    icon = {"success": "✅", "failed": "❌", "rolled-back": "↩️", "unchanged": "✅"}.get(state, "ℹ️")
    lines = [
        "## Israel App Manager app catalog",
        "",
        "This release holds the **catalog** the Israel App Manager Android app downloads "
        "(`catalog.json` and the app icons). It is updated automatically by the **Catalog** workflow "
        "after every validated change. It is not a version of the Android app.",
        "",
        f"**Manage the catalog:** [Admin dashboard]({DASHBOARD_URL}) · [Catalog management guide]({DOCS_URL})",
        "",
        f"**Last run:** {icon} {state} at {status.get('at', '?')}"
        + (f" · {status['apps']} apps" if status.get("apps") is not None else "")
        + (f" · [workflow run]({status['runUrl']})" if status.get("runUrl") else ""),
    ]
    if status.get("errors"):
        lines += ["", "Errors (the previous catalog is still published):", ""]
        lines += [f"- {e}" for e in status["errors"][:20]]
    if status.get("pinned"):
        lines += ["", "The catalog was rolled back and is pinned until the next change to catalog/catalog.json."]
    # JSON in an HTML comment: escape ">" so the text can never close the comment.
    blob = json.dumps(status, ensure_ascii=False, separators=(",", ":")).replace(">", "\\u003e")
    lines += ["", f"<!-- catalog-status {blob} -->", ""]
    return "\n".join(lines)


def problem_text(problem):
    """Validation problems come as {"path", "message"}; build problems as plain text."""
    if isinstance(problem, dict):
        problem = f"{problem.get('path', '$')}: {problem.get('message', '')}"
    return str(problem)[:300]


def make_status(state, args, report=None, **extra):
    report = report or {}
    status = {
        "state": state,
        "at": now(),
        "sourceBlob": getattr(args, "source_blob", None),
        "commit": getattr(args, "commit", None),
        "runUrl": getattr(args, "run_url", None),
        "errors": [problem_text(e) for e in report.get("errors", [])][:50],
        "warnings": [problem_text(w) for w in report.get("warnings", [])][:50],
        "apps": report.get("apps"),
    }
    status.update(extra)
    return status


def ensure_release(gh, status):
    rel = gh.release()
    if rel is None:
        log("creating the catalog release")
        rel = gh.create_release(release_body(status))
    return rel


def set_status(gh, status):
    rel = ensure_release(gh, status)
    gh.edit_release(rel["id"], release_body(status))
    return rel


# ---------------------------------------------------------------------- publish
def referenced_icons(catalog):
    return {a.get("icon") for a in (catalog or {}).get("apps", []) if isinstance(a, dict) and a.get("icon") and ICON_RE.match(a["icon"])}


def swap_in(gh, assets, new_file):
    """Makes `new_file` the release's catalog.json, keeping the current one as catalog-previous.json.

    Order matters: the new file is complete on the release before the current one is renamed, so
    there is never a moment without a usable catalog for longer than one rename.
    """
    by_name = {a["name"]: a for a in assets}
    stamp = re.sub(r"\D", "", now())
    temp_name = f"catalog-next-{stamp}.json"
    gh.upload(new_file, temp_name)
    fresh = {a["name"]: a for a in gh.release()["assets"]}[temp_name]
    if PREVIOUS in by_name:
        gh.delete(by_name[PREVIOUS]["id"])
    current = by_name.get(CATALOG)
    if current:
        gh.rename(current["id"], PREVIOUS)
    try:
        gh.rename(fresh["id"], CATALOG)
    except GhError:
        if current:
            gh.rename(current["id"], CATALOG)  # put the last good one back
        raise


def same_file(gh, asset, path):
    """True when the published asset already has the content of `path`."""
    digest = asset.get("digest") or ""
    if digest.startswith("sha256:"):
        import hashlib
        return digest[7:] == hashlib.sha256(Path(path).read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "current.json"
        gh.download(asset["id"], dest)
        return dest.read_bytes() == Path(path).read_bytes()


def cmd_publish(args, gh=None):
    gh = gh or Gh()
    report = json.loads(Path(args.report).read_text("utf-8")) if args.report and Path(args.report).exists() else {}
    out = Path(args.dir)
    new_file = out / CATALOG
    catalog = json.loads(new_file.read_text("utf-8"))
    rel = gh.release()
    previous_status = read_status((rel or {}).get("body"))
    if previous_status and previous_status.get("pinned") and args.event != "push" and args.event != "workflow_dispatch":
        log("the catalog is pinned by a rollback: not publishing until catalog/catalog.json changes")
        return 0
    rel = ensure_release(gh, make_status("publishing", args, report))
    assets = rel.get("assets", [])
    names = {a["name"] for a in assets}

    # 1. Icons first: the new catalog must never point at a file that is not there yet.
    for app in catalog.get("apps", []):
        icon = app.get("icon")
        if icon and ICON_RE.match(icon) and icon not in names:
            local = out / "icons" / f"{app['id']}.png"
            if not local.exists():
                raise SystemExit(f"{app['id']}: icon {icon} is referenced but {local} is missing")
            log(f"uploading {icon}")
            gh.upload(local, icon)

    # 2. The catalog itself, unless it is already what is published.
    current = next((a for a in assets if a["name"] == CATALOG), None)
    if current and same_file(gh, current, new_file):
        state = "unchanged"
        log("catalog.json is already published")
    else:
        swap_in(gh, assets, new_file)
        state = "success"
        log("catalog.json published")

    # 3. Clean up: icons nobody references (current or previous catalog) and stale temporary files.
    assets = gh.release()["assets"]
    keep = referenced_icons(catalog)
    prev_asset = next((a for a in assets if a["name"] == PREVIOUS), None)
    if prev_asset:
        with tempfile.TemporaryDirectory() as tmp:
            try:
                gh.download(prev_asset["id"], Path(tmp) / PREVIOUS)
                keep |= referenced_icons(json.loads((Path(tmp) / PREVIOUS).read_text("utf-8")))
            except (GhError, json.JSONDecodeError):
                keep |= {a["name"] for a in assets if ICON_RE.match(a["name"])}  # unsure: keep them all
    for a in assets:
        stale_icon = ICON_RE.match(a["name"]) and a["name"] not in keep
        stale_temp = a["name"].startswith("catalog-next-")
        if stale_icon or stale_temp:
            log(f"removing {a['name']}")
            gh.delete(a["id"])

    set_status(gh, make_status(state, args, report, pinned=False))
    return 0


def cmd_fail(args, gh=None):
    gh = gh or Gh()
    report = {}
    if args.report and Path(args.report).exists():
        try:
            report = json.loads(Path(args.report).read_text("utf-8"))
        except json.JSONDecodeError:
            report = {}
    if not report.get("errors"):
        report["errors"] = [f"the {args.stage or 'catalog'} step failed; see the workflow run for details"]
    rel = gh.release()
    pinned = bool((read_status((rel or {}).get("body")) or {}).get("pinned"))
    set_status(gh, make_status("failed", args, report, stage=args.stage, pinned=pinned))
    return 0


def cmd_rollback(args, gh=None):
    gh = gh or Gh()
    rel = gh.release()
    if rel is None:
        raise SystemExit("there is no catalog release yet")
    by_name = {a["name"]: a for a in rel["assets"]}
    if PREVIOUS not in by_name or CATALOG not in by_name:
        raise SystemExit("there is no previous catalog to roll back to")
    stamp = re.sub(r"\D", "", now())
    gh.rename(by_name[CATALOG]["id"], f"catalog-rolledback-{stamp}.json")
    gh.rename(by_name[PREVIOUS]["id"], CATALOG)
    gh.rename(by_name[CATALOG]["id"], PREVIOUS)
    args.source_blob, args.commit = None, None
    set_status(gh, make_status("rolled-back", args, {}, pinned=True))
    log("rolled back: catalog-previous.json is now catalog.json (and the other way round)")
    return 0


def http_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "israel-app-manager-catalog"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def cmd_fetch(args, gh=None):
    """Downloads the published catalog and its icons into DIR (icons/<id>.png)."""
    gh = gh or Gh()
    out = Path(args.out)
    (out / "icons").mkdir(parents=True, exist_ok=True)
    rel = gh.release()
    assets = {a["name"]: a for a in (rel or {}).get("assets", [])}
    catalog = None
    if CATALOG in assets:
        gh.download(assets[CATALOG]["id"], out / CATALOG)
        catalog = json.loads((out / CATALOG).read_text("utf-8"))
        for app in catalog.get("apps", []):
            icon = app.get("icon")
            if icon in assets:
                try:
                    gh.download(assets[icon]["id"], out / "icons" / f"{app['id']}.png")
                except GhError as e:
                    log(f"{app['id']}: icon not fetched: {e}")
        log(f"fetched the published catalog ({len(catalog.get('apps', []))} apps)")
        return 0
    try:
        catalog = http_json(LEGACY_URL)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as e:
        log(f"no published catalog yet ({e}): starting fresh")
        return 0
    base = catalog.get("baseUrl", "")
    for app in catalog.get("apps", []):
        icon = app.get("icon")
        if not icon:
            continue
        url = icon if icon.startswith("https://") else base + icon
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "israel-app-manager-catalog"})
            with urllib.request.urlopen(request, timeout=60) as response:
                (out / "icons" / f"{app['id']}.png").write_bytes(response.read())
        except (urllib.error.URLError, OSError) as e:
            log(f"{app['id']}: legacy icon not fetched: {e}")
    # The builder compares against this file; its icon names are rewritten on the next build.
    (out / CATALOG).write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", "utf-8")
    log(f"fetched the legacy catalog branch ({len(catalog.get('apps', []))} apps)")
    return 0


def cmd_status(args, gh=None):
    gh = gh or Gh()
    rel = gh.release()
    print(json.dumps(read_status((rel or {}).get("body")) or {}, indent=2))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--source-blob")
    common.add_argument("--commit")
    common.add_argument("--run-url")
    common.add_argument("--event")
    f = sub.add_parser("fetch")
    f.add_argument("--out", required=True)
    f.set_defaults(func=cmd_fetch)
    p = sub.add_parser("publish", parents=[common])
    p.add_argument("--dir", required=True)
    p.add_argument("--report")
    p.set_defaults(func=cmd_publish)
    x = sub.add_parser("fail", parents=[common])
    x.add_argument("--report")
    x.add_argument("--stage")
    x.set_defaults(func=cmd_fail)
    r = sub.add_parser("rollback", parents=[common])
    r.set_defaults(func=cmd_rollback)
    s = sub.add_parser("status")
    s.set_defaults(func=cmd_status)
    args = parser.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
