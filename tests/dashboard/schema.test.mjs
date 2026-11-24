import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { validateCatalog, validateAppManifest, isHttpsUrl, isValidPattern, localize, emptyCatalog } from "../../dashboard/js/schema.js";

const shipped = JSON.parse(readFileSync(new URL("../../catalog/catalog.json", import.meta.url), "utf8"));
const base = () => ({
  schemaVersion: 2,
  categories: [{ id: "tools", name: { en: "Tools", he: "כלים" } }, { id: "other", name: { en: "Other" } }],
  apps: [{ id: "a", type: "custom", name: "A", categories: ["tools"], release: { strategy: "github-latest", repository: "o/a" } }],
});
const errorsOf = (c) => validateCatalog(c).errors.map((e) => `${e.path}: ${e.message}`).join("\n");

test("the shipped catalog is valid", () => {
  const r = validateCatalog(shipped);
  assert.equal(r.ok, true, errorsOf(shipped));
  assert.equal(r.warnings.length, 0);
});

test("a new empty catalog is valid", () => {
  assert.equal(validateCatalog(emptyCatalog()).ok, true);
});

test("schema version, ids, duplicates and types are enforced", () => {
  const c = base();
  c.schemaVersion = 1;
  c.apps.push({ id: "a", type: "custom", name: "Dup" }, { id: "Bad Id", type: "custom", name: "x" }, { id: "t", type: "weird" });
  const text = errorsOf(c);
  assert.match(text, /schemaVersion: must be 2/);
  assert.match(text, /"a" is used by more than one app/);
  assert.match(text, /apps\[2\]\.id: must use lowercase/);
  assert.match(text, /apps\[3\]\.type: is required and must be "link" or "custom"/);
});

test("link entries need a repository; custom entries need a name", () => {
  const c = base();
  c.apps = [{ id: "l", type: "link" }, { id: "m", type: "custom" }, { id: "n", type: "link", repository: "owner/repo" }];
  const text = errorsOf(c);
  assert.match(text, /apps\[0\]\.repository: is required/);
  assert.match(text, /apps\[1\]\.name: is required/);
  assert.doesNotMatch(text, /apps\[2\]/);
});

test("optional fields are optional; a custom app without release only warns", () => {
  const c = base();
  c.apps = [{ id: "bare", type: "custom", name: "Bare" }];
  const r = validateCatalog(c);
  assert.equal(r.ok, true);
  const warnings = r.warnings.map((w) => w.path).sort();
  assert.deepEqual(warnings, ["apps[0].categories", "apps[0].release"]);
});

test("unsafe URLs are rejected everywhere", () => {
  const c = base();
  Object.assign(c.apps[0], {
    icon: "javascript:alert(1)",
    website: "http://insecure.example",
    screenshots: ["https://ok.example/1.png", "data:image/png;base64,AAAA"],
    license: { contact: "https://user:pw@t.me/x" },
  });
  const text = errorsOf(c);
  for (const path of ["icon", "website", "screenshots[1]", "license.contact"]) assert.match(text, new RegExp(`apps\\[0\\]\\.${path.replace(/[[\]]/g, "\\$&")}: must be an https`));
  assert.doesNotMatch(text, /screenshots\[0\]/);
});

test("categories must exist in the registry, and the registry must include other", () => {
  const c = base();
  c.categories = [{ id: "tools", name: "Tools" }];
  c.apps[0].categories = ["tools", "games", "tools"];
  const text = errorsOf(c);
  assert.match(text, /must include the default category "other"/);
  assert.match(text, /"games" is not in the category registry/);
  assert.match(text, /"tools" is listed twice/);
});

test("release strategies are validated", () => {
  const c = base();
  c.apps = [
    { id: "a", type: "custom", name: "A", release: { strategy: "github-latest" } },
    { id: "b", type: "custom", name: "B", release: { strategy: "direct-apk", url: "ftp://x/b.apk" } },
    { id: "c", type: "custom", name: "C", release: { strategy: "external-json" } },
    { id: "d", type: "custom", name: "D", release: { strategy: "magic" } },
    { id: "e", type: "custom", name: "E", release: { strategy: "github-latest", repository: "o/e", asset: "([" } },
    { id: "f", type: "link", repository: "o/f", release: { strategy: "direct-apk" } },
  ];
  const text = errorsOf(c);
  assert.match(text, /apps\[0\]\.release\.repository: is required/);
  assert.match(text, /apps\[1\]\.release\.url: must be an https/);
  assert.match(text, /apps\[2\]\.release\.url: is required/);
  assert.match(text, /apps\[3\]\.release\.strategy: must be one of/);
  assert.match(text, /apps\[4\]\.release\.asset: is not a valid/);
  assert.match(text, /apps\[5\]\.release\.strategy: can only be "github-latest"/);
});

test("localized text is checked", () => {
  const c = base();
  c.apps[0].tagline = { english: "x", he: "" };
  c.apps[0].description = "x".repeat(5000);
  const text = errorsOf(c);
  assert.match(text, /tagline\.english: is not a language code/);
  assert.match(text, /tagline\.he: must be a non-empty string/);
  assert.match(text, /description: must be at most 4000/);
});

test("only one self app", () => {
  const c = base();
  c.apps.push({ id: "b", type: "custom", name: "B", self: true }, { id: "c", type: "custom", name: "C", self: true });
  assert.match(errorsOf(c), /only one app can be marked "self"/);
});

test("non-objects never throw", () => {
  for (const value of [null, 5, "x", [], { apps: "x", categories: {} }, { apps: [null, 5, "x"] }]) {
    const r = validateCatalog(value);
    assert.equal(r.ok, false);
  }
});

test("app-manager.json validation", () => {
  const good = { specVersion: 1, name: "Cool", by: "Dana", icon: "assets/icon.png", screenshots: ["https://x.example/1.png"],
    android: { packageName: "com.dana.cool", minSdk: 26 }, releases: { asset: "\\.apk$" }, license: "free" };
  assert.deepEqual(validateAppManifest(good).errors, []);
  const bad = validateAppManifest({ specVersion: 2, icon: "../secret.png", screenshots: ["javascript:x"], license: "paid",
    android: { packageName: "x", minSdk: 0 }, releases: { repository: "nope" } });
  const paths = bad.errors.map((e) => e.path);
  for (const p of ["specVersion", "name", "icon", "screenshots[0]", "license", "android.packageName", "android.minSdk", "releases.repository"]) {
    assert.ok(paths.includes(p), `expected an error at ${p}: ${paths}`);
  }
});

test("helpers", () => {
  assert.equal(isHttpsUrl("https://github.com/o/r"), true);
  assert.equal(isHttpsUrl("https://"), false);
  assert.equal(isValidPattern("^v\\d"), true);
  assert.equal(isValidPattern("(?<=x)y"), false);
  assert.equal(localize({ en: "Hi", he: "שלום" }, "he"), "שלום");
  assert.equal(localize({ he: "שלום" }, "fr"), "שלום");
  assert.equal(localize("Plain", "he"), "Plain");
  assert.equal(localize(null), "");
});
