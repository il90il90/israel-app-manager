#!/usr/bin/env bash
# Publishes signed APKs of one app to its public "<App>-releases" repository.
#
#   tools/release/publish_app.sh <owner/repo> <release.json> <apk> [<apk>...]
#
# The apps' source repositories are private, so their APKs are published from here: the
# files are pushed to the `release-inbox` branch of the releases repository, where its
# "Publish release" workflow checks the signature against signing-cert.sha256 on main and
# creates the tagged GitHub Release. The catalog picks the new version up on its next run.
#
# release.json:
#   { "tag": "v1.2.3", "title": "App 1.2.3", "package": "com.example.app",
#     "files": [ { "name": "app-1.2.3.apk", "stable": "App.apk" } ],
#     "notes": "Markdown shown on the release and as What's new in the app" }
# `stable` is optional: a second copy under a name that never changes, which makes
# https://github.com/<owner/repo>/releases/latest/download/<stable> an always-latest link.
# "replace": true swaps the files of a release that is already out (one that went out with a
# bad file) and leaves its title and notes alone.
#
# This is the by-hand route. Each app's private repository also has a "Public release" workflow
# that does the same after every release there, once its RELEASES_TOKEN secret is set.
set -euo pipefail

repo="${1:?usage: publish_app.sh <owner/repo> <release.json> <apk>...}"
meta="${2:?release.json}"
shift 2
[ "$#" -gt 0 ] || { echo "no APK given" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git -C "$work" init --quiet --initial-branch=release-inbox
git -C "$work" remote add origin "https://github.com/$repo"
git -C "$work" fetch --quiet --depth 1 origin main
mkdir -p "$work/.github/workflows"
# The workflow has to be on the pushed branch to run for it; main keeps the reference copy.
git -C "$work" show FETCH_HEAD:.github/workflows/publish.yml > "$work/.github/workflows/publish.yml"

cp "$meta" "$work/release.json"
for apk in "$@"; do
  name="$(basename "$apk")"
  jq -e --arg n "$name" '.files | any(.name == $n)' "$work/release.json" > /dev/null \
    || { echo "$name is not listed in $meta" >&2; exit 1; }
  cp "$apk" "$work/$name"
done
listed="$(jq '.files | length' "$work/release.json")"
[ "$listed" -eq "$#" ] || { echo "$meta lists $listed files, $# given" >&2; exit 1; }

tag="$(jq -r .tag "$work/release.json")"
git -C "$work" add -A
git -C "$work" \
  -c user.name="$(git config user.name || echo release)" \
  -c user.email="$(git config user.email || echo release@users.noreply.github.com)" \
  commit --quiet -m "Release $tag" ${COMMIT_TRAILER:+-m "$COMMIT_TRAILER"}
git -C "$work" push --quiet --force origin release-inbox
echo "Pushed $tag to $repo@release-inbox - the Publish release workflow takes it from here."
