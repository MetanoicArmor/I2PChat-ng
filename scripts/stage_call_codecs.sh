#!/usr/bin/env bash
# Copy libopus (and optional libvpx) into vendor/codecs/<plat> for PyInstaller.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

plat=""
case "$(uname -s)" in
  Darwin)
    if [[ "$(uname -m)" == "x86_64" ]]; then plat="darwin-x64"; else plat="darwin-arm64"; fi
    ;;
  Linux)
    if [[ "$(uname -m)" =~ (aarch64|arm64) ]]; then plat="linux-aarch64"; else plat="linux-x86_64"; fi
    ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    plat="win-x64"
    ;;
  *)
    echo "Unsupported OS" >&2
    exit 1
    ;;
esac

DEST="$ROOT/vendor/codecs/$plat"
mkdir -p "$DEST"

copy_one() {
  local src="$1"
  local name
  name="$(basename "$src")"
  if [[ -f "$src" ]]; then
    cp -f "$src" "$DEST/$name"
    echo "staged $name -> $DEST/"
  fi
}

if [[ "$(uname -s)" == "Darwin" ]]; then
  for prefix in /opt/homebrew /usr/local; do
    copy_one "$prefix/lib/libopus.0.dylib" || true
    copy_one "$prefix/lib/libopus.dylib" || true
    copy_one "$prefix/lib/libvpx.9.dylib" || true
    copy_one "$prefix/lib/libvpx.dylib" || true
  done
elif [[ "$(uname -s)" == "Linux" ]]; then
  for lib in \
    /usr/lib/x86_64-linux-gnu/libopus.so.0 \
    /usr/lib/aarch64-linux-gnu/libopus.so.0 \
    /usr/lib64/libopus.so.0 \
    /usr/lib/libopus.so.0 \
    /usr/lib/x86_64-linux-gnu/libvpx.so.9 \
    /usr/lib/aarch64-linux-gnu/libvpx.so.9 \
    /usr/lib64/libvpx.so.9; do
    copy_one "$lib" || true
  done
fi

# Placeholder so the directory is tracked even without binaries.
if [[ ! -f "$DEST/.gitkeep" ]]; then
  : >"$DEST/.gitkeep"
fi

echo "Done. Install Python wheels separately: pip/uv add opuslib av"
ls -la "$DEST" || true
