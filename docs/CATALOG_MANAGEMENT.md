# Catalog Management (owner guide)

This guide is for the owner of `il90il90/israel-app-manager`. It explains how the list of apps
in Israel App Manager is managed, published and rolled back, and how to set everything up the
first time.

**Dashboard:** <https://il90il90.github.io/israel-app-manager/>

- [How it fits together](#how-it-fits-together)
- [One-time setup](#one-time-setup)
  - [1. Turn on GitHub Pages](#1-turn-on-github-pages)
  - [2. Create the fine-grained personal access token](#2-create-the-fine-grained-personal-access-token)
  - [3. First sign-in](#3-first-sign-in)
- [Managing apps](#managing-apps)
- [Managing categories](#managing-categories)
- [Validation](#validation)
- [Publishing](#publishing)
- [Rollback](#rollback)
- [Token: storage, replacing and revoking](#token-storage-replacing-and-revoking)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Reference: catalog.json](#reference-catalogjson)
- [Contact & Support](#contact--support)

## How it fits together

```
 Dashboard (GitHub Pages, in your browser)
        │  commits catalog/catalog.json with the SHA it was loaded at
        ▼
 main branch: catalog/catalog.json            ← the only source of truth, owner-controlled
        │  push triggers the Catalog workflow (also: schedule, app-released event, manual run)
        ▼
 GitHub Actions "Catalog"
   1. validate catalog/catalog.json (same rules as the dashboard)  ── errors? stop, keep the last good catalog
   2. resolve every app: app-manager.json, newest release, APK checks (package, version,
      signature, minimum Android), icons
   3. upload icons, then swap the new catalog.json into the "catalog" release
      (the old one becomes catalog-previous.json)
   4. write the outcome into the release description (the dashboard reads it)
        ▼
 GitHub Release with the fixed tag "catalog"   (pre-release, never "latest", never an app release)
   catalog.json · catalog-previous.json · icon-<id>-<hash>.png
        ▼
 Israel App Manager on phones and TVs: downloads catalog.json, validates it, caches the last good copy
```

- The catalog is **never** coupled to the Android app's own releases. The `catalog` release is
  created as a pre-release and is never marked "latest", so download links such as
  `releases/latest/download/IsraelAppManager.apk` are unaffected.
- **No app rebuild** is needed for any catalog change: phones pick up the new catalog on their next
  refresh (opening the app, pull to refresh, or the background update check).
- A copy of the published `catalog.json` is also kept on the `catalog` branch, because app versions
  up to 1.1.0 read it from there. The `catalog` tag is moved to the same commit, so the branch and
  the tag of the same name always hold identical content.

## One-time setup

### 1. Turn on GitHub Pages

1. Open <https://github.com/il90il90/israel-app-manager/settings/pages>
   (repository **Settings > Pages**).
2. Under **Build and deployment > Source**, choose **GitHub Actions**.
3. Open **Actions > Dashboard** and click **Run workflow** (it also runs by itself whenever
   something in `dashboard/` changes on `main`).
4. When it finishes, the dashboard is live at
   **<https://il90il90.github.io/israel-app-manager/>**. GitHub serves `github.io` sites over
   HTTPS only.

The first run of the **Catalog** workflow on `main` creates the `catalog` release by itself. To
create it right away: **Actions > Catalog > Run workflow** (action: `publish`).

### 2. Create the fine-grained personal access token

The dashboard has no server and no login service: it talks to the GitHub API directly from your
browser with a token you create. Give it the least access possible.

1. Open GitHub **Settings > Developer settings > Personal access tokens > Fine-grained tokens**
   (direct link: <https://github.com/settings/personal-access-tokens>), then **Generate new token**.
2. **Token name:** `Catalog dashboard`.
3. **Resource owner:** your account, `il90il90`.
4. **Expiration:**
   - If the menu offers **No expiration**, you may choose it, as you prefer not to renew the
     token. GitHub shows a warning; that is expected. Revoke the token yourself if it is ever
     exposed (see [revoking](#token-storage-replacing-and-revoking)).
   - If **No expiration** is not offered (GitHub or an organization policy can limit token
     lifetime), choose the longest period available. GitHub emails you before it expires; then
     create a new token the same way and use **Replace token** in the dashboard.
5. **Repository access:** **Only select repositories**, then select
   **`il90il90/israel-app-manager`**. Do not choose "All repositories".
6. **Permissions > Repository permissions:** set **Contents** to **Read and write**.
   Leave every other permission as **No access**. (GitHub adds **Metadata: Read-only**
   automatically; that is required and harmless.) No Actions, Workflows, Administration or
   account permissions are needed.
7. Click **Generate token** and copy it. It starts with `github_pat_`. GitHub shows it only once.

What this token can do: read and change files in this one repository (and read its releases).
It cannot change workflows, settings, other repositories, or your account.

### 3. First sign-in

1. Open <https://il90il90.github.io/israel-app-manager/>.
2. Paste the token and click **Sign in**. The dashboard checks it (your GitHub profile, access to
   this repository, that it can read `catalog/catalog.json`).
3. You see **Hello, Israel 👋** with your GitHub avatar. On later visits you are signed in
   automatically.

**How to get started?** at the top of the dashboard repeats these steps at any time.

## Managing apps

The **Apps** tab lists every entry of `catalog/catalog.json`, in the order the app shows them.

- **Search** by name, ID, developer or repository; **filter** by category, type
  (Link / Custom) and status (changed but unpublished, no release, hidden, featured, premium).
- **Add app**, **Edit**, **Delete**, and move apps **up / down** to change their order.
- Badges show what is **New** or **Edited** in your draft.

### Link entries (a GitHub repository)

For apps whose repository follows the [developer guide](DEVELOPER_GUIDE.md):

- **Repository** (`owner/name`) is all that is required.
- Its `app-manager.json` supplies the name, developer credit ("by"), tagline, description, icon
  and screenshots. Its newest GitHub Release supplies the APK and the version.
- **Fetch preview** shows what will be published: whether `app-manager.json` was found and is
  valid, the latest release and its APK files.
- Anything you fill in under **Override the repository's metadata** wins over what the
  repository says. **Release filters** narrow which tags and files are used.
- A repository without `app-manager.json` still works: its GitHub name and description are used.

### Custom entries (written by hand)

For apps you describe yourself. **Name** is required; everything else is optional.

| Release strategy | When to use it | What happens on publish |
|---|---|---|
| **GitHub: newest release** | The APK is attached to GitHub Releases | The newest release whose tag and file name match the patterns is used |
| **Direct APK link** | A fixed https link to an APK | The APK is downloaded and inspected; it is downloaded again only when the server says it changed |
| **External JSON file** | Another system publishes versions | The JSON (`versionName`, `url`, optional `sha256`, `notes`, `publishedAt`) names the current APK; see [`docs/examples/external-release.json`](examples/external-release.json) |
| **No release** | Not released yet | The app is listed with its details, and Install is disabled |

### Fields common to both

- **App ID**: lowercase letters, digits and dashes. It is the app's identity on every phone,
  so it cannot be changed after the app is added (delete and re-add to change it).
- **Categories**: any number (up to 5); none means **Other**.
- **Featured**, **Hidden** (keeps the entry but does not publish it), **Premium** (the app shows
  "License required" and a button to contact you; the contact defaults to the catalog's Telegram
  link), and **This is Israel App Manager itself** (only for the manager's own entry).
- Optional media is optional: an app without an icon gets its icon from the APK (or a letter);
  missing screenshots or descriptions are simply not shown.

### Drafts and change detection

Every edit is saved immediately as a **draft in this browser** (localStorage). The bar at the
bottom shows how many changes are unpublished. **Discard** returns to the catalog as it is on
GitHub. If the catalog changed on GitHub while you had a draft, your draft is merged on top of
the new version the next time you open the dashboard.

## Managing categories

The **Categories** tab is the central category registry.

- Each category has a stable **ID** and an **English** name (required) plus an optional
  **Hebrew** name (the app is in English and Hebrew).
- **Add category**, rename, reorder, and **Remove**. Removing a category takes it off every app;
  apps left with none are moved to **Other**.
- **Other** is the default category and cannot be removed.

The **Settings** tab holds the default developer credit, the Telegram contact used by the app, and
an optional announcement card shown at the top of the app.

## Validation

The same rules run in three places:

1. **In the dashboard**, while you edit (an app with errors cannot be saved) and again in
   **Review & publish** (a catalog with errors cannot be published).
2. **In GitHub Actions**, before anything is published (`tools/catalog/validate_catalog.mjs`, which
   uses the dashboard's own `dashboard/js/schema.js`).
3. **In the Android app**, before a downloaded catalog is used or cached.

What is checked: `schemaVersion`, required fields, stable and unique app IDs, `link` / `custom`
types, categories that exist in the registry, https-only URLs, portable regular expressions,
release strategies, text lengths, and that only one app is marked as the manager itself. During
the build, each APK must be readable, signed so that Android accepts it, and (if the repository's
`app-manager.json` declares a package name) be that package. An APK that fails is left out and
named in the warnings; an app whose release cannot be read keeps its last published entry.

## Publishing

1. Click **Review & publish** in the bottom bar.
2. Check the summary of new, changed and removed apps and categories. Errors block publishing;
   warnings do not.
3. Edit the description if you like, then click **Publish**.

The dashboard then shows three steps:

1. **Commit catalog.json**: the file is committed to `main` naming the version (SHA) you started
   from. If someone changed it meanwhile, GitHub refuses, and the dashboard merges your changes
   onto the newer version automatically. If the same app was changed on both sides, you choose
   which version to keep. Nothing is ever overwritten silently.
2. **Validate and build (GitHub Actions)**: the Catalog workflow runs (usually 1 to 4 minutes),
   with a link to the run.
3. **Publish to the catalog release**: success, or the exact errors. **On any failure the
   previous catalog stays published**, and the reason is also shown in the status bar at the top
   of the dashboard and in the description of the `catalog` release.

If no result arrives within about 12 minutes, the dashboard says so and links to
**Actions > Catalog**. It never reports success it has not seen.

Runs never overlap: they queue in one concurrency group. If you publish twice quickly, the
first run notices that a newer change exists and leaves publishing to the run for that change.

## Rollback

- **Normal rollback (recommended):** dashboard **History** tab > **Load this version** on any
  earlier commit. The older catalog becomes your draft; review it and publish. This is recorded
  in Git like any other change.
- **Emergency rollback (instant):** **Actions > Catalog > Run workflow**, action **rollback**. The
  previously published file (`catalog-previous.json`) becomes `catalog.json` again immediately.
  Scheduled runs then leave it alone (it is "pinned") until the next change to
  `catalog/catalog.json`; fix or revert the catalog in the dashboard afterwards.
- From a computer: `git revert` the commit that changed `catalog/catalog.json` and push.

## Token: storage, replacing and revoking

- **Where it is kept:** in this browser's `localStorage`, **unencrypted and persistent**, as you
  chose (no extra password). It is never put in the repository, the source code, URLs, logs,
  analytics or any third-party script; the page loads no third-party code at all. It is sent only
  to `https://api.github.com`, and only for this repository and your profile.
- **Account menu** (your avatar, top right):
  - **Check token access**: tests the token again.
  - **Replace token**: paste a new token (for example after it expired).
  - **Sign out and delete token**: removes it from this browser.
  - **Discard unpublished changes**.
- **Revoke** (when a device is lost, the token may have leaked, or you no longer need it):
  GitHub **Settings > Developer settings > Personal access tokens > Fine-grained tokens**, open
  the token, **Delete**. Deleting it on GitHub stops it working everywhere at once; signing out
  only removes it from one browser.

## Security notes

- **The dashboard page is public.** Anyone can open its URL and see the sign-in screen and the
  public catalog. Only someone with a valid token can change anything. The page is not private.
- **localStorage risk:** anyone who can use your browser profile, malware on the computer, or a
  script injected into this page could read the token. Mitigations in place: no third-party
  scripts, a strict Content-Security-Policy (only this site and the GitHub API), all catalog and
  repository text is inserted as plain text (never as HTML), only https links and images are
  used, the page refuses to run inside a frame, and the token is limited to one repository and
  one permission. Use the dashboard only on your own devices and revoke the token if in doubt.
- **No public writes:** only you (with the token) can change the catalog. Third-party developers
  can only ask you to add their repository (see the [developer guide](DEVELOPER_GUIDE.md)).
- The compatibility badge in other developers' READMEs is informational. It is not a security
  review; the build still checks every APK's signature and package.

## Troubleshooting

| Symptom | What to do |
|---|---|
| "That does not look like a GitHub token" | Copy the whole token; fine-grained tokens start with `github_pat_`. |
| "This token cannot see il90il90/israel-app-manager" | Edit the token on GitHub: Repository access > Only select repositories > add the repository. |
| Publish fails with "GitHub refused the change" | The token lacks write access: set Repository permissions > **Contents: Read and write**. |
| "Your token no longer works" | It expired or was revoked. Create a new one and use **Replace token**. |
| "Rate limit reached" | Wait a few minutes. Previews of other repositories use GitHub's anonymous limit (60 requests per hour per network). |
| Publishing stays on "Waiting for the Catalog workflow" | Check **Actions > Catalog**. Actions must be enabled for the repository (Settings > Actions > General). |
| The run failed at "validation" | The errors are listed in the dashboard and in the release description; fix them and publish again. The previous catalog is still live. |
| An app shows "no APK" / is not installable | Its release has no APK matching the patterns, or the APK is unsigned. See the warnings of the last run. |
| The dashboard URL shows 404 | Pages is not enabled with source "GitHub Actions", or the Dashboard workflow has not run yet. |
| Changes do not appear on a phone | Pull to refresh in the app. It shows the last good catalog when offline, and a notice when the published catalog needs a newer app version. |

## Reference: catalog.json

`catalog/catalog.json` is described by
[`dashboard/schemas/catalog.schema.json`](../dashboard/schemas/catalog.schema.json) (for editors);
the authoritative rules are in [`dashboard/js/schema.js`](../dashboard/js/schema.js).

```json
{
  "schemaVersion": 2,
  "developer": "Israel",
  "contact": { "telegram": "https://t.me/YisraelCohen" },
  "announcement": null,
  "categories": [
    { "id": "tools", "name": { "en": "Tools", "he": "כלים" } },
    { "id": "other", "name": { "en": "Other", "he": "אחר" } }
  ],
  "apps": [
    { "id": "weather-now", "type": "link", "repository": "dana-levi/weather-now", "categories": ["tools"] },
    {
      "id": "touchkey", "type": "custom", "name": "TouchKey", "by": "Israel", "categories": ["tools"],
      "tagline": { "en": "Remap any hardware key" },
      "release": { "strategy": "github-latest", "repository": "il90il90/touchkey-releases", "asset": "\\.apk$" }
    }
  ]
}
```

Editing the file directly on GitHub or from a clone also works; the same validation runs before
anything is published.

## Contact & Support

Telegram: <https://t.me/YisraelCohen>
