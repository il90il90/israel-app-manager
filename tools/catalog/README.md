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

## Apps that are not released publicly

The app can only download from public URLs. An app whose source is `{ "mirror": true }` is
served from the `apks` branch of this repository: `apks/<app id>/` holds its APK files and a
`release.json` (tag, name, notes, date, file names). Publishing an APK there makes it public.

Where those apps are really released is deliberately not written in this repository. It is
passed to the `mirror` command as a JSON file:

```json
{ "my-app": { "repo": "owner/private-repo", "tag": "^v\\d", "asset": "\\.apk$" } }
```

```bash
git worktree add ../apks-out apks
python3 tools/catalog/build_catalog.py mirror --apps catalog/apps.json --mirror ../apks-out --sources private-sources.json
cd ../apks-out && git add -A && git commit -m "Mirror app releases" && git push origin HEAD:apks
```

To have the **Catalog** workflow do this by itself, add two repository secrets:
`PRIVATE_SOURCES_JSON` (the JSON above) and `PRIVATE_REPOS_TOKEN` (a fine-grained token with
read access to *Contents* of those repositories).

As soon as an app also lists a public releases repository that has a release, the mirror copy
is dropped and the public release is used.

## Icons

`apk_icon.py` renders an APK's launcher icon without a device. Adaptive icons are translated to
SVG layer by layer (colours, bitmaps, VectorDrawables with groups, clip paths and gradients) and
rasterised; if a layer uses something it cannot translate, the legacy bitmap icon is used.
