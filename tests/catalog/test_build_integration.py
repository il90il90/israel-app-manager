"""Runs the real builder (aapt2, apksigner, icon rendering) on a real APK, with GitHub mocked.

Needs ANDROID_HOME with build-tools and an APK to stand in for a release asset:
IAM_TEST_APK, or the debug build (./gradlew :app:assembleDebug). Skipped otherwise.
"""
import json
import os
import shutil
from types import SimpleNamespace

import pytest

import build_catalog as bc
from conftest import ROOT

APK = os.environ.get("IAM_TEST_APK") or os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

pytestmark = pytest.mark.skipif(
    not os.path.exists(APK) or not (os.environ.get("ANDROID_HOME") or os.environ.get("AAPT2")),
    reason="needs an APK and the Android build-tools",
)


def catalog(apps):
    return {"schemaVersion": 2, "developer": "Israel", "contact": {"telegram": "https://t.me/YisraelCohen"},
            "categories": [{"id": "tools", "name": {"en": "Tools"}}, {"id": "other", "name": {"en": "Other"}}], "apps": apps}


@pytest.fixture
def github(monkeypatch, tmp_path):
    state = {"downloads": 0, "fail": set(), "manifest": None}
    asset = {"id": 7, "name": "manager-1.0.apk", "size": os.path.getsize(APK), "updated_at": "2026-01-01T00:00:00Z",
             "browser_download_url": "https://github.com/o/manager/releases/download/v1.0/manager-1.0.apk", "state": "uploaded"}
    release = {"tag_name": "v1.0", "name": "1.0", "body": "## What's new\n- things", "published_at": "2026-01-01T00:00:00Z",
               "html_url": "https://github.com/o/manager/releases/tag/v1.0", "draft": False, "prerelease": False, "assets": [asset]}

    def gh_api(path, token=None):
        repo = "/".join(path.split("/")[1:3])
        if repo in state["fail"]:
            raise bc.GhError("HTTP 502")
        if path.endswith("/releases?per_page=40"):
            return [release]
        if path.endswith("/releases/latest"):
            return release
        if "/contents/" in path:
            if state["manifest"] is None:
                raise bc.GhError("HTTP 404: Not Found")
            import base64
            return {"encoding": "base64", "content": base64.b64encode(json.dumps(state["manifest"]).encode()).decode()}
        if path.count("/") == 2:
            return {"default_branch": "main", "name": repo.split("/")[1], "description": "From GitHub", "owner": {"login": repo.split("/")[0]}}
        raise AssertionError(path)

    def gh_download(repo, asset_id, dest, token=None):
        state["downloads"] += 1
        shutil.copyfile(APK, dest)

    monkeypatch.setattr(bc, "gh_api", gh_api)
    monkeypatch.setattr(bc, "gh_download", gh_download)
    monkeypatch.setattr(bc, "http_get", lambda *a, **k: (_ for _ in ()).throw(OSError("offline")))
    return state


def build(tmp_path, spec):
    src = tmp_path / "src"
    src.mkdir(exist_ok=True)
    (src / "catalog.json").write_text(json.dumps(spec))
    out = tmp_path / "out"
    args = SimpleNamespace(catalog=str(src / "catalog.json"), out=str(out), cache=str(tmp_path / "cache"), report=str(tmp_path / "report.json"))
    code = bc.cmd_build(args)
    return code, json.loads((out / "catalog.json").read_text()), json.loads((tmp_path / "report.json").read_text())


def test_custom_and_link_entries_build_into_the_published_shape(tmp_path, github):
    github["manifest"] = {"specVersion": 1, "name": "Linked Manager", "by": "Dana", "tagline": "From app-manager.json",
                          "android": {"packageName": "com.il90.appmanager.debug"}}
    spec = catalog([
        {"id": "manager", "type": "custom", "name": "Manager", "by": "Israel", "categories": ["tools"],
         "release": {"strategy": "github-latest", "repository": "o/manager", "asset": "\\.apk$"}},
        {"id": "linked", "type": "link", "repository": "dana/linked"},
        {"id": "soon", "type": "custom", "name": "Coming soon"},
        {"id": "secret", "type": "custom", "name": "Hidden", "hidden": True},
    ])
    code, published, report = build(tmp_path, spec)
    assert code == 0, report
    assert published["schemaVersion"] == 2 and published["baseUrl"].endswith("/releases/download/catalog/")
    assert [c["id"] for c in published["categories"]] == ["tools", "other"]
    apps = {a["id"]: a for a in published["apps"]}
    assert set(apps) == {"manager", "linked", "soon"}
    manager = apps["manager"]
    v = manager["variants"][0]
    assert v["packageName"] == "com.il90.appmanager.debug" and len(v["sha256"]) == 64 and v["url"].startswith("https://")
    assert manager["developer"] == "Israel" and manager["categories"] == ["tools"] and manager["category"] == "tools"
    assert manager["release"]["tag"] == "v1.0" and manager["source"]["repo"] == "o/manager"
    assert apps["linked"]["name"] == "Linked Manager" and apps["linked"]["developer"] == "Dana"
    assert apps["linked"]["tagline"] == {"en": "From app-manager.json"} and apps["linked"]["categories"] == ["other"]
    assert apps["soon"]["variants"] == [] and apps["soon"]["release"] is None
    if manager["icon"]:
        assert (tmp_path / "out" / "icons" / "manager.png").exists()
        assert manager["icon"].startswith("icon-manager-") and manager["icon"].endswith(".png")
    assert bc.check_published(published) == []


def test_second_run_reuses_apks_and_a_failing_app_keeps_its_last_entry(tmp_path, github):
    spec = catalog([{"id": "manager", "type": "custom", "name": "Manager", "release": {"strategy": "github-latest", "repository": "o/manager"}}])
    build(tmp_path, spec)
    first = github["downloads"]
    code, published, _ = build(tmp_path, spec)
    if published["apps"][0]["icon"]:
        assert github["downloads"] == first, "an APK already described is not downloaded again"
    github["fail"].add("o/manager")
    code, again, report = build(tmp_path, spec)
    assert again["apps"][0]["variants"] == published["apps"][0]["variants"]
    assert any("last published entry is kept" in w for w in report["warnings"])


def test_package_mismatch_with_app_manager_json_is_refused(tmp_path, github):
    github["manifest"] = {"specVersion": 1, "name": "Impostor", "android": {"packageName": "com.someone.else"}}
    code, published, report = build(tmp_path, catalog([{"id": "linked", "type": "link", "repository": "dana/linked"}]))
    assert published["apps"][0]["variants"] == []
    assert any("com.someone.else" in w for w in report["warnings"])
    assert code == 1 and any("no app could be resolved" in e for e in report["errors"])
