#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS="$ROOT/settings.gradle.kts"
APP_DIR="$ROOT/app"
GRADLE_VERSION="9.4.1"
GRADLE_SHA256="2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$SETTINGS" ]] || fail "Missing $SETTINGS"
[[ -f "$APP_DIR/build.gradle.kts" ]] || fail "Android module not found at $APP_DIR"
grep -Eq 'include\(("|\x27):app("|\x27)\)|include[[:space:]]+("|\x27):app("|\x27)' "$SETTINGS" \
  || fail "settings.gradle.kts does not include the :app module"

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

if [[ -z "${ANDROID_HOME:-}" && ! -f "$ROOT/local.properties" ]]; then
  for candidate in \
    "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "/opt/android-sdk" \
    "/usr/local/lib/android/sdk" \
    "/home/ubuntu/Android/Sdk"
  do
    if [[ -d "$candidate/platforms" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${ANDROID_HOME:-}" && ! -f "$ROOT/local.properties" ]]; then
  fail "Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or create local.properties from local.properties.example."
fi

if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  GRADLE=("$ROOT/gradlew")
else
  CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/cp-companion"
  GRADLE_HOME="$CACHE_ROOT/gradle-$GRADLE_VERSION"
  GRADLE_BIN="$GRADLE_HOME/bin/gradle"

  if [[ ! -x "$GRADLE_BIN" ]]; then
    mkdir -p "$CACHE_ROOT"
    ZIP="$CACHE_ROOT/gradle-$GRADLE_VERSION-bin.zip"

    if [[ ! -f "$ZIP" ]]; then
      echo "Downloading Gradle $GRADLE_VERSION…" >&2
      if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --retry-all-errors \
          --output "$ZIP" \
          "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
      elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --output-document="$ZIP" \
          "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
      else
        fail "curl or wget is required to bootstrap Gradle $GRADLE_VERSION"
      fi
    fi

    command -v sha256sum >/dev/null 2>&1 \
      || fail "sha256sum is required to verify the Gradle distribution"
    echo "$GRADLE_SHA256  $ZIP" | sha256sum --check --status \
      || fail "Gradle distribution checksum verification failed; delete $ZIP and retry"

    command -v unzip >/dev/null 2>&1 \
      || fail "unzip is required to unpack Gradle $GRADLE_VERSION"
    rm -rf "$GRADLE_HOME"
    unzip -q "$ZIP" -d "$CACHE_ROOT"
  fi

  GRADLE=("$GRADLE_BIN")
fi

COMMON_ARGS=(
  --project-dir "$ROOT"
  --no-daemon
)

PROJECTS_OUTPUT="$("${GRADLE[@]}" "${COMMON_ARGS[@]}" projects --console=plain 2>&1)" || {
  echo "$PROJECTS_OUTPUT" >&2
  fail "Gradle could not load the project structure"
}
printf '%s\n' "$PROJECTS_OUTPUT" | grep -q "Project ':app'" \
  || {
    echo "$PROJECTS_OUTPUT" >&2
    fail "Gradle loaded the root project but did not discover :app"
  }

"${GRADLE[@]}" "${COMMON_ARGS[@]}" \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  "$@"

mapfile -t APKS < <(find "$APP_DIR/build/outputs/apk" -type f -name '*-debug.apk' | sort)
[[ ${#APKS[@]} -gt 0 ]] || fail "Build finished but no debug APK was found"

printf 'APKs created:\n'
printf '  %s\n' "${APKS[@]}"
