// Pure operations on catalogs: comparing, merging and describing changes.
// No DOM, no network: unit-tested with node:test (tests/dashboard).

import { DEFAULT_CATEGORY } from "./schema.js";

/** JSON with object keys sorted, so two equal values always give the same text. */
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .filter((k) => value[k] !== undefined)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

export const deepEqual = (a, b) => canonical(a) === canonical(b);
export const clone = (value) => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

/** The text written to catalog/catalog.json: two-space indentation and a final newline. */
export function serializeCatalog(catalog) {
  return `${JSON.stringify(catalog, null, 2)}\n`;
}

/** A catalog id made from a name: "My App 2" -> "my-app-2". */
export function slugify(name) {
  const slug = String(name ?? "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 64)
    .replace(/-+$/g, "");
  return slug || "app";
}

/** An id that is not in `taken`, based on `wanted`. */
export function uniqueId(wanted, taken) {
  const base = slugify(wanted);
  if (!taken.has(base)) return base;
  for (let i = 2; ; i++) {
    const candidate = `${base.slice(0, 60)}-${i}`;
    if (!taken.has(candidate)) return candidate;
  }
}

const byId = (list) => new Map((Array.isArray(list) ? list : []).filter((x) => x && typeof x.id === "string").map((x) => [x.id, x]));
const META_KEYS = ["schemaVersion", "developer", "contact", "announcement"];

/**
 * What changed between two catalogs.
 * @returns {{apps: {added: string[], removed: string[], changed: string[], reordered: boolean},
 *            categories: {added: string[], removed: string[], changed: string[], reordered: boolean},
 *            meta: string[], count: number}}
 */
export function diffCatalogs(base, current) {
  const section = (a, b) => {
    const before = byId(a);
    const after = byId(b);
    const added = [...after.keys()].filter((id) => !before.has(id));
    const removed = [...before.keys()].filter((id) => !after.has(id));
    const changed = [...after.keys()].filter((id) => before.has(id) && !deepEqual(before.get(id), after.get(id)));
    const common = (list, other) => (Array.isArray(list) ? list : []).map((x) => x?.id).filter((id) => other.has(id));
    const reordered = common(a, after).join("\n") !== common(b, before).join("\n");
    return { added, removed, changed, reordered };
  };
  const apps = section(base?.apps, current?.apps);
  const categories = section(base?.categories, current?.categories);
  const meta = META_KEYS.filter((k) => !deepEqual(base?.[k], current?.[k]));
  const count =
    apps.added.length + apps.removed.length + apps.changed.length + (apps.reordered ? 1 : 0) +
    categories.added.length + categories.removed.length + categories.changed.length + (categories.reordered ? 1 : 0) +
    meta.length;
  return { apps, categories, meta, count };
}

/** A one-line commit message describing a diff. */
export function summarizeDiff(diff) {
  const parts = [];
  const list = (verb, ids) => ids.length && parts.push(`${verb} ${ids.length <= 3 ? ids.join(", ") : `${ids.length} apps`}`);
  list("add", diff.apps.added);
  list("update", diff.apps.changed);
  list("remove", diff.apps.removed);
  if (diff.apps.reordered) parts.push("reorder apps");
  const cats = diff.categories.added.length + diff.categories.removed.length + diff.categories.changed.length + (diff.categories.reordered ? 1 : 0);
  if (cats) parts.push("edit categories");
  if (diff.meta.length) parts.push(`edit ${diff.meta.join(", ")}`);
  return parts.length ? `Catalog: ${parts.join("; ")}` : "Catalog: no changes";
}

/**
 * Three-way merge of a list of {id} records: changes made on both sides to different records are
 * combined; the same record changed differently on both sides is a conflict.
 */
function mergeList(base, mine, theirs, label, conflicts) {
  const b = byId(base);
  const m = byId(mine);
  const t = byId(theirs);
  const resolved = new Map();
  const ids = new Set([...b.keys(), ...m.keys(), ...t.keys()]);
  for (const id of ids) {
    const inB = b.has(id), inM = m.has(id), inT = t.has(id);
    const vb = b.get(id), vm = m.get(id), vt = t.get(id);
    const mineChanged = inM !== inB || (inM && !deepEqual(vm, vb));
    const theirsChanged = inT !== inB || (inT && !deepEqual(vt, vb));
    if (!mineChanged) {
      if (inT) resolved.set(id, vt);
    } else if (!theirsChanged) {
      if (inM) resolved.set(id, vm);
    } else if (inM === inT && (!inM || deepEqual(vm, vt))) {
      if (inM) resolved.set(id, vm);
    } else {
      conflicts.push({ kind: label, id, mine: vm ?? null, theirs: vt ?? null });
      if (inT) resolved.set(id, vt);
    }
  }
  // Order: theirs first (it is what is published), then records only I added, in my order.
  const order = [];
  const seen = new Set();
  const push = (id) => {
    if (resolved.has(id) && !seen.has(id)) {
      seen.add(id);
      order.push(resolved.get(id));
    }
  };
  const mineOrderChanged = (Array.isArray(mine) ? mine : []).map((x) => x.id).filter((id) => b.has(id)).join("\n") !==
    (Array.isArray(base) ? base : []).map((x) => x.id).filter((id) => m.has(id)).join("\n");
  const primary = mineOrderChanged ? mine : theirs;
  (Array.isArray(primary) ? primary : []).forEach((x) => push(x.id));
  (Array.isArray(theirs) ? theirs : []).forEach((x) => push(x.id));
  (Array.isArray(mine) ? mine : []).forEach((x) => push(x.id));
  return order;
}

/**
 * Merges my edits (base -> mine) into a catalog someone else published meanwhile (base -> theirs).
 * @returns {{merged: object, conflicts: {kind: string, id: string, mine: any, theirs: any}[]}}
 */
export function mergeCatalogs(base, mine, theirs) {
  const conflicts = [];
  const merged = clone(theirs);
  for (const key of META_KEYS) {
    const mineChanged = !deepEqual(base?.[key], mine?.[key]);
    const theirsChanged = !deepEqual(base?.[key], theirs?.[key]);
    if (mineChanged && !theirsChanged) merged[key] = clone(mine[key]);
    else if (mineChanged && theirsChanged && !deepEqual(mine?.[key], theirs?.[key])) {
      conflicts.push({ kind: "setting", id: key, mine: mine?.[key] ?? null, theirs: theirs?.[key] ?? null });
    }
  }
  merged.categories = clone(mergeList(base?.categories, mine?.categories, theirs?.categories, "category", conflicts));
  merged.apps = clone(mergeList(base?.apps, mine?.apps, theirs?.apps, "app", conflicts));
  return { merged, conflicts };
}

/** Apps that use a category (by id). */
export function appsInCategory(catalog, categoryId) {
  return (catalog?.apps ?? []).filter((app) => {
    const cats = Array.isArray(app.categories) && app.categories.length ? app.categories : [DEFAULT_CATEGORY];
    return cats.includes(categoryId);
  });
}

/** Removes a category and moves its apps to "other" when they would be left with none. */
export function removeCategory(catalog, categoryId) {
  if (categoryId === DEFAULT_CATEGORY) throw new Error(`The default category "${DEFAULT_CATEGORY}" cannot be removed.`);
  const next = clone(catalog);
  next.categories = next.categories.filter((c) => c.id !== categoryId);
  for (const app of next.apps) {
    if (!Array.isArray(app.categories)) continue;
    app.categories = app.categories.filter((c) => c !== categoryId);
    if (!app.categories.length) app.categories = [DEFAULT_CATEGORY];
  }
  return next;
}

/** Drops empty strings, empty lists, empty objects and undefined from an app before it is saved. */
export function pruneEmpty(value) {
  if (Array.isArray(value)) {
    return value.map(pruneEmpty).filter((v) => v !== undefined && !(typeof v === "string" && !v.trim()));
  }
  if (value && typeof value === "object") {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      const p = pruneEmpty(v);
      if (p === undefined) continue;
      if (typeof p === "string" && !p.trim()) continue;
      if (Array.isArray(p) && !p.length) continue;
      if (p && typeof p === "object" && !Array.isArray(p) && !Object.keys(p).length) continue;
      out[k] = p;
    }
    return out;
  }
  return value;
}
