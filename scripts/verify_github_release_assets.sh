#!/usr/bin/env bash
# Fail if a GitHub Release is missing desktop/Android/.deb assets (parity with PyQt6 releases).
set -euo pipefail

REPO="${I2PCHAT_GH_REPO:-MetanoicArmor/I2PChat-ng}"
TAG=""
SKIP_DEB=0
SKIP_GPG=0

usage() {
  echo "Usage: $0 [--skip-deb] [--skip-gpg] vX.Y.Z" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-deb) SKIP_DEB=1; shift ;;
    --skip-gpg) SKIP_GPG=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      if [[ -z "${TAG}" ]]; then
        TAG="$1"
        shift
      else
        usage
        exit 2
      fi
      ;;
  esac
done

if [[ -z "${TAG}" ]]; then
  usage
  exit 2
fi
TAG="${TAG#v}"
VER="${TAG}"
TAG="v${VER}"

if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: gh CLI required" >&2
  exit 2
fi

HAVE_FILE="$(mktemp)"
trap 'rm -f "${HAVE_FILE}"' EXIT
gh release view "${TAG}" --repo "${REPO}" --json assets -q '.assets[].name' | sort -u > "${HAVE_FILE}"

have() {
  local want="$1"
  grep -Fxq "${want}" "${HAVE_FILE}"
}

missing=()
need() {
  if ! have "$1"; then
    missing+=("$1")
  fi
}

# Desktop zips (same names as 1.4.x + Android)
for arch in x64; do
  need "I2PChat-windows-${arch}-v${VER}.zip"
  need "I2PChat-windows-tui-${arch}-v${VER}.zip"
  need "I2PChat-windows-${arch}-winget-v${VER}.zip"
  need "I2PChat-windows-tui-${arch}-winget-v${VER}.zip"
done
for arch in arm64 x64; do
  need "I2PChat-macOS-${arch}-v${VER}.zip"
  need "I2PChat-macOS-${arch}-tui-v${VER}.zip"
done
for arch in x86_64 aarch64; do
  need "I2PChat-linux-${arch}-v${VER}.zip"
  need "I2PChat-linux-${arch}-tui-v${VER}.zip"
done
need "I2PChat-android-v${VER}.apk"

# Checksums + signature
need "SHA256SUMS"
if [[ "${SKIP_GPG}" -eq 0 ]]; then
  need "SHA256SUMS.asc"
fi
need "SHA256SUMS.android"
need "SHA256SUMS.linux-aarch64"
need "SHA256SUMS.linux-x86_64"
need "SHA256SUMS.macos-arm64"
need "SHA256SUMS.macos-x64"
need "SHA256SUMS.windows-x64"

if [[ "${SKIP_DEB}" -eq 0 ]]; then
  need "i2pchat_${VER}_amd64.deb"
  need "i2pchat_${VER}_arm64.deb"
  need "i2pchat-tui_${VER}_amd64.deb"
  need "i2pchat-tui_${VER}_arm64.deb"
  need "i2pchat_${VER}_x86_64.rpm"
fi

HAVE_COUNT="$(wc -l < "${HAVE_FILE}" | tr -d ' ')"
echo "Release ${TAG} on ${REPO}: ${HAVE_COUNT} asset(s) present."
if ((${#missing[@]} == 0)); then
  echo "OK: full artifact set present."
  exit 0
fi

echo "MISSING (${#missing[@]}):"
printf '  - %s\n' "${missing[@]}"
exit 1
