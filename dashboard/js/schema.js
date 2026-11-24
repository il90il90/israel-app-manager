// Catalog schema and validation for Israel App Manager.
//
// This module is the single source of truth for what a valid catalog/catalog.json looks like.
// It runs in two places:
//   * in the browser, inside the admin dashboard (before anything is committed);
//   * in GitHub Actions, through tools/catalog/validate_catalog.mjs (before anything is published).
// It has no dependencies and touches no DOM or network, so both can import it as-is.

export const SCHEMA_VERSION = 2;
export const APP_TYPES = Object.freeze(["link", "custom"]);
export const RELEASE_STRATEGIES = Object.freeze(["github-latest", "direct-apk", "external-json"]);
export const DEFAULT_CATEGORY = "other";
export const APP_MANAGER_SPEC_VERSION = 1;

export const ID_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;
export const REPOSITORY_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})\/[A-Za-z0-9._-]{1,100}$/;
export const LANGUAGE_PATTERN = /^[a-z]{2,3}(?:-[A-Za-z]{2,4})?$/;
export const COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/;
export const SHA256_PATTERN = /^[0-9a-f]{64}$/;

export const LIMITS = Object.freeze({
  name: 80,
  by: 80,
  tagline: 160,
  description: 4000,
  screenshots: 10,
  categoriesPerApp: 5,
  apps: 500,
  categories: 100,
  url: 2048,
});

const TOP_LEVEL_KEYS = new Set(["$schema", "schemaVersion", "developer", "contact", "announcement", "categories", "apps"]);
const COMMON_APP_KEYS = [
  "id", "type", "name", "by", "categories", "tagline", "description", "icon", "screenshots",
  "website", "storeUrl", "repoUrl", "featured", "hidden", "license", "self", "accent", "iconStyle", "release",
];
const APP_KEYS = {
  link: new Set([...COMMON_APP_KEYS, "repository"]),
  custom: new Set(COMMON_APP_KEYS),
};
const RELEASE_KEYS = {
  "github-latest": new Set(["strategy", "repository", "tag", "asset", "exclude", "prerelease"]),
  "direct-apk": new Set(["strategy", "url", "versionName"]),
  "external-json": new Set(["strategy", "url"]),
};
const LINK_RELEASE_KEYS = new Set(["strategy", "repository", "tag", "asset", "exclude", "prerelease"]);

const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const isNonEmptyString = (v) => typeof v === "string" && v.trim().length > 0;

/** True for an absolute https URL with a host and no credentials. */
export function isHttpsUrl(value) {
  if (typeof value !== "string" || value.length > LIMITS.url) return false;
  let url;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return url.protocol === "https:" && url.hostname.length > 0 && !url.username && !url.password;
}

/** True when a pattern compiles as a regular expression (JavaScript dialect, compatible subset). */
export function isValidPattern(value) {
  if (typeof value !== "string" || value.length > 300) return false;
  // Lookbehind and named groups differ between JavaScript, Python and Kotlin: keep patterns portable.
  if (/\(\?<[=!]|\(\?<[A-Za-z]|\(\?P/.test(value)) return false;
  try {
    new RegExp(value);
    return true;
  } catch {
    return false;
  }
}

/** A localized text is either a plain string (English) or {"en": "...", "he": "..."}. */
export function normalizeLocalized(value) {
  if (value === undefined || value === null) return null;
  if (typeof value === "string") return value.trim() ? { en: value } : null;
  if (!isObject(value)) return null;
  const out = {};
  for (const [lang, text] of Object.entries(value)) {
    if (typeof text === "string" && text.trim()) out[lang] = text;
  }
  return Object.keys(out).length ? out : null;
}

/** Picks the text for a language, falling back to English, then to anything. */
export function localize(value, language = "en") {
  const map = normalizeLocalized(value);
  if (!map) return "";
  return map[language] ?? map.en ?? Object.values(map)[0] ?? "";
}

class Report {
  constructor() {
    this.errors = [];
    this.warnings = [];
  }
  error(path, message) {
    this.errors.push({ path, message });
  }
  warn(path, message) {
    this.warnings.push({ path, message });
  }
  get ok() {
    return this.errors.length === 0;
  }
}

function checkLocalized(report, path, value, max, { required = false } = {}) {
  if (value === undefined || value === null) {
    if (required) report.error(path, "is required");
    return;
  }
  if (typeof value === "string") {
    if (!value.trim()) report.error(path, "must not be empty");
    else if (value.length > max) report.error(path, `must be at most ${max} characters`);
    return;
  }
  if (!isObject(value)) {
    report.error(path, 'must be a string or an object such as {"en": "...", "he": "..."}');
    return;
  }
  const langs = Object.keys(value);
  if (!langs.length) {
    report.error(path, "must contain at least one language");
    return;
  }
  for (const lang of langs) {
    if (!LANGUAGE_PATTERN.test(lang)) report.error(`${path}.${lang}`, "is not a language code (use en, he, ...)");
    const text = value[lang];
    if (typeof text !== "string" || !text.trim()) report.error(`${path}.${lang}`, "must be a non-empty string");
    else if (text.length > max) report.error(`${path}.${lang}`, `must be at most ${max} characters`);
  }
  if (!("en" in value)) report.warn(path, "has no English (en) text; English is the fallback language");
}

function checkUrl(report, path, value, { required = false } = {}) {
  if (value === undefined || value === null || value === "") {
    if (required) report.error(path, "is required");
    return;
  }
  if (!isHttpsUrl(value)) report.error(path, "must be an https:// URL");
}

function checkPattern(report, path, value) {
  if (value === undefined || value === null || value === "") return;
  if (!isValidPattern(value)) report.error(path, "is not a valid, portable regular expression");
}

function checkUnknownKeys(report, path, object, allowed) {
  for (const key of Object.keys(object)) {
    if (!allowed.has(key)) report.warn(`${path}.${key}`, "is not a known field and will be ignored");
  }
}

function checkRelease(report, path, release, type) {
  if (release === undefined || release === null) return;
  if (!isObject(release)) {
    report.error(path, "must be an object");
    return;
  }
  if (type === "link") {
    if (release.strategy !== undefined && release.strategy !== "github-latest") {
      report.error(`${path}.strategy`, 'can only be "github-latest" for a link entry');
    }
    if (release.repository !== undefined && !REPOSITORY_PATTERN.test(String(release.repository))) {
      report.error(`${path}.repository`, 'must look like "owner/repository"');
    }
    checkPattern(report, `${path}.tag`, release.tag);
    checkPattern(report, `${path}.asset`, release.asset);
    checkPattern(report, `${path}.exclude`, release.exclude);
    if (release.prerelease !== undefined && typeof release.prerelease !== "boolean") report.error(`${path}.prerelease`, "must be true or false");
    checkUnknownKeys(report, path, release, LINK_RELEASE_KEYS);
    return;
  }
  const strategy = release.strategy;
  if (!RELEASE_STRATEGIES.includes(strategy)) {
    report.error(`${path}.strategy`, `must be one of: ${RELEASE_STRATEGIES.join(", ")}`);
    return;
  }
  if (strategy === "github-latest") {
    if (!REPOSITORY_PATTERN.test(String(release.repository ?? ""))) report.error(`${path}.repository`, 'is required and must look like "owner/repository"');
    checkPattern(report, `${path}.tag`, release.tag);
    checkPattern(report, `${path}.asset`, release.asset);
    checkPattern(report, `${path}.exclude`, release.exclude);
    if (release.prerelease !== undefined && typeof release.prerelease !== "boolean") report.error(`${path}.prerelease`, "must be true or false");
  } else if (strategy === "direct-apk") {
    checkUrl(report, `${path}.url`, release.url, { required: true });
    if (isHttpsUrl(release.url) && !/\.apk(?:$|[?#])/i.test(release.url)) report.warn(`${path}.url`, "does not end in .apk; make sure it downloads an APK file");
    if (release.versionName !== undefined && typeof release.versionName !== "string") report.error(`${path}.versionName`, "must be a string");
  } else if (strategy === "external-json") {
    checkUrl(report, `${path}.url`, release.url, { required: true });
  }
  checkUnknownKeys(report, path, release, RELEASE_KEYS[strategy]);
}

function checkApp(report, path, app, categoryIds, seenIds) {
  if (!isObject(app)) {
    report.error(path, "must be an object");
    return;
  }
  const id = app.id;
  if (!isNonEmptyString(id)) report.error(`${path}.id`, "is required");
  else if (!ID_PATTERN.test(id)) report.error(`${path}.id`, "must use lowercase letters, digits and dashes (e.g. my-app), up to 64 characters");
  else if (seenIds.has(id)) report.error(`${path}.id`, `"${id}" is used by more than one app`);
  else seenIds.add(id);

  if (!APP_TYPES.includes(app.type)) {
    report.error(`${path}.type`, 'is required and must be "link" or "custom"');
    return;
  }
  if (app.type === "link") {
    if (!REPOSITORY_PATTERN.test(String(app.repository ?? ""))) report.error(`${path}.repository`, 'is required for a link entry and must look like "owner/repository"');
    if (app.name !== undefined) checkLocalizedName(report, `${path}.name`, app.name);
  } else {
    if (!isNonEmptyString(app.name)) report.error(`${path}.name`, "is required for a custom entry");
    else checkLocalizedName(report, `${path}.name`, app.name);
    if (app.release === undefined || app.release === null) report.warn(`${path}.release`, "is missing: the app is listed but cannot be installed");
  }

  if (app.by !== undefined && (typeof app.by !== "string" || app.by.length > LIMITS.by)) report.error(`${path}.by`, `must be a string of at most ${LIMITS.by} characters`);

  if (app.categories === undefined) {
    report.warn(`${path}.categories`, `is missing; the app will be listed under "${DEFAULT_CATEGORY}"`);
  } else if (!Array.isArray(app.categories)) {
    report.error(`${path}.categories`, "must be a list of category ids");
  } else {
    if (!app.categories.length) report.warn(`${path}.categories`, `is empty; the app will be listed under "${DEFAULT_CATEGORY}"`);
    if (app.categories.length > LIMITS.categoriesPerApp) report.error(`${path}.categories`, `may hold at most ${LIMITS.categoriesPerApp} categories`);
    const seen = new Set();
    app.categories.forEach((category, i) => {
      if (typeof category !== "string" || !categoryIds.has(category)) report.error(`${path}.categories[${i}]`, `"${category}" is not in the category registry`);
      else if (seen.has(category)) report.error(`${path}.categories[${i}]`, `"${category}" is listed twice`);
      seen.add(category);
    });
  }

  checkLocalized(report, `${path}.tagline`, app.tagline, LIMITS.tagline);
  checkLocalized(report, `${path}.description`, app.description, LIMITS.description);
  checkUrl(report, `${path}.icon`, app.icon);
  checkUrl(report, `${path}.website`, app.website);
  checkUrl(report, `${path}.storeUrl`, app.storeUrl);
  checkUrl(report, `${path}.repoUrl`, app.repoUrl);
  if (app.screenshots !== undefined) {
    if (!Array.isArray(app.screenshots)) report.error(`${path}.screenshots`, "must be a list of https:// URLs");
    else {
      if (app.screenshots.length > LIMITS.screenshots) report.error(`${path}.screenshots`, `may hold at most ${LIMITS.screenshots} images`);
      app.screenshots.forEach((url, i) => checkUrl(report, `${path}.screenshots[${i}]`, url, { required: true }));
    }
  }
  for (const flag of ["featured", "hidden", "self"]) {
    if (app[flag] !== undefined && typeof app[flag] !== "boolean") report.error(`${path}.${flag}`, "must be true or false");
  }
  if (app.license !== undefined && typeof app.license !== "boolean") {
    if (!isObject(app.license)) report.error(`${path}.license`, 'must be true, false or {"contact": "https://t.me/..."}');
    else checkUrl(report, `${path}.license.contact`, app.license.contact);
  }
  if (app.accent !== undefined && app.accent !== null && !COLOR_PATTERN.test(String(app.accent))) report.error(`${path}.accent`, "must be a colour such as #1A73E8");
  if (app.iconStyle !== undefined && typeof app.iconStyle !== "string") report.error(`${path}.iconStyle`, "must be a string");
  checkRelease(report, `${path}.release`, app.release, app.type);
  checkUnknownKeys(report, path, app, APP_KEYS[app.type]);
}

function checkLocalizedName(report, path, name) {
  if (typeof name !== "string" || !name.trim()) report.error(path, "must be a non-empty string");
  else if (name.length > LIMITS.name) report.error(path, `must be at most ${LIMITS.name} characters`);
}

/**
 * Validates a catalog (catalog/catalog.json).
 * @returns {{ok: boolean, errors: {path: string, message: string}[], warnings: {path: string, message: string}[]}}
 */
export function validateCatalog(catalog) {
  const report = new Report();
  if (!isObject(catalog)) {
    report.error("$", "the catalog must be a JSON object");
    return { ok: false, errors: report.errors, warnings: report.warnings };
  }
  if (catalog.schemaVersion !== SCHEMA_VERSION) {
    report.error("schemaVersion", `must be ${SCHEMA_VERSION} (found ${JSON.stringify(catalog.schemaVersion)})`);
  }
  if (catalog.developer !== undefined && typeof catalog.developer !== "string") report.error("developer", "must be a string");
  if (catalog.contact !== undefined && catalog.contact !== null) {
    if (!isObject(catalog.contact)) report.error("contact", "must be an object");
    else checkUrl(report, "contact.telegram", catalog.contact.telegram);
  }
  if (catalog.announcement !== undefined && catalog.announcement !== null) {
    const a = catalog.announcement;
    if (!isObject(a)) report.error("announcement", "must be an object or null");
    else {
      if (!isNonEmptyString(a.id)) report.error("announcement.id", "is required (change it to show the announcement again)");
      checkLocalized(report, "announcement.title", a.title, 120, { required: true });
      checkLocalized(report, "announcement.body", a.body, 1000);
      checkUrl(report, "announcement.url", a.url);
    }
  }

  const categoryIds = new Set();
  if (!Array.isArray(catalog.categories)) {
    report.error("categories", "is required and must be a list");
  } else {
    if (catalog.categories.length > LIMITS.categories) report.error("categories", `may hold at most ${LIMITS.categories} categories`);
    catalog.categories.forEach((category, i) => {
      const path = `categories[${i}]`;
      if (!isObject(category)) {
        report.error(path, "must be an object");
        return;
      }
      if (!isNonEmptyString(category.id) || !ID_PATTERN.test(category.id)) report.error(`${path}.id`, "must use lowercase letters, digits and dashes");
      else if (categoryIds.has(category.id)) report.error(`${path}.id`, `"${category.id}" is defined twice`);
      else categoryIds.add(category.id);
      checkLocalized(report, `${path}.name`, category.name, 40, { required: true });
    });
    if (!categoryIds.has(DEFAULT_CATEGORY)) report.error("categories", `must include the default category "${DEFAULT_CATEGORY}"`);
  }

  if (!Array.isArray(catalog.apps)) {
    report.error("apps", "is required and must be a list");
  } else {
    if (catalog.apps.length > LIMITS.apps) report.error("apps", `may hold at most ${LIMITS.apps} apps`);
    if (!catalog.apps.length) report.warn("apps", "is empty: the app will show an empty list");
    const seenIds = new Set();
    catalog.apps.forEach((app, i) => checkApp(report, `apps[${i}]`, app, categoryIds, seenIds));
    const selfApps = catalog.apps.filter((a) => isObject(a) && a.self === true);
    if (selfApps.length > 1) report.error("apps", 'only one app can be marked "self" (Israel App Manager itself)');
    const repos = new Map();
    catalog.apps.forEach((app, i) => {
      if (!isObject(app)) return;
      const repo = (app.type === "link" ? app.release?.repository ?? app.repository : app.release?.strategy === "github-latest" ? app.release.repository : null);
      if (!repo) return;
      const key = `${String(repo).toLowerCase()}|${app.release?.tag ?? ""}|${app.release?.asset ?? ""}`;
      if (repos.has(key)) report.warn(`apps[${i}]`, `uses the same releases as apps[${repos.get(key)}]`);
      else repos.set(key, i);
    });
  }
  checkUnknownKeys(report, "$", catalog, TOP_LEVEL_KEYS);
  return { ok: report.ok, errors: report.errors, warnings: report.warnings };
}

const MANIFEST_KEYS = new Set([
  "$schema", "specVersion", "id", "name", "by", "developerUrl", "tagline", "description", "icon", "screenshots",
  "categories", "website", "license", "android", "releases",
]);

/**
 * Validates a developer's app-manager.json (see docs/DEVELOPER_GUIDE.md).
 * Paths inside a repository (icon, screenshots) are allowed as well as https URLs.
 */
export function validateAppManifest(manifest) {
  const report = new Report();
  if (!isObject(manifest)) {
    report.error("$", "app-manager.json must be a JSON object");
    return { ok: false, errors: report.errors, warnings: report.warnings };
  }
  if (manifest.specVersion !== APP_MANAGER_SPEC_VERSION) {
    report.error("specVersion", `must be ${APP_MANAGER_SPEC_VERSION} (found ${JSON.stringify(manifest.specVersion)})`);
  }
  if (!isNonEmptyString(manifest.name)) report.error("name", "is required");
  else if (manifest.name.length > LIMITS.name) report.error("name", `must be at most ${LIMITS.name} characters`);
  if (!isNonEmptyString(manifest.by)) report.warn("by", "is missing: the app will be credited to its GitHub owner");
  else if (manifest.by.length > LIMITS.by) report.error("by", `must be at most ${LIMITS.by} characters`);
  if (manifest.id !== undefined && !ID_PATTERN.test(String(manifest.id))) report.error("id", "must use lowercase letters, digits and dashes");
  checkLocalized(report, "tagline", manifest.tagline, LIMITS.tagline);
  checkLocalized(report, "description", manifest.description, LIMITS.description);
  checkUrl(report, "developerUrl", manifest.developerUrl);
  checkUrl(report, "website", manifest.website);
  const checkAsset = (path, value) => {
    if (value === undefined || value === null) return;
    if (typeof value !== "string" || !value.trim()) report.error(path, "must be a path in the repository or an https:// URL");
    else if (/^[a-z]+:/i.test(value) && !isHttpsUrl(value)) report.error(path, "must be a path in the repository or an https:// URL");
    else if (!/^[a-z]+:/i.test(value) && (value.includes("..") || value.startsWith("/"))) report.error(path, "must be a relative path inside the repository");
    else if (!/^[a-z]+:/i.test(value) && !/\.(png|jpe?g|webp)$/i.test(value)) report.warn(path, "should be a PNG, JPEG or WebP image");
  };
  checkAsset("icon", manifest.icon);
  if (manifest.screenshots !== undefined) {
    if (!Array.isArray(manifest.screenshots)) report.error("screenshots", "must be a list");
    else {
      if (manifest.screenshots.length > LIMITS.screenshots) report.error("screenshots", `may hold at most ${LIMITS.screenshots} images`);
      manifest.screenshots.forEach((s, i) => checkAsset(`screenshots[${i}]`, s));
    }
  }
  if (manifest.categories !== undefined && (!Array.isArray(manifest.categories) || manifest.categories.some((c) => typeof c !== "string"))) {
    report.error("categories", "must be a list of category ids (suggestions; the catalog owner decides)");
  }
  if (manifest.license !== undefined && !["free", "premium"].includes(manifest.license)) report.error("license", 'must be "free" or "premium"');
  if (manifest.android !== undefined) {
    const a = manifest.android;
    if (!isObject(a)) report.error("android", "must be an object");
    else {
      if (a.packageName !== undefined && !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(String(a.packageName))) report.error("android.packageName", "is not a valid Android package name");
      if (a.minSdk !== undefined && !(Number.isInteger(a.minSdk) && a.minSdk >= 1 && a.minSdk <= 100)) report.error("android.minSdk", "must be an API level such as 26");
    }
  } else {
    report.warn("android.packageName", "is missing: declaring it lets the catalog check that the APK really is your app");
  }
  if (manifest.releases !== undefined) {
    const r = manifest.releases;
    if (!isObject(r)) report.error("releases", "must be an object");
    else {
      if (r.repository !== undefined && !REPOSITORY_PATTERN.test(String(r.repository))) report.error("releases.repository", 'must look like "owner/repository"');
      checkPattern(report, "releases.tag", r.tag);
      checkPattern(report, "releases.asset", r.asset);
      checkPattern(report, "releases.exclude", r.exclude);
    }
  }
  checkUnknownKeys(report, "$", manifest, MANIFEST_KEYS);
  return { ok: report.ok, errors: report.errors, warnings: report.warnings };
}

/** A new, empty catalog with the default category. */
export function emptyCatalog() {
  return {
    schemaVersion: SCHEMA_VERSION,
    developer: "",
    contact: {},
    announcement: null,
    categories: [{ id: DEFAULT_CATEGORY, name: { en: "Other" } }],
    apps: [],
  };
}
