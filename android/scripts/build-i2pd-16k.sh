#!/usr/bin/env bash
# Rebuild PurpleI2P libi2pd.so for arm64-v8a + x86_64 with 16 KiB ELF page
# alignment (NDK r28), then install into android/app/src/main/jniLibs/.
#
# Usage:
#   ./android/scripts/build-i2pd-16k.sh
# Optional:
#   I2PD_ANDROID_SRC=/path/to/i2pd-android   (default: /tmp/i2pd-android-16k)
#   ANDROID_NDK_HOME=...                     (default: SDK ndk/28.2.13676358)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${I2PD_ANDROID_SRC:-/tmp/i2pd-android-16k}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
export ANDROID_HOME="${SDK}"
export ANDROID_SDK_ROOT="${SDK}"
export ANDROID_NDK_HOME="${NDK}"
export ANDROID_NDK_ROOT="${NDK}"

if [ ! -d "${NDK}" ]; then
  echo "ERROR: NDK not found at ${NDK}" >&2
  exit 1
fi

HOST_TAG="$(ls "${NDK}/toolchains/llvm/prebuilt" | head -1)"
NDK_BIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
export PATH="${NDK_BIN}:${PATH}"

echo "==> NDK ${NDK} (host ${HOST_TAG})"
echo "==> Source ${SRC}"

if [ ! -d "${SRC}/binary/jni/i2pd/libi2pd" ] || [ ! -d "${SRC}/binary/jni/openssl" ]; then
  echo "==> Cloning i2pd-android 2.61.0 with submodules"
  rm -rf "${SRC}"
  git clone --depth 1 --branch 2.61.0 https://github.com/PurpleI2P/i2pd-android.git "${SRC}"
  git -C "${SRC}" submodule update --init --depth 1 --recursive
fi

JNI="${SRC}/binary/jni"
APP_JNI="${SRC}/app/jni"

# Boost-for-Android only lists up to NDK 27.0 — accept 28.x.
python3 - <<PY
from pathlib import Path
p = Path("${JNI}/boost/build-android.sh")
t = p.read_text()
old = '"22.1"|"23.0"|"23.1"|"23.2"|"25.0"|"25.1"|"25.2"|"26.0"|"26.1"|"26.2"|"26.3"|"27.0"'
new = old + '|"27.1"|"27.2"|"28.0"|"28.1"|"28.2"'
if '|"28.2"' not in t:
    if old not in t:
        raise SystemExit("boost NDK version list not found")
    p.write_text(t.replace(old, new))
    print("patched Boost-for-Android for NDK 28")
else:
    print("Boost-for-Android already allows NDK 28")
PY

echo "==> Patching Application.mk / Android.mk for 16 KiB + arm64/x86_64 only"
cat > "${APP_JNI}/Application.mk" <<'EOF'
APP_ABI := arm64-v8a x86_64
APP_PLATFORM := android-26
NDK_TOOLCHAIN_VERSION := clang
APP_STL := c++_static
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

APP_CPPFLAGS += -std=c++17 -fexceptions -frtti
APP_CPPFLAGS += -DANDROID -D__ANDROID__ -DUSE_UPNP -Wno-deprecated-declarations
APP_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

IFADDRS_PATH  = $(NDK_MODULE_PATH)/android-ifaddrs
BOOST_PATH    = $(NDK_MODULE_PATH)/boost
MINIUPNP_PATH = $(NDK_MODULE_PATH)/miniupnp
OPENSSL_PATH  = $(NDK_MODULE_PATH)/openssl
I2PD_SRC_PATH = $(NDK_MODULE_PATH)/i2pd

LIB_SRC_PATH        = $(I2PD_SRC_PATH)/libi2pd
LIB_CLIENT_SRC_PATH = $(I2PD_SRC_PATH)/libi2pd_client
LANG_SRC_PATH       = $(I2PD_SRC_PATH)/i18n
DAEMON_SRC_PATH     = $(I2PD_SRC_PATH)/daemon
EOF

python3 - <<PY
from pathlib import Path
p = Path("${APP_JNI}/Android.mk")
t = p.read_text()
needle = "LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384\n"
if needle not in t:
    t = t.replace("LOCAL_LDLIBS := -lz\n", "LOCAL_LDLIBS := -lz\n" + needle)
    p.write_text(t)
print("Android.mk link flags ok")
PY

# Darwin-friendly dep scripts
python3 - <<PY
from pathlib import Path
host = "${HOST_TAG}"
jobs = "${JOBS}"
jni = Path("${JNI}")

def write_openssl():
    t = (jni / "build_openssl.sh").read_text()
    t = t.replace("linux-x86_64", host).replace("\$(nproc)", jobs).replace("\`nproc\`", jobs)
    out = jni / "build_openssl.darwin.sh"
    out.write_text(t)
    out.chmod(0o755)

def write_boost():
    t = (jni / "build_boost.sh").read_text()
    t = t.replace("NCPU=\$(nproc)", f"NCPU={jobs}")
    t = t.replace("sed -i -E -e", "sed -i '' -E -e")
    old = "x86_64)\n\t\t\t\tAPI=21\n\t\t\t\tTARGET=x86_64\n\t\t\t\tbuild_one"
    new = "x86_64)\n\t\t\t\tAPI=21\n\t\t\t\tTARGET=x86_64\n\t\t\t\tCPU=x86_64\n\t\t\t\tbuild_one"
    if "CPU=x86_64\n\t\t\t\tbuild_one" not in t:
        t = t.replace(old, new)
    out = jni / "build_boost.darwin.sh"
    out.write_text(t)
    out.chmod(0o755)

write_openssl()
write_boost()
print("darwin dep scripts ready")
PY

cd "${JNI}"

build_boost_one() {
  local arch="$1"
  local save=""
  # Boost-for-Android cleans build/out on each arch — preserve siblings.
  if [ -d boost/build/out ]; then
    save="$(mktemp -d)"
    cp -a boost/build/out/. "${save}/" || true
  fi
  ./build_boost.darwin.sh "${arch}"
  mkdir -p boost/build/out
  if [ -n "${save}" ]; then
    for d in "${save}"/*; do
      [ -d "${d}" ] || continue
      name="$(basename "${d}")"
      if [ ! -d "boost/build/out/${name}" ]; then
        cp -a "${d}" "boost/build/out/${name}"
      fi
    done
    rm -rf "${save}"
  fi
}

if [ ! -f boost/build/out/arm64-v8a/lib/libboost_program_options.a ] || \
   [ ! -f boost/build/out/x86_64/lib/libboost_program_options.a ]; then
  echo "==> Building Boost (arm64 + x86_64) — long"
  build_boost_one x86_64
  build_boost_one arm64
else
  echo "==> Boost already built"
fi

if [ ! -f openssl/out/arm64-v8a/lib/libcrypto.a ] || \
   [ ! -f openssl/out/x86_64/lib/libcrypto.a ]; then
  echo "==> Building OpenSSL (arm64 + x86_64)"
  ./build_openssl.darwin.sh arm64 x86_64
else
  echo "==> OpenSSL already built"
fi

if [ ! -f miniupnp/miniupnpc/out/arm64-v8a/lib/libminiupnpc.a ] || \
   [ ! -f miniupnp/miniupnpc/out/x86_64/lib/libminiupnpc.a ]; then
  echo "==> Building miniupnpc (arm64 + x86_64)"
  ./build_miniupnpc.sh arm64 x86_64
else
  echo "==> miniupnpc already built"
fi

echo "==> ndk-build libi2pd.so"
rm -rf "${SRC}/app/libs" "${SRC}/app/obj"
"${NDK}/ndk-build" -C "${APP_JNI}" \
  NDK_PROJECT_PATH="${SRC}/app" \
  NDK_APPLICATION_MK="${APP_JNI}/Application.mk" \
  APP_BUILD_SCRIPT="${APP_JNI}/Android.mk" \
  NDK_MODULE_PATH="${JNI}" \
  -j"${JOBS}"

RE="${NDK_BIN}/llvm-readelf"
for abi in arm64-v8a x86_64; do
  so="${SRC}/app/libs/${abi}/libi2pd.so"
  if [ ! -f "${so}" ]; then
    echo "ERROR: missing ${so}" >&2
    exit 1
  fi
  echo "==> Verify ${abi}"
  "${RE}" -lW "${so}" | awk '/LOAD/{print}'
  while read -r al; do
    val=$((al))
    if [ "${val}" -lt 16384 ]; then
      echo "ERROR: ${so} LOAD align ${al} < 16384" >&2
      exit 1
    fi
  done < <("${RE}" -lW "${so}" | awk '/LOAD/{print $NF}')
  dest="${ROOT}/android/app/src/main/jniLibs/${abi}/libi2pd.so"
  mkdir -p "$(dirname "${dest}")"
  cp -f "${so}" "${dest}"
  "${NDK_BIN}/llvm-strip" -s "${dest}" || true
  echo "installed ${dest} ($(wc -c < "${dest}") bytes)"
done

echo "==> Done. Rebuild the app with ./build-android.sh (pageSizeCompat not required)."
