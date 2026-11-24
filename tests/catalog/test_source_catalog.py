import json
import os

import pytest

import source_catalog as sc
from conftest import ROOT

CATALOG = {"schemaVersion": 2, "developer": "Israel", "contact": {"telegram": "https://t.me/YisraelCohen"},
           "categories": [{"id": "tools", "name": {"en": "Tools"}}, {"id": "other", "name": {"en": "Other"}}], "apps": []}


def manifest(**over):
    m = {"specVersion": 1, "name": "Cool App", "by": "Dana", "tagline": {"en": "Does things", "he": "עושה דברים"},
         "description": "Long text", "icon": "assets/icon.png", "screenshots": ["assets/1.png", "https://img.example/2.png"],
         "android": {"packageName": "com.dana.cool"},
         "releases": {"repository": "dana/cool-releases", "asset": "^cool-.*\\.apk$"}}
    m.update(over)
    return m


def test_link_entry_reads_app_manager_json():
    app = {"id": "cool", "type": "link", "repository": "dana/cool", "categories": ["tools"]}
    plan = sc.plan_app(app, CATALOG, fetch_manifest=lambda repo: (manifest(), "main"), fetch_repo=lambda repo: None)
    assert plan["name"] == "Cool App"
    assert plan["developer"] == "Dana"
    assert plan["tagline"] == {"en": "Does things", "he": "עושה דברים"}
    assert plan["description"] == {"en": "Long text"}
    assert plan["iconUrl"] == "https://raw.githubusercontent.com/dana/cool/main/assets/icon.png"
    assert plan["screenshots"] == ["https://raw.githubusercontent.com/dana/cool/main/assets/1.png", "https://img.example/2.png"]
    assert plan["sources"] == [{"repo": "dana/cool-releases", "asset": "^cool-.*\\.apk$"}]
    assert plan["packageName"] == "com.dana.cool"
    assert plan["repoUrl"] == "https://github.com/dana/cool"
    assert plan["warnings"] == []


def test_owner_fields_in_the_catalog_win_over_app_manager_json():
    app = {"id": "cool", "type": "link", "repository": "dana/cool", "name": "Renamed", "by": "Israel",
           "tagline": "Mine", "icon": "https://cdn.example/i.png", "release": {"tag": "^v2"}}
    plan = sc.plan_app(app, CATALOG, fetch_manifest=lambda repo: (manifest(), "main"))
    assert plan["name"] == "Renamed"
    assert plan["developer"] == "Israel"
    assert plan["tagline"] == {"en": "Mine"}
    assert plan["iconUrl"] == "https://cdn.example/i.png"
    assert plan["sources"] == [{"repo": "dana/cool-releases", "asset": "^cool-.*\\.apk$", "tag": "^v2"}]


def test_link_without_app_manager_json_falls_back_to_the_repository():
    app = {"id": "plain", "type": "link", "repository": "dana/plain-app"}
    repo = {"name": "plain-app", "description": "A plain app", "owner": {"login": "dana"}, "homepage": "http://insecure"}
    plan = sc.plan_app(app, CATALOG, fetch_manifest=lambda r: (None, "main"), fetch_repo=lambda r: repo)
    assert plan["name"] == "plain-app"
    assert plan["developer"] == "dana"
    assert plan["tagline"] == {"en": "A plain app"}
    assert plan["description"] is None
    assert plan["iconUrl"] is None and plan["screenshots"] == []
    assert plan["website"] is None  # not https: dropped
    assert plan["categories"] == ["other"]
    assert plan["sources"] == [{"repo": "dana/plain-app"}]
    assert any("no app-manager.json" in w for w in plan["warnings"])


def test_bad_app_manager_json_is_ignored_with_a_warning():
    app = {"id": "x", "type": "link", "repository": "dana/x"}
    plan = sc.plan_app(app, CATALOG, fetch_manifest=lambda r: ({"specVersion": 99, "name": "Nope"}, "main"),
                       fetch_repo=lambda r: {"name": "x", "owner": {"login": "dana"}})
    assert plan["name"] == "x"
    assert any("specVersion" in w for w in plan["warnings"])


def test_manifest_fields_of_the_wrong_kind_are_dropped():
    clean, warnings = sc.check_manifest(manifest(name=5, website="javascript:alert(1)", icon="../../etc/passwd",
                                                 android={"packageName": "not a package"},
                                                 releases={"tag": "(", "repository": "bad repo"}))
    assert "name" not in clean and "website" not in clean and "packageName" not in clean
    assert "tag" not in clean["releases"] and "repository" not in clean["releases"]
    assert sc.repo_asset(clean["icon"], "a/b", "main") is None
    assert len(warnings) >= 4


@pytest.mark.parametrize("value", ["javascript:alert(1)", "http://x.example/a.png", "https://user:pw@x.example/", "", None, 5])
def test_only_https_urls_are_accepted(value):
    assert not sc.is_https(value)


def test_custom_strategies():
    base = {"id": "c", "type": "custom", "name": "C"}
    gh = sc.plan_app({**base, "release": {"strategy": "github-latest", "repository": "o/r", "tag": "^v", "prerelease": True}}, CATALOG)
    assert gh["sources"] == [{"repo": "o/r", "tag": "^v", "prerelease": True}]
    direct = sc.plan_app({**base, "release": {"strategy": "direct-apk", "url": "https://x.example/c.apk", "versionName": "1.0"}}, CATALOG)
    assert direct["direct"] == {"url": "https://x.example/c.apk", "versionName": "1.0"} and direct["sources"] == []
    ext = sc.plan_app({**base, "release": {"strategy": "external-json", "url": "https://x.example/c.json"}}, CATALOG)
    assert ext["external"] == "https://x.example/c.json"
    none = sc.plan_app(base, CATALOG)
    assert none["sources"] == [] and none["direct"] is None and none["external"] is None
    assert none["developer"] == "Israel"  # the catalog's developer is the default credit


@pytest.mark.parametrize("text,needle", [
    ("not json", "not valid JSON"),
    ("[]", "JSON object"),
    (json.dumps({"schemaVersion": 1, "apps": []}), "schemaVersion"),
    (json.dumps({"schemaVersion": 2, "apps": [{"id": "A B", "type": "custom", "name": "x"}]}), "valid id"),
    (json.dumps({"schemaVersion": 2, "apps": [{"id": "a", "type": "custom", "name": "x"}, {"id": "a", "type": "custom", "name": "y"}]}), "used twice"),
    (json.dumps({"schemaVersion": 2, "apps": [{"id": "a", "type": "other"}]}), "link or custom"),
    (json.dumps({"schemaVersion": 2, "apps": [{"id": "a", "type": "link"}]}), "repository"),
    (json.dumps({"schemaVersion": 2, "apps": [{"id": "a", "type": "custom"}]}), "needs a name"),
])
def test_load_catalog_rejects_what_cannot_be_built(text, needle):
    with pytest.raises(sc.CatalogError, match=needle):
        sc.load_catalog(text)


def test_category_registry_always_has_other():
    cats = sc.categories_of({"categories": [{"id": "tools", "name": "Tools"}, {"id": "tools", "name": "dup"}]})
    assert cats == [{"id": "tools", "name": {"en": "Tools"}}, {"id": "other", "name": {"en": "Other"}}]


def test_the_shipped_catalog_migrated_every_app():
    text = open(os.path.join(ROOT, "catalog", "catalog.json"), encoding="utf-8").read()
    catalog = sc.load_catalog(text)
    ids = [a["id"] for a in catalog["apps"]]
    assert ids == ["touchless", "seretgram", "wallify", "trustapk", "onedial", "gallerywall", "watchiptv", "scoreup",
                   "touchless-bridge", "screenawake", "touchkey", "zoopli", "abc", "israel-tv", "israel-app-manager"]
    assert all(a["by"] == "Israel" for a in catalog["apps"])
    assert all(a["release"]["strategy"] == "github-latest" for a in catalog["apps"])
    assert [a["id"] for a in catalog["apps"] if a.get("self")] == ["israel-app-manager"]
    assert catalog["contact"]["telegram"] == "https://t.me/YisraelCohen"
    registry = {c["id"] for c in catalog["categories"]}
    assert "other" in registry
    assert all(set(a["categories"]) <= registry for a in catalog["apps"])


def test_json_schemas_accept_the_shipped_catalog_and_the_example():
    jsonschema = pytest.importorskip("jsonschema")
    load = lambda *p: json.load(open(os.path.join(ROOT, *p), encoding="utf-8"))
    jsonschema.Draft202012Validator(load("dashboard", "schemas", "catalog.schema.json")).validate(load("catalog", "catalog.json"))
    manifest_schema = load("dashboard", "schemas", "app-manager.schema.json")
    jsonschema.Draft202012Validator(manifest_schema).validate(load("docs", "examples", "app-manager.json"))
    clean, warnings = sc.check_manifest(load("docs", "examples", "app-manager.json"))
    assert warnings == [] and clean["packageName"] == "com.example.weathernow"
