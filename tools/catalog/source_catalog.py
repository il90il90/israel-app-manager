"""Reads catalog/catalog.json (schemaVersion 2) and turns each app into a build plan.

The catalog has two kinds of entries:

* ``link``: a GitHub repository. Its ``app-manager.json`` (see docs/DEVELOPER_GUIDE.md) supplies
  the name, developer credit, descriptions, icon, screenshots and where the releases are. What
  the repository does not say falls back to the repository's own name and description. Anything
  the owner writes into the catalog entry itself wins over both.
* ``custom``: everything is written in the catalog entry, and ``release`` says where the APK
  comes from: ``github-latest`` (the newest matching GitHub Release), ``direct-apk`` (a fixed
  https link to an APK) or ``external-json`` (an https link to a small JSON file that names the
  current APK).

An app without a release is still listed; it simply has nothing to install.

Network access is injected (``fetch_manifest``, ``fetch_repo``), so this module is unit-tested
without GitHub.
"""
from __future__ import annotations

import base64
import json
import re
import urllib.parse

SCHEMA_VERSION = 2
APP_MANAGER_SPEC_VERSION = 1
DEFAULT_CATEGORY = "other"
MANIFEST_PATHS = ("app-manager.json", ".github/app-manager.json")
REPOSITORY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$")
ID_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
MAX_SCREENSHOTS = 10


class CatalogError(Exception):
    """The source catalog cannot be built at all (wrong schema version, not an object...)."""


def is_https(url) -> bool:
    if not isinstance(url, str) or len(url) > 2048:
        return False
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    return parts.scheme == "https" and bool(parts.hostname) and not parts.username and not parts.password


def localized(value):
    """A plain string means English; maps keep only non-empty strings. None when nothing is left."""
    if value is None:
        return None
    if isinstance(value, str):
        return {"en": value} if value.strip() else None
    if isinstance(value, dict):
        out = {k: v for k, v in value.items() if isinstance(k, str) and isinstance(v, str) and v.strip()}
        return out or None
    return None


def raw_url(repo: str, branch: str, path: str) -> str:
    return f"https://raw.githubusercontent.com/{repo}/{urllib.parse.quote(branch)}/{urllib.parse.quote(path)}"


def repo_asset(value, repo, branch):
    """An icon or screenshot from app-manager.json: an https URL, or a path in the repository."""
    if not isinstance(value, str) or not value.strip():
        return None
    if re.match(r"^[a-z]+:", value, re.I):
        return value if is_https(value) else None
    path = value.strip()
    if path.startswith("/") or "\\" in path or ".." in path.split("/"):
        return None
    while path.startswith("./"):
        path = path[2:]
    if not path:
        return None
    return raw_url(repo, branch, path)


def check_manifest(manifest):
    """-> (usable manifest dict or None, list of warnings). Lenient: bad fields are dropped."""
    warnings = []
    if not isinstance(manifest, dict):
        return None, ["app-manager.json is not a JSON object"]
    if manifest.get("specVersion") != APP_MANAGER_SPEC_VERSION:
        return None, [f"app-manager.json specVersion {manifest.get('specVersion')!r} is not supported "
                      f"(this catalog reads {APP_MANAGER_SPEC_VERSION})"]
    clean = {}
    for key in ("name", "by"):
        value = manifest.get(key)
        if isinstance(value, str) and value.strip():
            clean[key] = value.strip()[:80]
        elif value is not None:
            warnings.append(f"app-manager.json {key} is not a string and is ignored")
    for key in ("tagline", "description"):
        if manifest.get(key) is not None:
            value = localized(manifest.get(key))
            if value is None:
                warnings.append(f"app-manager.json {key} is not text and is ignored")
            else:
                clean[key] = value
    for key in ("website", "developerUrl"):
        if manifest.get(key) is not None:
            if is_https(manifest[key]):
                clean[key] = manifest[key]
            else:
                warnings.append(f"app-manager.json {key} is not an https URL and is ignored")
    if manifest.get("license") in ("free", "premium"):
        clean["license"] = manifest["license"]
    for key in ("icon",):
        if manifest.get(key) is not None:
            clean[key] = manifest[key]
    if isinstance(manifest.get("screenshots"), list):
        clean["screenshots"] = manifest["screenshots"][:MAX_SCREENSHOTS]
    android = manifest.get("android")
    if isinstance(android, dict):
        pkg = android.get("packageName")
        if pkg is not None:
            if isinstance(pkg, str) and PACKAGE_RE.match(pkg):
                clean["packageName"] = pkg
            else:
                warnings.append("app-manager.json android.packageName is not a package name and is ignored")
    releases = manifest.get("releases")
    if isinstance(releases, dict):
        rel = {}
        if releases.get("repository") is not None:
            if isinstance(releases["repository"], str) and REPOSITORY_RE.match(releases["repository"]):
                rel["repository"] = releases["repository"]
            else:
                warnings.append("app-manager.json releases.repository is not owner/name and is ignored")
        for key in ("tag", "asset", "exclude"):
            value = releases.get(key)
            if value is None:
                continue
            try:
                re.compile(value)
                rel[key] = value
            except (re.error, TypeError):
                warnings.append(f"app-manager.json releases.{key} is not a valid pattern and is ignored")
        clean["releases"] = rel
    return clean, warnings


def github_source(spec):
    """The {repo, tag, asset, exclude, prerelease} rule the builder resolves releases with."""
    return {k: v for k, v in {
        "repo": spec.get("repository"),
        "tag": spec.get("tag"),
        "asset": spec.get("asset"),
        "exclude": spec.get("exclude"),
        "prerelease": spec.get("prerelease") or None,
    }.items() if v}


def plan_app(app, catalog, fetch_manifest=None, fetch_repo=None):
    """One catalog entry -> build plan (a dict) with everything resolved except the release itself.

    fetch_manifest(repo) -> (manifest dict | None, default branch)   raises on network errors
    fetch_repo(repo)     -> GitHub repository JSON | None            raises on network errors
    """
    warnings = []
    plan = {
        "id": app["id"],
        "type": app["type"],
        "name": None,
        "developer": None,
        "tagline": None,
        "description": None,
        "categories": [c for c in (app.get("categories") or []) if isinstance(c, str)] or [DEFAULT_CATEGORY],
        "website": app.get("website"),
        "storeUrl": app.get("storeUrl"),
        "repoUrl": app.get("repoUrl"),
        "featured": bool(app.get("featured")),
        "self": bool(app.get("self")),
        "license": app.get("license"),
        "accent": app.get("accent"),
        "iconStyle": app.get("iconStyle"),
        "iconUrl": app.get("icon") if is_https(app.get("icon")) else None,
        "screenshots": [s for s in (app.get("screenshots") or []) if is_https(s)][:MAX_SCREENSHOTS],
        "sources": [],
        "direct": None,
        "external": None,
        "packageName": None,
        "warnings": warnings,
    }
    release = app.get("release") if isinstance(app.get("release"), dict) else None

    if app["type"] == "link":
        repo = app["repository"]
        manifest, branch, info = None, "HEAD", None
        if fetch_manifest:
            raw_manifest, branch = fetch_manifest(repo)
            if raw_manifest is None:
                warnings.append(f"{repo} has no app-manager.json; using the repository's name and description")
            else:
                manifest, problems = check_manifest(raw_manifest)
                warnings.extend(problems)
        if fetch_repo and (manifest is None or not manifest.get("tagline") or not manifest.get("by")):
            info = fetch_repo(repo)
        manifest = manifest or {}
        owner = repo.split("/")[0]
        plan["name"] = app.get("name") or manifest.get("name") or (info or {}).get("name") or repo.split("/")[1]
        plan["developer"] = app.get("by") or manifest.get("by") or ((info or {}).get("owner") or {}).get("login") or owner
        plan["tagline"] = localized(app.get("tagline")) or manifest.get("tagline") or localized((info or {}).get("description"))
        plan["description"] = localized(app.get("description")) or manifest.get("description")
        plan["website"] = plan["website"] or manifest.get("website") or ((info or {}).get("homepage") if is_https((info or {}).get("homepage")) else None)
        plan["repoUrl"] = plan["repoUrl"] or f"https://github.com/{repo}"
        if app.get("license") is None and manifest.get("license") == "premium":
            plan["license"] = True
        if not plan["iconUrl"]:
            plan["iconUrl"] = repo_asset(manifest.get("icon"), repo, branch)
        if not plan["screenshots"]:
            plan["screenshots"] = [u for u in (repo_asset(s, repo, branch) for s in manifest.get("screenshots", [])) if u]
        plan["packageName"] = manifest.get("packageName")
        rules = dict(manifest.get("releases") or {})
        rules.update({k: v for k, v in (release or {}).items() if k != "strategy"})
        rules.setdefault("repository", repo)
        plan["sources"] = [github_source(rules)]
    else:
        plan["name"] = app["name"]
        plan["developer"] = app.get("by")
        plan["tagline"] = localized(app.get("tagline"))
        plan["description"] = localized(app.get("description"))
        strategy = (release or {}).get("strategy")
        if strategy == "github-latest":
            plan["sources"] = [github_source(release)]
        elif strategy == "direct-apk":
            plan["direct"] = {"url": release["url"], "versionName": release.get("versionName")}
        elif strategy == "external-json":
            plan["external"] = release["url"]
        elif release is not None:
            warnings.append(f"unknown release strategy {strategy!r}: the app is listed without a download")
    plan["developer"] = plan["developer"] or catalog.get("developer") or ""
    return plan


def load_catalog(text):
    """Parses the source catalog and checks what the build cannot work without."""
    try:
        catalog = json.loads(text)
    except json.JSONDecodeError as e:
        raise CatalogError(f"catalog.json is not valid JSON: {e}") from e
    if not isinstance(catalog, dict):
        raise CatalogError("catalog.json must be a JSON object")
    if catalog.get("schemaVersion") != SCHEMA_VERSION:
        raise CatalogError(f"catalog.json schemaVersion must be {SCHEMA_VERSION}, found {catalog.get('schemaVersion')!r}")
    apps = catalog.get("apps")
    if not isinstance(apps, list):
        raise CatalogError("catalog.json has no apps list")
    seen = set()
    for i, app in enumerate(apps):
        if not isinstance(app, dict) or not isinstance(app.get("id"), str) or not ID_RE.match(app["id"]):
            raise CatalogError(f"apps[{i}] has no valid id")
        if app["id"] in seen:
            raise CatalogError(f"apps[{i}]: id {app['id']!r} is used twice")
        seen.add(app["id"])
        if app.get("type") not in ("link", "custom"):
            raise CatalogError(f"apps[{i}] ({app['id']}): type must be link or custom")
        if app["type"] == "link" and not (isinstance(app.get("repository"), str) and REPOSITORY_RE.match(app["repository"])):
            raise CatalogError(f"apps[{i}] ({app['id']}): a link entry needs repository owner/name")
        if app["type"] == "custom" and not (isinstance(app.get("name"), str) and app["name"].strip()):
            raise CatalogError(f"apps[{i}] ({app['id']}): a custom entry needs a name")
    return catalog


def categories_of(catalog):
    """The category registry as published: every category with its localized name; "other" always present."""
    out, seen = [], set()
    for category in catalog.get("categories") or []:
        if isinstance(category, dict) and isinstance(category.get("id"), str) and category["id"] not in seen:
            seen.add(category["id"])
            out.append({"id": category["id"], "name": localized(category.get("name")) or {"en": category["id"]}})
    if DEFAULT_CATEGORY not in seen:
        out.append({"id": DEFAULT_CATEGORY, "name": {"en": "Other"}})
    return out


def decode_contents(payload):
    """The text of a GitHub "contents" API answer."""
    if not isinstance(payload, dict) or payload.get("encoding") != "base64":
        return None
    return base64.b64decode(payload.get("content", "")).decode("utf-8")
