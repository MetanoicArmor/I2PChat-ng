#!/usr/bin/env bash
# Verify the bundled i2pd router binaries against the checksum manifests
# committed next to them. The binaries are opaque blobs that end up inside
# every release artifact and every APK; the manifests are what a reviewer can
# actually read in a diff, so a binary that changes without its manifest line
# changing is a build failure.
#
#   vendor/i2pd/i2pd.sha256                       desktop routers (optional blobs)
#   android/app/src/main/jniLibs/libi2pd.sha256   Android JNI routers
#
# Usage: scripts/verify_bundled_i2pd.sh [--android-only|--desktop-only]
#
# A desktop binary that is absent is skipped (local builds may not stage it),
# but one that is present must match. Android binaries are required.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

check_manifest() {
  local dir="$1" manifest="$2" require_all="$3"
  local failures=0 checked=0
  if [[ ! -f "${dir}/${manifest}" ]]; then
    echo "ERROR: missing manifest ${dir}/${manifest}" >&2
    return 1
  fi
  while read -r expected rel; do
    [[ -z "${expected}" || "${expected}" == \#* ]] && continue
    local path="${dir}/${rel}"
    if [[ ! -f "${path}" ]]; then
      if [[ "${require_all}" == "1" ]]; then
        echo "ERROR: ${path} is missing" >&2
        failures=$((failures + 1))
      else
        echo "skip  ${rel} (not staged)"
      fi
      continue
    fi
    local actual
    actual="$(sha256_of "${path}")"
    if [[ "${actual}" != "${expected}" ]]; then
      echo "ERROR: checksum mismatch for ${path}" >&2
      echo "       expected ${expected}" >&2
      echo "       actual   ${actual}" >&2
      failures=$((failures + 1))
    else
      echo "ok    ${rel}"
      checked=$((checked + 1))
    fi
  done < "${dir}/${manifest}"
  if [[ "${failures}" -gt 0 ]]; then
    return 1
  fi
  echo "verified ${checked} file(s) against ${manifest}"
}

status=0
if [[ "${MODE}" != "--android-only" ]]; then
  check_manifest "${ROOT}/vendor/i2pd" "i2pd.sha256" 0 || status=1
fi
if [[ "${MODE}" != "--desktop-only" ]]; then
  check_manifest "${ROOT}/android/app/src/main/jniLibs" "libi2pd.sha256" 1 || status=1
fi
exit "${status}"
