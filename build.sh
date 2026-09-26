#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }
python3 "$ROOT/tools/validate_source.py"
python3 "$ROOT/tools/validate_repository.py"
if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -z "${ANDROID_HOME:-}" && ! -f "$ROOT/local.properties" ]]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /opt/android-sdk /usr/local/lib/android/sdk; do
    if [[ -d "$candidate/platforms" ]]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && ! -f "$ROOT/local.properties" ]]; then
  echo 'Set ANDROID_HOME or copy local.properties.example to local.properties and set sdk.dir.' >&2
  exit 1
fi
"$ROOT/gradlew" --project-dir "$ROOT" --no-daemon \
  :app:exportReleaseDependencies :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease "$@"
python3 "$ROOT/tools/generate_notices.py" --check
