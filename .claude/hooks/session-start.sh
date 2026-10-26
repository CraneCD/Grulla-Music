#!/bin/bash
# SessionStart hook for Claude Code cloud sessions: makes `./gradlew assembleDebug`
# and `./gradlew test` work by installing JDK 17 and the Android SDK.
#
# Idempotent: every step is skipped when its result is already present, so the
# cached container only pays the download cost once.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-/opt/android-sdk}"
# Keep in sync with app/build.gradle.kts (compileSdk) and README.md.
SDK_PACKAGES=("platforms;android-37.0" "build-tools;36.0.0" "platform-tools" "ndk;29.0.14206865" "cmake;3.31.6")
CMDLINE_TOOLS_ZIPS=(
  "commandlinetools-linux-16111833_latest.zip"
  "commandlinetools-linux-13114758_latest.zip"
)

log() { echo "[session-start] $*" >&2; }

persist_env() {
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo "export $1=\"$2\"" >> "$CLAUDE_ENV_FILE"
  fi
}

# --- JDK 17 -------------------------------------------------------------------
JDK17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
if [ ! -x "$JDK17_HOME/bin/javac" ]; then
  log "Installing OpenJDK 17"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
fi
export JAVA_HOME="$JDK17_HOME"
persist_env JAVA_HOME "$JDK17_HOME"

# FluidSynth headers/library for the host tests of the native synth (tools/test-synth-native.sh).
if ! pkg-config --exists fluidsynth 2>/dev/null; then
  log "Installing libfluidsynth-dev"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq libfluidsynth-dev pkg-config >/dev/null || log "WARNING: libfluidsynth-dev not installed"
fi

# --- Android SDK --------------------------------------------------------------
SDKMANAGER="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  log "Installing Android command-line tools into $ANDROID_SDK_DIR"
  tmp="$(mktemp -d)"
  downloaded=""
  for zip in "${CMDLINE_TOOLS_ZIPS[@]}"; do
    if curl -fsSL --retry 3 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$zip"; then
      downloaded="$zip"
      break
    fi
  done
  if [ -z "$downloaded" ]; then
    log "WARNING: could not download the Android command-line tools from dl.google.com."
    log "WARNING: allow dl.google.com (Android SDK and Google Maven) in the environment's"
    log "WARNING: network settings; the Gradle build cannot run without it."
    rm -rf "$tmp"
    exit 0
  fi
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  rm -rf "$tmp"
fi

missing=()
for pkg in "${SDK_PACKAGES[@]}"; do
  if [ ! -d "$ANDROID_SDK_DIR/${pkg//;//}" ]; then
    missing+=("$pkg")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  log "Installing SDK packages: ${missing[*]}"
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" "${missing[@]}" >/dev/null
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
persist_env ANDROID_HOME "$ANDROID_SDK_DIR"
persist_env ANDROID_SDK_ROOT "$ANDROID_SDK_DIR"

# Warm the Gradle wrapper and dependency caches so the first build is fast.
# A failure here should not block the session; the build will report it.
if [ -x "$PROJECT_DIR/gradlew" ]; then
  log "Warming Gradle caches"
  (cd "$PROJECT_DIR" && ./gradlew --quiet help >/dev/null 2>&1) || log "WARNING: ./gradlew help failed"
fi
