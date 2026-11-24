// Previews of a link entry: what its repository says about itself, fetched without the token.

import { anonymousJson } from "./github.js";
import { REPOSITORY_PATTERN, validateAppManifest, normalizeLocalized, isHttpsUrl } from "./schema.js";

function rawUrl(repo, path) {
  return `https://raw.githubusercontent.com/${repo}/HEAD/${path.split("/").map(encodeURIComponent).join("/")}`;
}

function assetUrl(value, repo) {
  if (typeof value !== "string" || !value.trim()) return null;
  if (/^[a-z]+:/i.test(value)) return isHttpsUrl(value) ? value : null;
  const path = value.trim().replace(/^(\.\/)+/, "");
  if (!path || path.startsWith("/") || path.split("/").includes("..")) return null;
  return rawUrl(repo, path);
}

/**
 * Looks up a repository the way the Catalog workflow will:
 * app-manager.json (or .github/app-manager.json), the repository itself, and its newest release.
 * @returns {Promise<{repo, manifest, manifestPath, validation, info, release, apks, resolved, problems}>}
 */
export async function previewRepository(repo, overrides = {}) {
  if (!REPOSITORY_PATTERN.test(repo)) throw new Error('Enter the repository as "owner/name".');
  const problems = [];
  let manifest = null;
  let manifestPath = null;
  for (const path of ["app-manager.json", ".github/app-manager.json"]) {
    try {
      const response = await fetch(rawUrl(repo, path), { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" });
      if (response.ok) {
        manifestPath = path;
        try {
          manifest = JSON.parse(await response.text());
        } catch (e) {
          problems.push(`${path} is not valid JSON: ${e.message}`);
        }
        break;
      }
    } catch {
      problems.push("raw.githubusercontent.com could not be reached");
      break;
    }
  }
  const validation = manifest ? validateAppManifest(manifest) : null;
  if (!manifestPath) problems.push("No app-manager.json in this repository: the repository name and description are used.");

  let info = null;
  try {
    info = await anonymousJson(`https://api.github.com/repos/${repo}`);
    if (!info) problems.push("The repository was not found or is private (the app can only download public releases).");
  } catch (e) {
    problems.push(`Repository details: ${e.message}`);
  }

  const releasesRepo = overrides.release?.repository || manifest?.releases?.repository || repo;
  let release = null;
  try {
    release = await anonymousJson(`https://api.github.com/repos/${releasesRepo}/releases/latest`);
    if (!release) problems.push(`${releasesRepo} has no published release yet: the app is listed without an Install button.`);
  } catch (e) {
    problems.push(`Latest release: ${e.message}`);
  }
  let assetPattern = /\.apk$/i;
  try {
    const p = overrides.release?.asset || manifest?.releases?.asset;
    if (p) assetPattern = new RegExp(p, "i");
  } catch {
    /* validated elsewhere */
  }
  const apks = (release?.assets ?? []).filter((a) => assetPattern.test(a.name)).map((a) => ({ name: a.name, size: a.size, url: a.browser_download_url }));
  if (release && !apks.length) problems.push(`The latest release (${release.tag_name}) has no matching APK file.`);

  const usable = validation?.ok ? manifest : null;
  const resolved = {
    name: overrides.name || usable?.name || info?.name || repo.split("/")[1],
    by: overrides.by || usable?.by || info?.owner?.login || repo.split("/")[0],
    tagline: normalizeLocalized(overrides.tagline) || normalizeLocalized(usable?.tagline) || normalizeLocalized(info?.description),
    description: normalizeLocalized(overrides.description) || normalizeLocalized(usable?.description),
    icon: overrides.icon || assetUrl(usable?.icon, repo),
    screenshots: overrides.screenshots?.length ? overrides.screenshots : (usable?.screenshots ?? []).map((s) => assetUrl(s, repo)).filter(Boolean),
    version: release?.tag_name ?? null,
    publishedAt: release?.published_at ?? null,
  };
  return { repo, manifest, manifestPath, validation, info, release, apks, resolved, problems };
}
