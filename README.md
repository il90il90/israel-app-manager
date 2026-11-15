# Israel App Manager

One place to download, install and update all of Israel's Android apps.

**[Download the latest APK](https://github.com/il90il90/israel-app-manager/releases/latest/download/IsraelAppManager.apk)** ·
[All releases](https://github.com/il90il90/israel-app-manager/releases)

Android 8.0 or newer. English and Hebrew.

## What it does

- Shows every app with its icon, description, version, size and release notes.
- Installs an app in one tap, with download progress and a checksum check before installing.
- Knows what is installed, shows which apps have a new version, and updates one or all of them.
- Picks the right build for the device (CPU, Android version, phone / TV / watch) and offers the
  TV and watch builds as plain APK downloads.
- Checks for new versions in the background and sends one notification per set of updates.
- Updates itself the same way it updates the other apps.

Nothing in the app is hard-coded: the list comes from this repository, so adding an app or
publishing a new version of one needs no new version of the manager.

## The apps

Touchless · SeretGram · Wallify · TrustAPK · OneDial · Gallery Wall · WatchIPTV · ScoreUp ·
Touchless Bridge · Screen Awake · Zoopli · אבג ABC · Israel TV

## How it works

| Branch | What is on it |
|---|---|
| `main` | The Android app (`app/`), the list of apps (`catalog/apps.json`) and the tools. |
| `catalog` | `catalog.json` and the icons the app downloads. Written by the **Catalog** workflow. |
| `apks` | APKs of apps that have no public release page of their own (optional). |
| `release-inbox` | A signed build of the manager waiting to be published (see Releasing). |

Every 30 minutes, and whenever `catalog/apps.json` changes, the **Catalog** workflow asks GitHub
for the newest release of each app, reads every APK with `aapt2` (package, version code,
minimum Android, ABIs, form factor), renders its launcher icon, and commits the result to the
`catalog` branch. The app reads
`https://raw.githubusercontent.com/il90il90/israel-app-manager/catalog/catalog.json`.

### Adding an app

Add an entry to `catalog/apps.json` and push:

```json
{
  "id": "my-app",
  "name": "My App",
  "category": "tools",
  "tagline": { "en": "One line about it", "he": "שורה אחת עליה" },
  "description": { "en": "A paragraph.", "he": "פסקה." },
  "sources": [{ "repo": "il90il90/my-app-releases", "tag": "^v\\d", "asset": "\\.apk$" }]
}
```

`tag` and `asset` are regular expressions that choose the releases and the files to take. The
package name, versions, sizes, hashes, icon and accent colour are read from the APK. To use a
hand-made icon instead, put `catalog/icons/<id>.png` next to `apps.json`.

An app that has no public release page can be served from the `apks` branch instead; see
[tools/catalog/README.md](tools/catalog/README.md).

## Building

```bash
./gradlew :app:assembleDebug          # debug build, installs next to the released app
./gradlew :app:testDebugUnitTest :app:lintDebug
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
