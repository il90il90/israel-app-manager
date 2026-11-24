import { test } from "node:test";
import assert from "node:assert/strict";
import { canonical, diffCatalogs, summarizeDiff, mergeCatalogs, slugify, uniqueId, removeCategory, pruneEmpty, serializeCatalog } from "../../dashboard/js/catalog-ops.js";

const cat = (apps, categories = [{ id: "other", name: { en: "Other" } }]) => ({ schemaVersion: 2, developer: "Israel", categories, apps });
const app = (id, extra = {}) => ({ id, type: "custom", name: id.toUpperCase(), ...extra });

test("canonical JSON ignores key order", () => {
  assert.equal(canonical({ b: 1, a: [{ y: 2, x: 1 }] }), canonical({ a: [{ x: 1, y: 2 }], b: 1 }));
});

test("diff finds added, removed, changed and reordered apps", () => {
  const before = cat([app("a"), app("b"), app("c")]);
  const after = cat([app("c"), app("a", { name: "Changed" }), app("d")]);
  const d = diffCatalogs(before, after);
  assert.deepEqual(d.apps.added, ["d"]);
  assert.deepEqual(d.apps.removed, ["b"]);
  assert.deepEqual(d.apps.changed, ["a"]);
  assert.equal(d.apps.reordered, true);
  assert.equal(summarizeDiff(d), "Catalog: add d; update a; remove b; reorder apps");
  assert.equal(diffCatalogs(before, structuredClone(before)).count, 0);
});

test("merge combines edits to different apps", () => {
  const base = cat([app("a"), app("b")]);
  const mine = cat([app("a", { name: "Mine" }), app("b"), app("new")]);
  const theirs = cat([app("a"), app("b", { name: "Theirs" })]);
  const { merged, conflicts } = mergeCatalogs(base, mine, theirs);
  assert.deepEqual(conflicts, []);
  assert.deepEqual(merged.apps.map((x) => [x.id, x.name]), [["a", "Mine"], ["b", "Theirs"], ["new", "NEW"]]);
});

test("merge reports the same app changed on both sides", () => {
  const base = cat([app("a")]);
  const { merged, conflicts } = mergeCatalogs(base, cat([app("a", { name: "Mine" })]), cat([app("a", { name: "Theirs" })]));
  assert.equal(conflicts.length, 1);
  assert.equal(conflicts[0].id, "a");
  assert.equal(merged.apps[0].name, "Theirs");
});

test("merge: deleted on one side, untouched on the other", () => {
  const base = cat([app("a"), app("b")]);
  const { merged, conflicts } = mergeCatalogs(base, cat([app("a")]), cat([app("a"), app("b")]));
  assert.deepEqual(conflicts, []);
  assert.deepEqual(merged.apps.map((x) => x.id), ["a"]);
});

test("ids from names", () => {
  assert.equal(slugify("My Cool App!"), "my-cool-app");
  assert.equal(slugify("Café Délice"), "cafe-delice");
  assert.equal(slugify("אבג"), "app");
  assert.equal(uniqueId("My App", new Set(["my-app", "my-app-2"])), "my-app-3");
});

test("removing a category moves orphaned apps to other", () => {
  const c = cat([app("a", { categories: ["games"] }), app("b", { categories: ["games", "tools"] })],
    [{ id: "games", name: "Games" }, { id: "tools", name: "Tools" }, { id: "other", name: "Other" }]);
  const next = removeCategory(c, "games");
  assert.deepEqual(next.categories.map((x) => x.id), ["tools", "other"]);
  assert.deepEqual(next.apps.map((x) => x.categories), [["other"], ["tools"]]);
  assert.throws(() => removeCategory(c, "other"));
});

test("pruneEmpty drops empty optional fields but keeps false and zero", () => {
  assert.deepEqual(pruneEmpty({ a: "", b: [], c: {}, d: { e: " " }, f: false, g: 0, h: ["x", ""], i: null }), { f: false, g: 0, h: ["x"], i: null });
});

test("serialized catalogs end with a newline", () => {
  assert.ok(serializeCatalog({ a: 1 }).endsWith("}\n"));
});
