import json
from types import SimpleNamespace

import publish_release as pr


class FakeGh:
    """An in-memory catalog release that records every operation."""

    def __init__(self, assets=None, body=""):
        self.next_id = 100
        self.ops = []
        self.rel = None if assets is None else {"id": 1, "body": body, "assets": []}
        self.files = {}
        for name, content in (assets or {}).items():
            self._add(name, content)

    def _add(self, name, content):
        self.next_id += 1
        self.rel["assets"].append({"id": self.next_id, "name": name})
        self.files[self.next_id] = content

    def release(self):
        return None if self.rel is None else json.loads(json.dumps(self.rel))

    def create_release(self, body):
        self.ops.append(("create",))
        self.rel = {"id": 1, "body": body, "assets": []}
        return self.release()

    def edit_release(self, release_id, body):
        self.ops.append(("edit",))
        self.rel["body"] = body

    def upload(self, path, name):
        self.ops.append(("upload", name))
        self._add(name, open(path, "rb").read())

    def rename(self, asset_id, name):
        self.ops.append(("rename", asset_id, name))
        next(a for a in self.rel["assets"] if a["id"] == asset_id)["name"] = name

    def delete(self, asset_id):
        self.ops.append(("delete", asset_id))
        self.rel["assets"] = [a for a in self.rel["assets"] if a["id"] != asset_id]

    def download(self, asset_id, dest):
        open(dest, "wb").write(self.files[asset_id])

    def content(self, name):
        return self.files[next(a["id"] for a in self.rel["assets"] if a["name"] == name)]


def args(tmp_path, catalog, report=None, event="push"):
    out = tmp_path / "out"
    (out / "icons").mkdir(parents=True)
    (out / "catalog.json").write_text(json.dumps(catalog))
    for app in catalog["apps"]:
        (out / "icons" / f"{app['id']}.png").write_bytes(b"png-" + app["id"].encode())
    rep = tmp_path / "report.json"
    rep.write_text(json.dumps(report or {"errors": [], "warnings": [], "apps": len(catalog["apps"])}))
    return SimpleNamespace(dir=str(out), report=str(rep), source_blob="blob1", commit="c1", run_url="https://run", event=event)


def cat(*icons):
    return {"schemaVersion": 2, "apps": [{"id": i.split("-")[1], "icon": i} for i in icons]}


def test_status_round_trips_through_the_release_description():
    status = {"state": "failed", "errors": ["apps[0].id: bad --> <script>"], "sourceBlob": "b"}
    body = pr.release_body(status)
    assert "-->" not in body.split("<!-- catalog-status")[1].split(" -->")[0]
    assert pr.read_status(body) == status
    assert "il90il90.github.io/israel-app-manager" in body
    assert pr.read_status("no status here") is None


def test_first_publish_creates_a_prerelease_and_uploads_icons_before_the_catalog(tmp_path):
    gh = FakeGh(assets=None)
    pr.cmd_publish(args(tmp_path, cat("icon-a-00000001.png")), gh)
    uploads = [op for op in gh.ops if op[0] == "upload"]
    assert uploads[0] == ("upload", "icon-a-00000001.png")
    assert uploads[1][1].startswith("catalog-next-")
    names = sorted(a["name"] for a in gh.rel["assets"])
    assert names == ["catalog.json", "icon-a-00000001.png"]
    assert pr.read_status(gh.rel["body"])["state"] == "success"
    assert pr.read_status(gh.rel["body"])["sourceBlob"] == "blob1"


def test_publish_keeps_the_previous_catalog_and_prunes_unused_icons(tmp_path):
    old = cat("icon-a-00000001.png", "icon-b-00000002.png")
    older = cat("icon-c-00000003.png")
    gh = FakeGh(assets={"catalog.json": json.dumps(old).encode(), "catalog-previous.json": json.dumps(older).encode(),
                        "icon-a-00000001.png": b"a", "icon-b-00000002.png": b"b", "icon-c-00000003.png": b"c",
                        "catalog-next-1.json": b"stale"})
    new = cat("icon-a-00000009.png")
    pr.cmd_publish(args(tmp_path, new), gh)
    names = sorted(a["name"] for a in gh.rel["assets"])
    # c belonged only to the catalog two versions back; a-1 and b-2 are still needed for rollback.
    assert names == ["catalog-previous.json", "catalog.json", "icon-a-00000001.png", "icon-a-00000009.png", "icon-b-00000002.png"]
    assert json.loads(gh.content("catalog.json")) == new
    assert json.loads(gh.content("catalog-previous.json")) == old


def test_unchanged_catalog_is_not_uploaded_again(tmp_path):
    c = cat("icon-a-00000001.png")
    a = args(tmp_path, c)
    gh = FakeGh(assets={"catalog.json": open(f"{a.dir}/catalog.json", "rb").read(), "icon-a-00000001.png": b"x"})
    pr.cmd_publish(a, gh)
    assert not [op for op in gh.ops if op[0] in ("upload", "rename")]
    assert pr.read_status(gh.rel["body"])["state"] == "unchanged"


def test_failure_only_updates_the_status(tmp_path):
    good = json.dumps(cat()).encode()
    gh = FakeGh(assets={"catalog.json": good})
    rep = tmp_path / "v.json"
    rep.write_text(json.dumps({"ok": False, "errors": [{"path": "apps[0].id", "message": "is required"}]}))
    pr.cmd_fail(SimpleNamespace(report=str(rep), stage="validation", source_blob="b2", commit="c2", run_url=None), gh)
    status = pr.read_status(gh.rel["body"])
    assert status["state"] == "failed" and status["errors"] == ["apps[0].id: is required"]
    assert gh.content("catalog.json") == good
    assert [op[0] for op in gh.ops] == ["edit"]


def test_rollback_swaps_and_pins_until_the_next_catalog_change(tmp_path):
    old, new = cat("icon-a-00000001.png"), cat("icon-a-00000002.png")
    gh = FakeGh(assets={"catalog.json": json.dumps(new).encode(), "catalog-previous.json": json.dumps(old).encode()})
    pr.cmd_rollback(SimpleNamespace(run_url=None), gh)
    assert json.loads(gh.content("catalog.json")) == old
    assert json.loads(gh.content("catalog-previous.json")) == new
    assert pr.read_status(gh.rel["body"])["pinned"] is True
    # A scheduled run does not undo the rollback...
    ops = len(gh.ops)
    pr.cmd_publish(args(tmp_path, new, event="schedule"), gh)
    assert len(gh.ops) == ops
    # ...a change to the catalog does.
    pr.cmd_publish(args(tmp_path / "2", cat("icon-a-00000003.png"), event="push"), gh)
    assert pr.read_status(gh.rel["body"])["pinned"] is False
