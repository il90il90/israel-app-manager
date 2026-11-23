# Google Play Protect

Play Protect has warned about Israel App Manager on some phones. The app is not harmful: it
downloads the APKs listed in this repository's catalog, checks their SHA-256, and hands each one
to Android's own installer, which asks the user every time. Nothing here can make Google
stop warning on its own. This page lists what the app does to look like what it is, and what the
owner has to do with Google.

## What the app itself does (done in code)

| What scanners weigh | What Israel App Manager does |
|---|---|
| `QUERY_ALL_PACKAGES`: reading the full list of installed apps is a high-risk permission | **Not requested** (since 1.1.0). The app sees only apps that have a launcher entry (phone or TV) and the apps it installed itself, through a `<queries>` block. That covers every app in the catalog. |
| Installing apps silently (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`, accessibility clicks on the installer) | Not used. Every install and update, the manager's own included, is confirmed in Android's own dialog (`USER_ACTION_REQUIRED`). |
| SMS, call log, notification listener, accessibility, device admin, overlay | None of them is requested. |
| Obfuscated code in an app that installs other apps | Not obfuscated (since 1.1.0, `-dontobfuscate`): every class and method keeps its real name, so the APK can be read as easily as this repository. |
| Old target SDK | Targets the current Android API level (36). |
| Dynamic code loading, native code, reflection, `Runtime.exec` | None. |
| Unsigned, debug-signed or re-signed releases | Every release is signed with the same release key, and the publish workflows refuse an APK whose certificate is not `release/signing-cert.sha256`. |
| Downloads it cannot vouch for | Every APK is checked against the SHA-256 in the catalog (or the one GitHub reports) before it reaches the installer. |

Keep it that way: a new permission or SDK can bring a warning back.

## What only the owner can do

### 1. Appeal the classification (do this first)

Google's form for developers whose app Play Protect flagged by mistake:
**https://support.google.com/googleplay/android-developer/contact/protectappeals**
(guidance: https://developers.google.com/android/play-protect/warning-dev-guidance).
Google answers appeals in English only.

What the form asks for, and the answers:

- **Package name**: `com.il90.appmanager`
- **Signing certificate SHA-256**: the value in `release/signing-cert.sha256`
  (`7E:A1:B5:1D:24:F5:DF:08:22:91:1C:02:E0:93:11:07:BA:BC:CA:39:F5:75:F6:7A:38:C5:17:8D:93:A6:F5:A6`)
- **Where users get it**: https://github.com/il90il90/israel-app-manager/releases/latest
- **Source code**: https://github.com/il90il90/israel-app-manager
- **The warning text**: copy it exactly from the phone (a screenshot helps), and the app version.

Text to paste into the description (edit the version and the warning):

> Israel App Manager (com.il90.appmanager) is an open-source app store for the developer's own
> Android apps, distributed from GitHub Releases. Play Protect shows "<the exact warning>" for
> version <x.y.z>. The app downloads APKs listed in a public catalog in the same GitHub
> repository, verifies each file's SHA-256, and installs it through PackageInstaller with
> USER_ACTION_REQUIRED, so Android's confirmation dialog is shown for every install and update.
> It does not request QUERY_ALL_PACKAGES, accessibility, SMS, call log, notification access,
> device admin or overlay permissions; it loads no code at runtime and its code is not
> obfuscated. The full source is at https://github.com/il90il90/israel-app-manager and every
> release is signed with the certificate above. We believe the app was classified in error and
> ask for it to be reviewed.

Appeal again after each release that changes the classification; the warning is tied to the
APK, not to the repository.

### 2. Developer verification (Android Developer Console)

Google requires apps installed outside Google Play on certified Android devices to come from a
verified developer (https://developer.android.com/developer-verification). As that page put it
in November 2026: protections began on **September 30, 2026** in Brazil, Indonesia, Singapore and
Thailand, and verification expands **globally in 2027**. Israel is not in the first wave, but it
will be. Registering now also ties the package and its key to a known developer, which is what
Play Protect trusts.

1. For apps distributed only outside Google Play, sign up in the **Android Developer Console**
   (https://android.google.com/developerconsole) and verify your identity. (A Play Console
   account works too.) There is also an open-source registration path, linked from the
   developer verification page.
2. Register the package `com.il90.appmanager` with the release signing certificate above.
3. Do the same for every app in the catalog (each app's own package and key). Where
   verification is enforced, Android will refuse to install apps from unregistered developers.

### 3. The apps in the catalog

Play Protect also knows which app installed what. If an app in the catalog is itself flagged,
the store that keeps installing it looks worse. Two entries deserve a look:

- **OneDial** is `com.app360brains.exDialer.mod`: a modified build of another developer's app.
  Play Protect flags modified third-party apps more often than anything else in this catalog.
- **Wallify** is published as `com.il90.wallify.debug`, a debug build.

Removing or replacing them is the owner's decision; the manager does not change the catalog on
its own.
