#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS="$ROOT/settings.gradle.kts"
APP_DIR="$ROOT/app"
WRAPPER_PROPERTIES="$ROOT/gradle/wrapper/gradle-wrapper.properties"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$SETTINGS" ]] || fail "Missing $SETTINGS"
[[ -f "$APP_DIR/build.gradle.kts" ]] || fail "Android module not found at $APP_DIR"
[[ -f "$WRAPPER_PROPERTIES" ]] || fail "Missing $WRAPPER_PROPERTIES"
if ! grep -Fq 'include(":app")' "$SETTINGS" &&
   ! grep -Fq "include(':app')" "$SETTINGS" &&
   ! grep -Eq 'include[[:space:]]+":app"' "$SETTINGS" &&
   ! grep -Eq "include[[:space:]]+':app'" "$SETTINGS"; then
  fail "settings.gradle.kts does not include the :app module"
fi

GRADLE_URL="$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPERTIES" | sed 's/\\:/:/g')"
GRADLE_SHA256="$(sed -n 's/^distributionSha256Sum=//p' "$WRAPPER_PROPERTIES")"
GRADLE_ARCHIVE="$(basename "$GRADLE_URL")"
case "$GRADLE_URL" in
  "https://services.gradle.org/distributions/$GRADLE_ARCHIVE") ;;
  *) fail "Unexpected Gradle distribution URL in $WRAPPER_PROPERTIES" ;;
esac
case "$GRADLE_ARCHIVE" in
  gradle-*-bin.zip) ;;
  *) fail "Unexpected Gradle distribution archive in $WRAPPER_PROPERTIES" ;;
esac
[[ "$GRADLE_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] \
  || fail "Missing or invalid Gradle SHA-256 checksum in $WRAPPER_PROPERTIES"
GRADLE_VERSION="${GRADLE_ARCHIVE#gradle-}"
GRADLE_VERSION="${GRADLE_VERSION%-bin.zip}"

command -v python3 >/dev/null 2>&1 \
  || fail "python3 is required for source validation"
python3 "$ROOT/tools/validate_source.py"

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
    ZIP="$CACHE_ROOT/$GRADLE_ARCHIVE"

    command -v sha256sum >/dev/null 2>&1 \
      || fail "sha256sum is required to verify the Gradle distribution"

    command -v unzip >/dev/null 2>&1 \
      || fail "unzip is required to unpack Gradle $GRADLE_VERSION"

    if [[ ! -f "$ZIP" ]]; then
      echo "Downloading Gradle $GRADLE_VERSION…" >&2
      if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --retry-all-errors \
          --output "$ZIP" \
          "$GRADLE_URL"
      elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --output-document="$ZIP" \
          "$GRADLE_URL"
      else
        fail "curl or wget is required to bootstrap Gradle $GRADLE_VERSION"
      fi
    fi

    if ! printf '%s  %s\n' "$GRADLE_SHA256" "$ZIP" | sha256sum --check --status; then
      rm -f "$ZIP"
      fail "Gradle distribution checksum verification failed"
    fi
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
  :app:lintDebug \
  :app:assembleDebugAndroidTest \
  :app:assembleDebug \
  :app:assembleRelease \
  "$@"

mapfile -t APKS < <(find "$APP_DIR/build/outputs/apk" -type f -name '*-debug.apk' | sort)
[[ ${#APKS[@]} -gt 0 ]] || fail "Build finished but no debug APK was found"

printf 'APKs created:\n'
printf '  %s\n' "${APKS[@]}"
