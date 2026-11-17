# Catalog tools

`build_catalog.py` turns `catalog/apps.json` into what the app downloads.

```bash
export ANDROID_HOME=...            # needs build-tools (aapt2); also rsvg-convert and Pillow
gh auth login                      # or GH_TOKEN

git worktree add ../catalog-out catalog
python3 tools/catalog/build_catalog.py build --apps catalog/apps.json --out ../catalog-out
```

The run only downloads APKs it has not seen (it compares release asset ids with the previous
`catalog.json`), and keeps the last good entry of an app whose release could not be read.
An app with no release it can reach is listed without a download ("Coming soon" in the app).

## What an entry in apps.json can say

| key | meaning |
|---|---|
| `sources` | where the APKs are: `repo`, and optionally `tag` and `asset` (regular expressions) |
| `website`, `store` | the app's own site and its store page, shown as links on its page |
| `license` | `true` for a premium app: its card gets a *License required* tag and its page a card with a button to message the developer |
| `featured`, `self`, `category`, `tagline`, `description` | as they read |

Every app in the generated catalog also carries `source`: the repository and the tag and file
rules it was resolved with. The app uses them to look for a release newer than the catalog's by
itself, so a slow catalog never means an old download.

`contact.telegram` at the top of the file is where that button leads. Every app in the
generated catalog also gets `latestUrl`, a GitHub link that keeps pointing at its newest
release: a direct download when the release carries one APK under a name that never changes
(`App.apk`), the "latest release" page otherwise. The app shares that link.

## Apps whose source is private

The app can only download from public URLs, so each such app has a public `<App>-releases`
repository that holds nothing but its releases. `tools/release/publish_app.sh` puts a signed
APK there:

```bash
tools/release/publish_app.sh il90il90/Zoopli-releases release.json zoopli-2.22.0.apk
```

It pushes the files to that repository's `release-inbox` branch, where its *Publish release*
workflow checks the signature against `signing-cert.sha256` on main and creates the tagged
release. The script's header describes `release.json`.

(`{ "mirror": true }` sources and the `mirror` command are an older way to do the same from an
`apks` branch of this repository; nothing uses them now.)

## Checking that everything installs

`build_catalog.py` leaves out an APK whose signature does not verify (an unsigned file looks like
any other on a release page, and every device refuses it), and names it in a warning.

`verify_catalog.py` checks the published catalog from the outside: it fetches every download the
way the app does and checks size, SHA-256, package, signature and target SDK. The **Verify
downloads** workflow runs it once a day.

```bash
python3 tools/catalog/verify_catalog.py            # the published catalog
```

## Icons

`apk_icon.py` renders an APK's launcher icon without a device. Adaptive icons are translated to
SVG layer by layer (colours, bitmaps, VectorDrawables with groups, clip paths and gradients) and
rasterised; if a layer uses something it cannot translate, the legacy bitmap icon is used.
