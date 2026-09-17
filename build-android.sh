#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
REPO_ROOT="$(pwd)"
ANDROID_DIR="${REPO_ROOT}/android"

VERSION_FILE="VERSION"
if [ ! -f "${VERSION_FILE}" ]; then
  echo "ERROR: VERSION file not found: ${VERSION_FILE}" >&2
  exit 1
fi
RELEASE_VERSION="$(tr -d '\r\n' < "${VERSION_FILE}")"
if [ -z "${RELEASE_VERSION}" ]; then
  echo "ERROR: VERSION file is empty: ${VERSION_FILE}" >&2
  exit 1
fi

BUILD_TYPE="debug"
DO_INSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --debug) BUILD_TYPE="debug" ;;
    --release) BUILD_TYPE="release" ;;
    --install) DO_INSTALL=1 ;;
    -h|--help)
      cat <<'EOF'
Build the I2PChat Android APK (Kotlin + JNI + bundled i2pd).

Usage:
  ./build-android.sh [--debug|--release] [--install]

  --debug     installable debug APK (default)
  --release   release APK (signed: upload keystore via I2PCHAT_ANDROID_* env, else Android debug keystore)
  --install   adb install -r the built APK

Needs JDK 17+, Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT), NDK and CMake.
The first native configure downloads Boost, nlohmann/json and libsodium.
EOF
      exit 0
      ;;
    *)
      echo "ERROR: unknown option: $1 (try --help)" >&2
      exit 1
      ;;
  esac
  shift
done

if [ ! -x "${ANDROID_DIR}/gradlew" ] && [ ! -f "${ANDROID_DIR}/gradlew" ]; then
  echo "ERROR: android/gradlew is missing" >&2
  exit 1
fi
chmod +x "${ANDROID_DIR}/gradlew" 2>/dev/null || true

first_existing_dir() {
  local candidate
  for candidate in "$@"; do
    if [ -n "${candidate}" ] && [ -d "${candidate}" ]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

SDK_DIR="$(first_existing_dir \
  "${ANDROID_HOME:-}" \
  "${ANDROID_SDK_ROOT:-}" \
  "${HOME}/Android/Sdk" \
  "${HOME}/Library/Android/sdk" \
  "/opt/android-sdk" \
  "${LOCALAPPDATA:-}/Android/Sdk" \
  "${USERPROFILE:-}/AppData/Local/Android/Sdk" \
  )" || true

if [ -z "${SDK_DIR}" ] && [ -f "${ANDROID_DIR}/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "${ANDROID_DIR}/local.properties" | tr -d '\r' | tail -n 1)"
  SDK_DIR="${SDK_DIR//\\//}"
fi

if [ -z "${SDK_DIR}" ] || [ ! -d "${SDK_DIR}" ]; then
  echo "ERROR: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi

SDK_DIR="${SDK_DIR//\\//}"
export ANDROID_HOME="${SDK_DIR}"
export ANDROID_SDK_ROOT="${SDK_DIR}"
export PATH="${SDK_DIR}/platform-tools:${SDK_DIR}/cmdline-tools/latest/bin:${PATH}"

if [ ! -f "${ANDROID_DIR}/local.properties" ]; then
  printf 'sdk.dir=%s\n' "${SDK_DIR}" > "${ANDROID_DIR}/local.properties"
  echo "==> wrote android/local.properties"
fi

# macOS ships /usr/bin/java as a stub that prints "Unable to locate a Java Runtime"
# without a JDK. Prefer an explicit JAVA_HOME, then Android Studio's JBR, then Homebrew.
java_works() {
  local bin="$1"
  [ -x "${bin}" ] || return 1
  "${bin}" -version >/dev/null 2>&1
}

resolve_java_home() {
  local candidate
  if [ -n "${JAVA_HOME:-}" ] && java_works "${JAVA_HOME}/bin/java"; then
    printf '%s\n' "${JAVA_HOME}"
    return 0
  fi
  for candidate in \
      "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      "/Applications/Android Studio.app/Contents/jre/Contents/Home" \
      "$(brew --prefix openjdk@17 2>/dev/null)/libexec/openjdk.jdk/Contents/Home" \
      "$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home" \
      "$(brew --prefix openjdk 2>/dev/null)/libexec/openjdk.jdk/Contents/Home" \
      "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
      "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
      "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  do
    if [ -n "${candidate}" ] && java_works "${candidate}/bin/java"; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
    if [ -n "${candidate}" ] && java_works "${candidate}/bin/java"; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  fi
  return 1
}

if ! JAVA_HOME="$(resolve_java_home)"; then
  echo "ERROR: Java 17+ is required." >&2
  echo "  Install a JDK, e.g.: brew install openjdk@17" >&2
  echo "  or open Android Studio once, then re-run this script." >&2
  echo "  Or set JAVA_HOME to a JDK home that contains bin/java." >&2
  exit 1
fi
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "    JAVA_HOME ${JAVA_HOME}"

NDK_DIR="$(first_existing_dir \
  "${ANDROID_NDK_HOME:-}" \
  "${SDK_DIR}/ndk-bundle" \
  )" || true
if [ -z "${NDK_DIR}" ] && [ -d "${SDK_DIR}/ndk" ]; then
  NDK_DIR="$(find "${SDK_DIR}/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1 || true)"
fi
if [ -z "${NDK_DIR}" ]; then
  echo "WARN: NDK not found under ${SDK_DIR}/ndk — Gradle will fail if CMake cannot download it." >&2
else
  export ANDROID_NDK_HOME="${NDK_DIR}"
fi

I2PD_ARM="${ANDROID_DIR}/app/src/main/jniLibs/arm64-v8a/libi2pd.so"
I2PD_X64="${ANDROID_DIR}/app/src/main/jniLibs/x86_64/libi2pd.so"
if [ ! -f "${I2PD_ARM}" ] || [ ! -f "${I2PD_X64}" ]; then
  echo "ERROR: bundled libi2pd.so is missing. Run ./android/scripts/fetch-i2pd.sh" >&2
  exit 1
fi

# Do NOT post-process libi2pd.so with align-elf-16k.py: bumping p_align without a
# real 16 KiB relink leaves RX/RW segments on the same 16 KiB page, and on Android
# 15+ that disables pageSizeCompat while still failing dlopen.

ensure_debug_keystore() {
  local ks="${HOME}/.android/debug.keystore"
  mkdir -p "${HOME}/.android"
  if [ -f "${ks}" ]; then
    return 0
  fi
  if ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: keytool not found; cannot create debug.keystore for release signing" >&2
    exit 1
  fi
  echo "==> Creating ${ks} (release signing fallback)"
  keytool -genkeypair -v \
    -keystore "${ks}" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
}

if [ "${BUILD_TYPE}" = "release" ] && [ -z "${I2PCHAT_ANDROID_KEYSTORE:-}" ]; then
  ensure_debug_keystore
fi

echo "==> Building I2PChat Android ${RELEASE_VERSION} (${BUILD_TYPE})"
echo "    SDK ${SDK_DIR}"
if [ -n "${NDK_DIR}" ]; then
  echo "    NDK ${NDK_DIR}"
fi

if [ "${BUILD_TYPE}" = "release" ]; then
  GRADLE_TASK="assembleRelease"
  APK_DIR="${ANDROID_DIR}/app/build/outputs/apk/release"
else
  GRADLE_TASK="assembleDebug"
  APK_DIR="${ANDROID_DIR}/app/build/outputs/apk/debug"
fi

(
  cd "${ANDROID_DIR}"
  ./gradlew --no-daemon "${GRADLE_TASK}"
)

APK_SRC="$(find "${APK_DIR}" -name '*.apk' -type f | sort | tail -n 1 || true)"

if [ -z "${APK_SRC}" ] || [ ! -f "${APK_SRC}" ]; then
  echo "ERROR: APK not produced" >&2
  exit 1
fi

mkdir -p "${REPO_ROOT}/dist"
APK_DEST="${REPO_ROOT}/dist/I2PChat-android-v${RELEASE_VERSION}-${BUILD_TYPE}.apk"
cp -f "${APK_SRC}" "${APK_DEST}"

file_sha256() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    return 1
  fi
}

echo "==> APK ${APK_DEST}"
if HASH="$(file_sha256 "${APK_DEST}")"; then
  printf '%s  %s\n' "${HASH}" "$(basename "${APK_DEST}")" > "${APK_DEST}.sha256"
  echo "    SHA256 ${HASH}"
fi

if [ "${DO_INSTALL}" -eq 1 ]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not on PATH (${SDK_DIR}/platform-tools)" >&2
    exit 1
  fi
  echo "==> Installing on the first adb device"
  adb install -r "${APK_DEST}"
fi
