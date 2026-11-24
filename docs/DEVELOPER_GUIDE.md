# Developer Guide: getting your app into Israel App Manager

This guide is for third-party Android developers, and for the AI coding assistants that help
them. It explains how to prepare a public GitHub repository so that Israel App Manager can list,
install and update your app, and how to ask for it to be added.

> **Who decides:** the catalog is controlled only by its owner. Nobody can add an app to it
> directly. You prepare your repository, then send the link for approval
> ([Submitting your app](#submitting-your-app)).

- [What you need](#what-you-need)
- [app-manager.json](#app-managerjson)
- [Logo and screenshots](#logo-and-screenshots)
- [Attribution](#attribution)
- [GitHub Releases and APK naming](#github-releases-and-apk-naming)
- [Private source code: a public releases repository](#private-source-code-a-public-releases-repository)
- [Compatibility badge](#compatibility-badge)
- [Checklist](#checklist)
- [Submitting your app](#submitting-your-app)
- [Notes for AI coding assistants](#notes-for-ai-coding-assistants)
- [Contact](#contact)

## What you need

1. A **public** GitHub repository (the app downloads only from public URLs).
2. An **`app-manager.json`** file at the root of its default branch.
3. **GitHub Releases** with a **signed APK** attached to each release.

When your app is in the catalog, every new GitHub Release you publish reaches users
automatically: the catalog is rebuilt on a schedule and the app also checks your releases page
itself. You do not need to contact anyone for updates.

## app-manager.json

Place it at the repository root (`/app-manager.json`). `/.github/app-manager.json` is also read
if the root has none. JSON Schema:
<https://il90il90.github.io/israel-app-manager/schemas/app-manager.schema.json>.

Full example (also in [`docs/examples/app-manager.json`](examples/app-manager.json)):

```json
{
  "$schema": "https://il90il90.github.io/israel-app-manager/schemas/app-manager.schema.json",
  "specVersion": 1,
  "id": "weather-now",
  "name": "Weather Now",
  "by": "Dana Levi",
  "developerUrl": "https://github.com/dana-levi",
  "tagline": { "en": "The forecast at a glance", "he": "התחזית במבט אחד" },
  "description": {
    "en": "A fast, ad-free weather app with hourly and 7-day forecasts.",
    "he": "אפליקציית מזג אוויר מהירה וללא פרסומות, עם תחזית שעתית ושבועית."
  },
  "icon": "app-manager/icon.png",
  "screenshots": ["app-manager/screenshot-1.png", "app-manager/screenshot-2.png"],
  "categories": ["tools"],
  "website": "https://example.com/weather-now",
  "license": "free",
  "android": { "packageName": "com.example.weathernow", "minSdk": 26 },
  "releases": { "tag": "^v\\d", "asset": "^weather-now-.*\\.apk$" }
}
```

Minimal valid file:

```json
{ "specVersion": 1, "name": "Weather Now", "by": "Dana Levi" }
```

### Fields

| Field | Required | Description |
|---|---|---|
| `specVersion` | yes | Always `1` for this version of the specification. |
| `name` | yes | App name, up to 80 characters. |
| `by` | recommended | Developer credit, shown as "by …". Without it, your GitHub user name is shown. |
| `id` | no | Suggested catalog ID: lowercase letters, digits, dashes. The owner decides the final ID. |
| `developerUrl` | no | https link to you or your organization. |
| `tagline` | no | One line, up to 160 characters. A string (English) or `{"en": "…", "he": "…"}`. |
| `description` | no | Up to 4000 characters, same format as `tagline`. |
| `icon` | no | Path in the repository or an https URL. See [Logo and screenshots](#logo-and-screenshots). Without it, the icon is taken from the APK. |
| `screenshots` | no | Up to 10 paths or https URLs. |
| `categories` | no | Suggested categories (for example `tools`, `video`, `kids`, `communication`). The owner decides. |
| `website` | no | https link to the app's website. |
| `license` | no | `"free"` (default) or `"premium"` (users need a license from you; the app shows "License required"). |
| `android.packageName` | recommended | Your application ID. **If set, an APK with any other package name is refused**, which protects your listing. |
| `android.minSdk` | no | Informational; the real minimum is read from the APK. |
| `releases.repository` | no | `owner/name` of the repository that holds the releases, if it is not this one. |
| `releases.tag` | no | Regular expression for the release tags to use (for example `^v\d`). |
| `releases.asset` | no | Regular expression for the APK file names to use (default: any `.apk`). |
| `releases.exclude` | no | Regular expression for files to leave out. |

Rules:

- Use only `https://` links. Other links are ignored.
- Repository paths must be relative and stay inside the repository (no `..`, no leading `/`).
- Regular expressions must be portable (no lookbehind, no named groups).
- Unknown fields are ignored with a warning. Missing optional fields are fine: nothing is shown
  for them.
- The catalog owner can override any of these values in the catalog itself.

## Logo and screenshots

- **Icon:** square PNG, 512 x 512 px recommended (at most 2 MB). Use a full-bleed image without
  rounded corners; the app applies its own shape. Without an icon, the launcher icon is extracted
  from your APK.
- **Screenshots:** PNG, JPEG or WebP, portrait phone screenshots work best (for example
  1080 x 1920). Up to 10. They are shown in the order listed.
- Keep media in the repository (for example an `app-manager/` folder) so it is versioned with the
  app. Files are loaded from `raw.githubusercontent.com` from your default branch.

## Attribution

- `by` is the credit shown next to your app ("by Dana Levi"). Use your name or your
  organization's name.
- `developerUrl` and `website` are shown as links where the app supports them.
- The catalog owner may adjust names, credit or descriptions for consistency, and may decline or
  remove any app.

## GitHub Releases and APK naming

1. Build a **release** APK **signed with your release key**. Unsigned or debug-signed builds are
   refused, because Android refuses them. Always sign updates with the **same key**, or Android
   will not install them over the previous version.
2. Create a **GitHub Release** (not only a tag) and attach the APK as a release asset.
3. Name files predictably, with the version in the name:
   - one APK: `weather-now-1.4.0.apk`;
   - several builds: `weather-now-phone-1.4.0.apk`, `weather-now-tv-1.4.0.apk`,
     `weather-now-1.4.0-arm64-v8a.apk`. The device type (phone, TV, watch) and CPU are read
     from the APK itself; `-tv` / `-watch` in names only help tell several files apart.
   - An additional copy without a version number (`WeatherNow.apk`) is fine and gives you a
     permanent "latest" download link.
4. Tag releases consistently, for example `v1.4.0`. Mark test builds as **pre-releases**; they
   are ignored unless the catalog owner opts in.
5. Do not delete or replace the APK of a published release: publish a new release instead.
6. Optional: if your release workflow can send a `repository_dispatch` to
   `il90il90/israel-app-manager`, ask the owner; otherwise the catalog notices new releases on
   its own within minutes, and the app checks releases pages directly.

What the catalog reads from each APK: package name, version name and code, minimum and target
Android version, CPU architectures, phone / TV / watch support, size and SHA-256. Users see your
GitHub release notes as "What's new".

## Private source code: a public releases repository

If your source code is private, create a separate **public** repository that holds only releases,
for example `dana-levi/weather-now-releases`:

1. Put `app-manager.json` (and the icon and screenshots) in the public releases repository,
   **or** keep `app-manager.json` in a public repository and point to the releases with
   `"releases": { "repository": "dana-levi/weather-now-releases" }`.
2. Publish GitHub Releases with the signed APK in that public repository.
3. Your source code never needs to be public.

## Compatibility badge

You may show that your repository follows this guide:

```markdown
[![Israel App Manager compatible](https://il90il90.github.io/israel-app-manager/badge/compatible.svg)](https://github.com/il90il90/israel-app-manager/blob/main/docs/DEVELOPER_GUIDE.md)
```

[![Israel App Manager compatible](https://il90il90.github.io/israel-app-manager/badge/compatible.svg)](https://github.com/il90il90/israel-app-manager/blob/main/docs/DEVELOPER_GUIDE.md)

The badge is **informational only**. It does not mean the app is listed, reviewed, endorsed or
safe; it is not a security proof. Every APK is still checked when the catalog is built.

## Checklist

- [ ] The repository is public (or releases are in a public releases repository).
- [ ] `app-manager.json` is at the root of the default branch, with `specVersion: 1`, `name`, `by`.
- [ ] `android.packageName` matches your app's application ID.
- [ ] The icon is a square PNG (512 x 512) and every link is https.
- [ ] The latest GitHub Release has a signed release APK attached.
- [ ] The APK file name contains the version, and tags follow one pattern (for example `v1.2.3`).
- [ ] Optional: the JSON validates against the schema linked above.

## Submitting your app

1. Make sure the [checklist](#checklist) is complete.
2. Send the repository link (for example `https://github.com/dana-levi/weather-now`) to the
   catalog owner on Telegram: **<https://t.me/YisraelCohen>**. Include one sentence about the app.
3. The owner reviews it and, if approved, adds it from the catalog dashboard as a **link** entry.
   From then on your `app-manager.json` and releases are read automatically.

There is no public form and no way to write to the catalog without the owner: pull requests that
edit `catalog/catalog.json` are not accepted as submissions.

## Notes for AI coding assistants

If you are an AI assistant preparing a repository for Israel App Manager:

1. Create `app-manager.json` at the repository root following the example above and the JSON
   Schema at `https://il90il90.github.io/israel-app-manager/schemas/app-manager.schema.json`.
   Set `specVersion` to `1`. Only include fields you can fill truthfully; omit the rest.
2. Read the real application ID from the Android project (`applicationId` in
   `app/build.gradle(.kts)`) and put it in `android.packageName`.
3. Use repository-relative paths for media you add (for example `app-manager/icon.png`), and
   only `https://` URLs otherwise. No `..`, no `http://`, no `data:` URLs.
4. Make sure releases attach a **signed release APK** whose file name includes the version, and
   set `releases.asset` to a pattern that matches only those files (for example
   `^weather-now-.*\\.apk$`, with backslashes escaped in JSON).
5. Do not edit `il90il90/israel-app-manager` or its catalog, and do not open pull requests to it to
   add an app. Tell the developer to send the repository link to the owner on Telegram:
   `https://t.me/YisraelCohen`.
6. The compatibility badge is optional and informational; do not describe it as a security
   certification.

## Contact

Questions, submissions and support: Telegram **<https://t.me/YisraelCohen>**.
