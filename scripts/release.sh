#!/usr/bin/env bash
# Build, verify, and publish a GitHub Release for ST VPN.
#
# Requirements (none of them live in this file or this repo):
#   - release.keystore + signing.properties present at repo root (gitignored)
#   - `gh` CLI installed and already authenticated (`gh auth login`)
#
# Usage: ./scripts/release.sh [tag]
#   If [tag] is omitted, it's derived from versionName in build.gradle.kts.

set -euo pipefail

REPO="stservice1/app"
APK_DIR="app/build/outputs/apk/meta/release"
APK_PATTERN="cmfa-*-meta-universal-release.apk"

log(){ echo "[release] $*"; }
fail(){ echo "[release][error] $*" >&2; exit 1; }

cd "$(dirname "$0")/.."

command -v gh &> /dev/null || fail "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/"
gh auth status &> /dev/null || fail "gh is not authenticated. Run: gh auth login"

[[ -f release.keystore && -f signing.properties ]] || \
  fail "release.keystore / signing.properties not found at repo root. Without them the build falls back to debug signing, which must never be published."

VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' build.gradle.kts | head -n1)
[[ -n "$VERSION" ]] || fail "Could not read versionName from build.gradle.kts"

TAG="${1:-v$VERSION}"
[[ "$TAG" == v* ]] || TAG="v$TAG"

if [[ "$TAG" != "v$VERSION" ]]; then
  log "WARNING: requested tag $TAG does not match build.gradle.kts versionName ($VERSION)"
fi

log "Building release APK (versionName=$VERSION)"
./gradlew :app:assembleMetaRelease

APK_SRC=$(ls -1 "$APK_DIR"/$APK_PATTERN 2>/dev/null | head -n1 || true)
[[ -n "$APK_SRC" ]] || fail "APK not found in $APK_DIR after build"
APK_SRC=$(realpath "$APK_SRC")
APK_SIZE=$(du -h "$APK_SRC" | cut -f1)
log "Built: $APK_SRC ($APK_SIZE)"

# Refuse to publish a debug-signed build.
JARSIGNER="$(command -v jarsigner || echo "${JAVA_HOME:-}/bin/jarsigner")"
[[ -x "$JARSIGNER" ]] || fail "jarsigner not found. Set JAVA_HOME or add it to PATH."
CERT_CN=$("$JARSIGNER" -verify -verbose -certs "$APK_SRC" 2>/dev/null | grep -m1 "CN=" || true)
[[ -n "$CERT_CN" ]] || fail "Could not read signing certificate from APK."
if [[ "$CERT_CN" == *"Android Debug"* ]]; then
  fail "APK is debug-signed. Refusing to publish."
fi
log "Signature OK: $CERT_CN"

# 16 KB page size alignment check (see docs/16kb-page-size notes in play-console-notes.txt).
ZIPALIGN="$(command -v zipalign || find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -iname zipalign 2>/dev/null | sort -V | tail -1)"
if [[ -n "$ZIPALIGN" ]]; then
  "$ZIPALIGN" -c -P 16 -v 4 "$APK_SRC" > /dev/null || fail "APK failed 16 KB page-size alignment check"
  log "16 KB page-size alignment OK"
else
  log "WARNING: zipalign not found, skipping 16 KB alignment check"
fi

APK_NAME="stapp-${VERSION}.apk"
STAGING="$(mktemp -d)"
cp "$APK_SRC" "$STAGING/$APK_NAME"

NOTES="## ST VPN ${TAG}

### Installation
Download the APK below and install on your Android device.

### Build info
- Version: ${VERSION}
- Build date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
- APK size: ${APK_SIZE}
- Architecture: Universal (all devices)
- 16 KB page size: supported"

if gh release view "$TAG" --repo "$REPO" &> /dev/null; then
  log "Release $TAG already exists"
  read -p "Delete and recreate? (y/N): " -n 1 -r
  echo
  [[ $REPLY =~ ^[Yy]$ ]] || fail "Release already exists. Aborting."
  gh release delete "$TAG" --repo "$REPO" --yes
fi

log "Creating GitHub Release: $TAG"
gh release create "$TAG" \
  --repo "$REPO" \
  --title "ST VPN ${TAG}" \
  --notes "$NOTES" \
  "$STAGING/$APK_NAME"

rm -rf "$STAGING"

log "Done: https://github.com/$REPO/releases/tag/$TAG"
