# Catalog migration: apps.json (v1) to catalog.json (v2)

This records what the repository looked like before the dynamic catalog (audit of commit
`bda36bc`) and where every piece went.

## Before

- **Source list:** `catalog/apps.json` on `main`: 15 apps, each with `id`, `name`, one
  `category`, English and Hebrew `tagline` / `description`, optional `license`, `featured`,
  `self`, `website`, and one `sources` entry (`repo`, `tag`, `asset` regular expressions).
  Top level: `developer: "Israel"`, `contact.telegram: https://t.me/Israelcohen`,
  `announcement: null`. No entry used `mirror` sources.
- **Build:** the Catalog workflow ran `tools/catalog/build_catalog.py build` (schedule every
  10 minutes, `app-released` dispatch, pushes to `catalog/**`), resolved each app's newest GitHub
  Release, inspected APKs with aapt2/apksigner, rendered icons, and committed `catalog.json` +
  `icons/` to the **`catalog` branch**.
- **Android app (1.1.0):** downloaded
  `raw.githubusercontent.com/il90il90/israel-app-manager/catalog/catalog.json` (jsDelivr as
  fallback), cached it in `filesDir/catalog.json`, and overlaid releases found live on GitHub
  (`LiveReleases`). Category names were hard-coded (9 ids in `categoryLabel`).
- **App releases** (`v*` tags, `IsraelAppManager.apk`, marked latest) came from `release.yml` /
  `publish-inbox.yml` and were unrelated to the catalog except for triggering a rebuild.

## Field mapping

| apps.json (v1) | catalog.json (v2) | Notes |
|---|---|---|
| top-level `developer` | `developer` | unchanged ("Israel") |
| `contact.telegram` | `contact.telegram` | now the official `https://t.me/YisraelCohen` |
| `announcement` | `announcement` | unchanged |
| (hard-coded in the app) | `categories[]` | central registry with English and Hebrew names; `other` added as the default |
| `id` | `id` | unchanged: every app keeps its ID, so installed apps and settings are unaffected |
| (none) | `type` | every migrated app is `custom` (their release repositories have no `app-manager.json` yet) |
| `name` | `name` | unchanged |
| (top-level developer) | `by` | `"Israel"` on every app; all 15 are published from `il90il90` repositories |
| `category` | `categories: [category]` | multi-category list; the published catalog also keeps `category` (first item) for app 1.1.0 |
| `tagline`, `description` | same | unchanged, English and Hebrew |
| `license: true` | `license: true` | unchanged (Touchless, SeretGram, TrustAPK, OneDial, Israel TV) |
| `featured`, `self`, `website` | same | unchanged |
| `store` | `storeUrl` | (no app used it) |
| `sources[0] = {repo, tag, asset}` | `release = {strategy: "github-latest", repository, tag, asset}` | same patterns, same behaviour |
| `sources[].mirror` | (removed) | unused by every app; the `apks` branch mirror and its secrets are no longer read |
| `catalog/icons/<id>.png` override | unchanged | `touchkey.png` still overrides the APK icon |

## Published output

| Before | After |
|---|---|
| `catalog` branch: `catalog.json` + `icons/<id>.png` | `catalog` **release** assets: `catalog.json`, `catalog-previous.json`, `icon-<id>-<hash>.png` |
| `schema: 1`, `baseUrl` = raw branch URL | `schemaVersion: 2` (and `schema: 2`), `baseUrl` = release download URL, `categories`, per-app `type`, `categories`, `screenshots` |
| written on every successful build | written only after validation; failures keep the last good file and are reported |

The `catalog` branch keeps receiving an identical copy of `catalog.json` so app versions up to
1.1.0 keep working; their icon URLs (baseUrl + icon) resolve to the release assets.

## The 15 migrated apps

| ID | Name | Categories | Releases repository |
|---|---|---|---|
| touchless | Touchless | ai | il90il90/touchless-releases |
| seretgram | SeretGram | video | il90il90/SeretGram-releases |
| wallify | Wallify | personalization | il90il90/Wallify-releases |
| trustapk | TrustAPK | devtools | il90il90/trust-apk-releases |
| onedial | OneDial | communication | il90il90/OneDial-releases |
| gallerywall | Gallery Wall | photos | il90il90/GalleryWall-releases |
| watchiptv | WatchIPTV | video | il90il90/WatchIPTV-releases |
| scoreup | ScoreUp | sports | il90il90/ScoreUp |
| touchless-bridge | Touchless Bridge | ai | il90il90/touchless-hackerai-bridge |
| screenawake | Screen Awake | tools | il90il90/ScreenAwake-releases |
| touchkey | TouchKey | tools | il90il90/touchkey-releases |
| zoopli | Zoopli | kids | il90il90/Zoopli-releases |
| abc | אבג ABC | kids | il90il90/abc-app-releases |
| israel-tv | Israel TV | video | il90il90/hass-israel-tv-release |
| israel-app-manager | Israel App Manager (self) | tools | il90il90/israel-app-manager |

`tests/catalog/test_source_catalog.py::test_the_shipped_catalog_migrated_every_app` checks this
list, the credit and the categories.

## Converting an app to a link entry later

When one of these repositories gains an `app-manager.json` (see the
[developer guide](DEVELOPER_GUIDE.md)), open the app in the dashboard, switch it to **Link**, enter
the repository and fetch the preview. Anything left filled in the override fields still wins.
