#!/usr/bin/env bash
# Builds a signed release and hands it to GitHub for publishing.
#
#   tools/release/make_release.sh <keys-dir> [notes-file]
#
# <keys-dir> is a checkout of the private keys repository (release.jks + keystore.properties).
# The version comes from version.properties; commit and push the bump to main first.
# The signed APK is pushed to the `release-inbox` branch, and the "Publish release"
# workflow turns it into the GitHub Release tagged v<version>.
set -euo pipefail

keys="${1:?usage: make_release.sh <keys-dir> [notes-file]}"
notes_file="${2:-}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

version="$(sed -n 's/^VERSION_NAME=//p' version.properties)"
code="$(sed -n 's/^VERSION_CODE=//p' version.properties)"
commit="$(git rev-parse HEAD)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Uncommitted changes: commit them first, the release must match a commit." >&2
  exit 1
fi
if ! git branch -r --contains "$commit" | grep -q "origin/main"; then
  echo "HEAD ($commit) is not on origin/main yet: push it first." >&2
  exit 1
fi

"${GRADLE:-./gradlew}" --console=plain -q :app:testDebugUnitTest :app:assembleRelease "-Piam.keystoreDir=$keys"
apk="app/build/outputs/apk/release/app-release.apk"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"
expected="$(tr -d '[:space:]' < release/signing-cert.sha256)"
actual="$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr -d '[:space:]')"
if [ "$expected" != "$actual" ]; then
  echo "The APK is not signed with the release key." >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$apk" "$work/israel-app-manager-${version}.apk"
if [ -n "$notes_file" ]; then notes="$(cat "$notes_file")"; else notes="$(git log -1 --pretty=%B)"; fi
python3 - "$work/release.json" "$version" "$code" "$commit" "$notes" <<'PY'
import json, sys
path, version, code, commit, notes = sys.argv[1:6]
json.dump({"versionName": version, "versionCode": int(code), "commit": commit, "notes": notes}, open(path, "w"), ensure_ascii=False, indent=2)
PY
mkdir -p "$work/.github/workflows"
git show "$commit:.github/workflows/publish-inbox.yml" > "$work/.github/workflows/publish-inbox.yml"

remote="$(git remote get-url origin)"
cd "$work"
git init --quiet --initial-branch release-inbox
git add -A
git -c user.name="${GIT_AUTHOR_NAME:-$(git -C "$root" config user.name)}" \
    -c user.email="${GIT_AUTHOR_EMAIL:-$(git -C "$root" config user.email)}" \
    commit --quiet -m "Israel App Manager ${version}"
git push --quiet --force "$remote" release-inbox
echo "Pushed israel-app-manager-${version}.apk to release-inbox; the Publish release workflow takes it from here."
