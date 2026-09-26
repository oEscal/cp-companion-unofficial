#!/usr/bin/env bash
# Build with the maintainer's existing key; never generate or commit a release key here.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for variable in CP_RELEASE_STORE_PASSWORD CP_RELEASE_KEY_ALIAS CP_RELEASE_CERT_SHA256; do
  [[ -n "${!variable:-}" ]] || { echo "Missing $variable" >&2; exit 1; }
done
if [[ -z "${CP_RELEASE_KEY_PASSWORD:-}" ]]; then
  export CP_RELEASE_KEY_PASSWORD="$CP_RELEASE_STORE_PASSWORD"
fi
RELEASE_TEMP="$(mktemp -d)"
trap 'rm -rf "$RELEASE_TEMP"' EXIT
if [[ -n "${CP_RELEASE_KEYSTORE_BASE64:-}" ]]; then
  [[ -z "${CP_RELEASE_STORE_FILE:-}" ]] || { echo 'Choose either a keystore file or base64 input.' >&2; exit 1; }
  export CP_RELEASE_STORE_FILE="$RELEASE_TEMP/release.jks"
  python3 - <<'PY'
import base64, os, pathlib
pathlib.Path(os.environ['CP_RELEASE_STORE_FILE']).write_bytes(base64.b64decode(os.environ['CP_RELEASE_KEYSTORE_BASE64'], validate=True))
PY
fi
[[ -f "${CP_RELEASE_STORE_FILE:-}" ]] || { echo 'Set CP_RELEASE_STORE_FILE or CP_RELEASE_KEYSTORE_BASE64.' >&2; exit 1; }
# Do not inherit leftover output from an earlier release attempt.
rm -rf "$ROOT/dist"
mkdir -p "$ROOT/dist"
"$ROOT/build.sh"
SDK_PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_PATH" && -f "$ROOT/local.properties" ]]; then
  SDK_PATH="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")"
fi
[[ -n "$SDK_PATH" ]] || { echo 'Set ANDROID_HOME for release signing verification.' >&2; exit 1; }
APKSIGNER="$SDK_PATH/build-tools/37.0.0/apksigner"
[[ -x "$APKSIGNER" ]] || { echo 'Install Android Build Tools 37.0.0.' >&2; exit 1; }
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$ROOT/dist/SIGNING_CERTIFICATE.txt"
python3 "$ROOT/tools/verify_signer.py" "$ROOT/dist/SIGNING_CERTIFICATE.txt"

cp "$APK" "$ROOT/dist/cp-companion.apk"
echo 'Signed APK verified against the expected certificate; output is in dist/.'
