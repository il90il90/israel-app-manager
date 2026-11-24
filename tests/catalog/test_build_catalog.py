import build_catalog as bc


def entry(**over):
    e = {"id": "a", "icon": "icon-a-0123abcd.png", "variants": [
        {"url": "https://github.com/o/r/releases/download/v1/a.apk", "sha256": "0" * 64, "packageName": "p.a"}]}
    e.update(over)
    return e


def test_published_catalog_checks():
    assert bc.check_published({"schemaVersion": 2, "apps": [entry()]}) == []
    problems = bc.check_published({"schemaVersion": 1, "apps": [
        entry(), entry(icon="../x.png", variants=[{"url": "http://x/a.apk", "sha256": "XYZ", "packageName": ""}])]})
    text = " ".join(problems)
    assert "schemaVersion" in text and "duplicate" in text and "https" in text and "SHA-256" in text
    assert "package name" in text and "icon" in text


def test_license_defaults_to_the_catalog_contact():
    catalog = {"contact": {"telegram": "https://t.me/YisraelCohen"}}
    assert bc.license_of(None, catalog) is None
    assert bc.license_of(False, catalog) is None
    assert bc.license_of(True, catalog) == {"required": True, "contact": "https://t.me/YisraelCohen"}
    assert bc.license_of({"contact": "https://t.me/other"}, catalog)["contact"] == "https://t.me/other"


def test_icons_are_content_addressed_release_assets():
    assert bc.icon_asset_name("touchless", "deadbeef") == "icon-touchless-deadbeef.png"
    assert bc.release_base_url("o/r") == "https://github.com/o/r/releases/download/catalog/"


def test_external_json_must_name_an_https_apk(monkeypatch):
    monkeypatch.setattr(bc, "http_get", lambda url, limit, dest=None: b'{"versionName": "1.0", "url": "http://x/a.apk"}')
    try:
        bc.resolve_external({"id": "a"}, "https://x.example/r.json", {})
    except ValueError as e:
        assert "https" in str(e)
    else:
        raise AssertionError("an http APK link must be refused")


def test_package_declared_in_app_manager_json_must_match(monkeypatch):
    monkeypatch.setattr(bc, "make_variant", lambda *a, **k: {"packageName": "com.evil.app"})
    try:
        bc.checked_variant({"packageName": "com.good.app"}, "/x.apk", "x.apk", "https://x/x.apk", {"aapt2": "aapt2"}, True)
    except bc.NotInstallable as e:
        assert "com.good.app" in str(e)
    else:
        raise AssertionError("a package mismatch must be refused")
