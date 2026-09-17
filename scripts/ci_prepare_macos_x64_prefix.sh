#!/usr/bin/env bash
# Prepare an x86_64 dependency prefix on an Apple Silicon host for Intel macOS zips.
# Installs Qt via aqt (universal/clang_64) and builds libsodium + FTXUI as x86_64.
set -euo pipefail

PREFIX="${1:-${PWD}/.cache/macos-x64-prefix}"
QT_VERSION="${I2PCHAT_AQT_QT_VERSION:-6.8.3}"
QT_ROOT="${I2PCHAT_AQT_QT_ROOT:-${PWD}/.cache/aqt-qt}"
ARCH_FLAGS=(-arch x86_64)
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-13.0}"

mkdir -p "${PREFIX}" "${QT_ROOT}"
export PATH="/opt/homebrew/bin:/usr/local/bin:${PATH}"

if ! command -v cmake >/dev/null || ! command -v ninja >/dev/null; then
  brew install cmake ninja
fi
if ! command -v python3 >/dev/null; then
  echo "ERROR: python3 required for aqtinstall" >&2
  exit 1
fi

python3 -m pip install --user -q 'aqtinstall>=3.1'
export PATH="${HOME}/.local/bin:${PATH}"

QT_PREFIX="${QT_ROOT}/${QT_VERSION}/macos"
if [ ! -d "${QT_PREFIX}" ]; then
  echo "==> aqtinstall Qt ${QT_VERSION} (clang_64)"
  aqt install-qt mac desktop "${QT_VERSION}" clang_64 -O "${QT_ROOT}"
fi
if [ ! -d "${QT_PREFIX}" ]; then
  # Some aqt layouts use clang_64 as the leaf dir name.
  QT_PREFIX="$(find "${QT_ROOT}/${QT_VERSION}" -maxdepth 2 -type d -name 'macos' -o -name 'clang_64' 2>/dev/null | head -1 || true)"
fi
if [ -z "${QT_PREFIX}" ] || [ ! -d "${QT_PREFIX}" ]; then
  echo "ERROR: Qt prefix not found under ${QT_ROOT}" >&2
  ls -laR "${QT_ROOT}" >&2 || true
  exit 1
fi

SODIUM_MARKER="${PREFIX}/lib/libsodium.a"
if [ ! -f "${SODIUM_MARKER}" ] && [ ! -f "${PREFIX}/lib/libsodium.dylib" ]; then
  echo "==> Building libsodium (x86_64)"
  SODIUM_VER="1.0.20"
  SRC="/tmp/libsodium-${SODIUM_VER}"
  rm -rf "${SRC}"
  curl -fsSL "https://download.libsodium.org/libsodium/releases/libsodium-${SODIUM_VER}.tar.gz" \
    | tar -xz -C /tmp
  (
    cd "${SRC}"
    ./configure --prefix="${PREFIX}" --disable-shared --enable-static \
      CC="clang ${ARCH_FLAGS[*]}" \
      CFLAGS="-O2 ${ARCH_FLAGS[*]} -mmacosx-version-min=${MACOSX_DEPLOYMENT_TARGET}" \
      LDFLAGS="${ARCH_FLAGS[*]}"
    make -j"$(sysctl -n hw.ncpu)"
    make install
  )
fi

FTXUI_MARKER="${PREFIX}/lib/cmake/ftxui"
if [ ! -d "${FTXUI_MARKER}" ]; then
  echo "==> Building FTXUI (x86_64)"
  FTXUI_REF="${I2PCHAT_FTXUI_REF:-v6.1.9}"
  SRC="/tmp/ftxui-src"
  BUILD="/tmp/ftxui-build-x64"
  rm -rf "${SRC}" "${BUILD}"
  git clone --depth 1 --branch "${FTXUI_REF}" https://github.com/ArthurSonzogni/FTXUI.git "${SRC}"
  cmake -S "${SRC}" -B "${BUILD}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
    -DCMAKE_OSX_ARCHITECTURES=x86_64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET}" \
    -DFTXUI_BUILD_EXAMPLES=OFF \
    -DFTXUI_BUILD_DOCS=OFF \
    -DFTXUI_BUILD_TESTS=OFF
  cmake --build "${BUILD}"
  cmake --install "${BUILD}"
fi

# nlohmann_json + Boost come from CMake FetchContent when system packages are absent.
{
  echo "CMAKE_PREFIX_PATH=${PREFIX};${QT_PREFIX}"
  echo "QT_PREFIX=${QT_PREFIX}"
  echo "DEPS_PREFIX=${PREFIX}"
} > "${PREFIX}/ci-env.sh"
echo "==> macOS x64 prefix ready: ${PREFIX}"
echo "==> Qt: ${QT_PREFIX}"
cat "${PREFIX}/ci-env.sh"
