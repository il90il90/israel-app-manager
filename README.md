# Israel App Manager

One place to download, install and update all of Israel's Android apps.

**[Download the latest APK](https://github.com/il90il90/israel-app-manager/releases/latest/download/IsraelAppManager.apk)** ·
[All releases](https://github.com/il90il90/israel-app-manager/releases)

Android 8.0 or newer, on phones, tablets, foldables, Chromebooks and Android TV / Google TV
(driven by the remote). English and Hebrew.

## What it does

- Shows every app with its icon, description, version, size and release notes.
- Installs an app in one tap, with download progress and a checksum check before installing.
- Never installs silently: every install and update, the manager's own included, is confirmed in
  Android's own dialog.
- Knows what is installed, shows which apps have a new version, and updates one or all of them.
- Picks the right build for the device (CPU, Android version, phone / TV / watch). Any app
  installs on any phone, tablet or TV: when an app has no build for this kind of device, the
  build made for another one is installed, and the app's page says so. Watch builds are offered
  only to watches, and as plain APK downloads everywhere else.
- Works with a TV remote, a keyboard or a D-pad: a clear focus ring, focus that starts on the
  first app and comes back to the app you opened, and safe margins for TV screens.
- Checks for new versions in the background and sends one notification per set of updates.
- Updates itself the same way it updates the other apps.

Nothing in the app is hard-coded: the list of apps is a catalog the owner manages from a web
dashboard, so adding an app or publishing a new version of one needs no new version of the manager.

## The apps

Touchless · SeretGram · Wallify · TrustAPK · OneDial · Gallery Wall · WatchIPTV · ScoreUp ·
Touchless Bridge · Screen Awake · TouchKey · Zoopli · אבג ABC · Israel TV

## Catalog Management

**[Open the Catalog Dashboard](https://il90il90.github.io/israel-app-manager/)** ·
**[Catalog management guide](docs/CATALOG_MANAGEMENT.md)** ·
[Guide for app developers](docs/DEVELOPER_GUIDE.md)

- The catalog is one file, [`catalog/catalog.json`](catalog/catalog.json) (`schemaVersion` 2), and
  only the repository owner can change it. Each app is either a **link** entry (a GitHub repository
  whose [`app-manager.json`](docs/DEVELOPER_GUIDE.md#app-managerjson) and releases are read
  automatically) or a **custom** entry (written by hand, with a GitHub, direct-APK or external-JSON
  release). Apps can be in several categories from a central category registry.
- The **Catalog Dashboard** is a static site on GitHub Pages (no server). The owner signs in with a
  fine-grained personal access token limited to this repository, edits apps and categories, and
  publishes. See the [guide](docs/CATALOG_MANAGEMENT.md) for setup, token creation, publishing,
  rollback and troubleshooting.
- The **Catalog** workflow validates every change and only then publishes the resolved catalog as
  the `catalog.json` asset of the GitHub Release tagged **`catalog`**. That release is separate
  from the app's own releases and is never marked "latest". If validation or the build fails, the
  last good catalog stays published.
- The app downloads
  `https://github.com/il90il90/israel-app-manager/releases/download/catalog/catalog.json`,
  validates it, and keeps the last good copy for offline use.

## How it works

| Where | What is there |
|---|---|
| `main` | The Android app (`app/`), the catalog (`catalog/catalog.json`), the dashboard (`dashboard/`), the tools and docs. |
| Release `catalog` | The published `catalog.json`, `catalog-previous.json` (for rollback) and app icons. Written by the **Catalog** workflow. |
| `catalog` branch | A copy of the published `catalog.json` for app versions up to 1.1.0. |
| `release-inbox` | A signed build of the manager waiting to be published (see Releasing). |
| GitHub Pages | The Catalog Dashboard, deployed by the **Dashboard** workflow from `dashboard/`. |

The **Catalog** workflow resolves every app (its `app-manager.json`, its newest release), downloads
new APKs once to read their version, size, checksum and icon, checks that Android accepts their
signature, and publishes the result. It runs when `catalog/catalog.json` changes, on a schedule,
when an app's release workflow sends the `app-released` event, and by hand (which can also roll
back).

**The app does not wait for it.** GitHub runs schedules when it has room, and hours can pass before
the catalog notices a release. So the app also asks each app's releases page itself
(`github.com/<repo>/releases/latest`, an ordinary page with no API limit) every time the list is
loaded, and once more at the moment Install or Update is tapped. A release the catalog does not
describe yet is offered straight from GitHub, with the size and SHA-256 GitHub reports for the
file; the catalog's entry supplies what a file name cannot say (package, CPU, minimum Android).
What gets installed is always the newest release.

### Adding an app

Use the [dashboard](https://il90il90.github.io/israel-app-manager/): **Add app**, choose **Link**
(a GitHub repository) or **Custom**, and publish. Editing `catalog/catalog.json` by hand works too;
the same validation runs before anything is published. Third-party developers prepare their
repository with the [developer guide](docs/DEVELOPER_GUIDE.md) and send the link for approval.

How the catalog was migrated from the earlier `apps.json`: [docs/CATALOG_MIGRATION.md](docs/CATALOG_MIGRATION.md).
Catalog tools: [tools/catalog/README.md](tools/catalog/README.md).

## Google Play Protect

The manager requests no high-risk permissions (no `QUERY_ALL_PACKAGES`, accessibility, SMS or
overlay), installs nothing without Android's own confirmation, and its code is not obfuscated.
If Play Protect still warns about it, see [docs/PLAY_PROTECT.md](docs/PLAY_PROTECT.md) for the
appeal and developer verification steps.

## Building

```bash
./gradlew :app:assembleDebug          # debug build, installs next to the released app
./gradlew :app:testDebugUnitTest :app:lintDebug

npm run validate                      # validate catalog/catalog.json
npm test                              # dashboard and validator unit tests (Node 22)
python3 -m pytest tests/catalog       # catalog build and publish tools
npm run test:e2e                      # dashboard end-to-end test (needs Playwright)
```

## Releasing

Releases are tagged `v<VERSION_NAME>` and every one must be signed with the same key, or Android
will not install it over the previous version. The key is not in this repository.

1. Bump `VERSION_NAME` and `VERSION_CODE` in `version.properties`, commit and push to `main`.
2. Publish, one of two ways:
   - **From a machine that has the key**: `tools/release/make_release.sh <keys-dir>` builds and
     signs the APK and pushes it to `release-inbox`; the **Publish release** workflow checks the
     signature and creates the GitHub Release.
   - **In CI**: add the repository secrets `IAM_KEYSTORE_BASE64` and `IAM_KEYSTORE_PASSWORD`.
     From then on the **Release** workflow builds, signs and publishes on every version bump.

Either way the catalog is rebuilt afterwards and installed copies offer the update.

## Contact & Support

Questions, support, licenses for premium apps and app submissions: Telegram
**[@YisraelCohen](https://t.me/YisraelCohen)** (<https://t.me/YisraelCohen>).
