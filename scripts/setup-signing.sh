#!/usr/bin/env bash
# One-time setup of the release signing key for Drawify.
#
# Creates a keystore on THIS machine (never commit it), then stores it and its password as
# GitHub Actions secrets so CI can sign every release with the same key. Android only installs an
# update over an existing app when both are signed with the same key, so back the keystore up.
#
# Needs: keytool (comes with any JDK) and the GitHub CLI (`gh auth login` done).
# Usage:  ./scripts/setup-signing.sh            (uses ~/.drawify-signing)
#         DRAWIFY_SIGNING_DIR=/some/dir ./scripts/setup-signing.sh
set -euo pipefail

REPO="stefanruvceski/drawify"
DIR="${DRAWIFY_SIGNING_DIR:-$HOME/.drawify-signing}"
KS="$DIR/drawify-release.jks"
PWFILE="$DIR/keystore-password.txt"
ALIAS="drawify"

command -v keytool >/dev/null || { echo "keytool not found - install a JDK (e.g. 'brew install openjdk')." >&2; exit 1; }
command -v gh >/dev/null || { echo "gh not found - install the GitHub CLI and run 'gh auth login'." >&2; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"

if [ -f "$KS" ]; then
  echo "Reusing existing keystore $KS"
  PW="$(cat "$PWFILE")"
else
  PW="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
  keytool -genkeypair -v \
    -keystore "$KS" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PW" -keypass "$PW" \
    -dname "CN=Drawify, O=stefanruvceski"
  printf '%s' "$PW" > "$PWFILE"
  chmod 600 "$KS" "$PWFILE"
  echo "Created $KS"
fi

base64 < "$KS" | tr -d '\n' | gh secret set DRAWIFY_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PW" | gh secret set DRAWIFY_KEYSTORE_PASSWORD --repo "$REPO"

echo
echo "Done. Secrets DRAWIFY_KEYSTORE_BASE64 and DRAWIFY_KEYSTORE_PASSWORD are set on $REPO."
echo "Back up $DIR (keystore + password) somewhere safe - losing it means users must reinstall."
