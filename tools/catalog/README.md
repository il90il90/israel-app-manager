# Catalog tools

The Catalog workflow (`.github/workflows/catalog.yml`) runs these in order. Each can also be run
by hand.

| Tool | What it does |
|---|---|
| `validate_catalog.mjs` | Validates `catalog/catalog.json` with `dashboard/js/schema.js`, the same rules the dashboard uses. Exits 1 on errors. |
| `publish_release.py fetch` | Downloads the published catalog and its icons, so the build reuses what it already knows. |
| `build_catalog.py build` | Resolves every app and writes the catalog the app downloads, plus `icons/<id>.png`. |
| `publish_release.py publish` | Uploads icons, swaps the new `catalog.json` into the `catalog` release, keeps `catalog-previous.json`, writes the status into the release description. |
| `publish_release.py fail` | Records a failed run in the release description. Nothing else changes. |
| `publish_release.py rollback` | Swaps `catalog-previous.json` back in and pins it until the next catalog change. |
| `verify_catalog.py` | Daily check (**Verify downloads** workflow): fetches every published APK like the app does and checks size, SHA-256, package, signature and target SDK. |
| `apk_icon.py` | Renders an APK's launcher icon without a device. |

```bash
node tools/catalog/validate_catalog.mjs catalog/catalog.json

export ANDROID_HOME=...            # needs build-tools (aapt2, apksigner); also rsvg-convert and Pillow
gh auth login                      # or GH_TOKEN
python3 tools/catalog/publish_release.py fetch --out out
python3 tools/catalog/build_catalog.py build --catalog catalog/catalog.json --out out --report build.json
python3 tools/catalog/publish_release.py status       # what the last run reported
```

## How an app is resolved

`source_catalog.py` turns each entry of `catalog/catalog.json` into a build plan:

- **link** entries: `app-manager.json` (or `.github/app-manager.json`) from the repository's
  default branch supplies name, `by`, tagline, description, icon, screenshots, package name and
  release rules. Missing fields fall back to the repository's own name, owner and description.
  Fields written in the catalog entry win over both.
- **custom** entries: everything comes from the entry; `release.strategy` is
  `github-latest`, `direct-apk` or `external-json`. No `release` means the app is listed without
  a download ("not available" in the app).
- `hidden: true` entries are not published.

Then `build_catalog.py`:

- picks the newest non-draft release whose tag and asset names match (`github-latest`), or
  downloads the direct APK / the APK named by the external JSON (re-downloaded only when its
  ETag, Last-Modified or size changes);
- inspects each APK with aapt2 (package, version, minimum and target SDK, ABIs, phone / TV /
  watch) and leaves out any APK whose signature Android would refuse, or whose package differs
  from the one `app-manager.json` declares;
- takes the icon from `catalog/icons/<id>.png` (owner override), else the entry's or
  `app-manager.json`'s icon (downloaded, checked to be an image, re-encoded as PNG), else the
  APK's launcher icon;
- keeps the last published entry of an app whose release cannot be read right now;
- fails (and nothing is published) only when no app at all can be resolved, which means GitHub
  or the network is down.

Every app in the published catalog carries `source` (repository, tag and file rules) for
`github-latest` releases, so the app can look for a newer release by itself, and `latestUrl`, a
link that keeps pointing at the newest version.

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
