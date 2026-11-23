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

Nothing in the app is hard-coded: the list comes from this repository, so adding an app or
publishing a new version of one needs no new version of the manager.

## The apps

Touchless · SeretGram · Wallify · TrustAPK · OneDial · Gallery Wall · WatchIPTV · ScoreUp ·
Touchless Bridge · Screen Awake · TouchKey · Zoopli · אבג ABC · Israel TV

## How it works

| Branch | What is on it |
|---|---|
| `main` | The Android app (`app/`), the list of apps (`catalog/apps.json`) and the tools. |
| `catalog` | `catalog.json` and the icons the app downloads. Written by the **Catalog** workflow. |
| `apks` | APKs of apps that have no public release page of their own (optional). |
| `release-inbox` | A signed build of the manager waiting to be published (see Releasing). |

The **Catalog** workflow asks GitHub for the newest release of every app, downloads the APKs once
to read their version, size, checksum and icon, and commits the result to the `catalog` branch. It
runs whenever `catalog/apps.json` changes, on a schedule, and when an app's release workflow sends
it the `app-released` event. The app reads
`https://raw.githubusercontent.com/il90il90/israel-app-manager/catalog/catalog.json`.

**The app does not wait for it.** GitHub runs schedules when it has room, and hours can pass before
the catalog notices a release. So the app also asks each app's releases page itself
(`github.com/<repo>/releases/latest`, an ordinary page with no API limit) every time the list is
loaded, and once more at the moment Install or Update is tapped. A release the catalog does not
describe yet is offered straight from GitHub, with the size and SHA-256 GitHub reports for the
file; the catalog's entry supplies what a file name cannot say (package, CPU, minimum Android).
What gets installed is always the newest release.

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

## Google Play Protect

The manager requests no high-risk permissions (no `QUERY_ALL_PACKAGES`, accessibility, SMS or
overlay), installs nothing without Android's own confirmation, and its code is not obfuscated.
If Play Protect still warns about it, see [docs/PLAY_PROTECT.md](docs/PLAY_PROTECT.md) for the
appeal and developer verification steps.

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
