#!/usr/bin/env bash
# Build one SHA256SUMS for a GitHub Release from the per-platform
# SHA256SUMS.<platform> files the build jobs uploaded, sign it with the release
# key when one is available, and upload SHA256SUMS (+ SHA256SUMS.asc).
#
# Usage: scripts/sign_release_checksums.sh vX.Y.Z
#
# Environment:
#   I2PCHAT_GH_REPO           owner/repo (default MetanoicArmor/I2PChat-ng)
#   GH_TOKEN                  for gh
#   RELEASE_GPG_PRIVATE_KEY   armored secret key; when empty, nothing is signed
#   RELEASE_GPG_PASSPHRASE    optional passphrase for that key
#
# The key is imported into a throwaway GNUPGHOME that is removed on exit, so
# nothing about it survives the job. Signing is all-or-nothing: with a key
# present, a failed signature fails the script rather than quietly publishing
# an unsigned set.
set -euo pipefail

REPO="${I2PCHAT_GH_REPO:-MetanoicArmor/I2PChat-ng}"
TAG="${1:-}"
if [[ -z "${TAG}" ]]; then
  echo "Usage: $0 vX.Y.Z" >&2
  exit 2
fi
TAG="${TAG#v}"
TAG="${TAG#.}"
if ! [[ "${TAG}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: tag must be semver X.Y.Z, got: ${TAG}" >&2
  exit 2
fi
TAG="v${TAG}"

command -v gh >/dev/null 2>&1 || { echo "ERROR: gh CLI required" >&2; exit 2; }

WORK="$(mktemp -d)"
cleanup() {
  if [[ -n "${GNUPGHOME:-}" && -d "${GNUPGHOME}" && "${GNUPGHOME}" == "${WORK}"/* ]]; then
    gpgconf --homedir "${GNUPGHOME}" --kill all >/dev/null 2>&1 || true
  fi
  rm -rf "${WORK}"
}
trap cleanup EXIT

# Every per-platform file the release carries. Missing ones are an error: the
# combined file must describe the whole release or it is misleading.
PLATFORM_SUMS=(
  SHA256SUMS.linux-x86_64
  SHA256SUMS.linux-aarch64
  SHA256SUMS.macos-arm64
  SHA256SUMS.macos-x64
  SHA256SUMS.windows-x64
  SHA256SUMS.android
)

mkdir -p "${WORK}/in"
for name in "${PLATFORM_SUMS[@]}"; do
  gh release download "${TAG}" --repo "${REPO}" --pattern "${name}" --dir "${WORK}/in" --clobber
  if [[ ! -s "${WORK}/in/${name}" ]]; then
    echo "ERROR: ${name} missing or empty on release ${TAG}" >&2
    exit 1
  fi
done

COMBINED="${WORK}/SHA256SUMS"
# Normalise to "<hex>  <name>" lines, one per asset, sorted by file name and
# deduplicated; CRLF from the Windows job is dropped.
cat "${WORK}/in"/SHA256SUMS.* \
  | tr -d '\r' \
  | awk 'NF >= 2 && $1 ~ /^[0-9a-fA-F]{64}$/ { sub(/^\*/, "", $2); print tolower($1) "  " $2 }' \
  | sort -k2,2 -u > "${COMBINED}"

LINES="$(wc -l < "${COMBINED}" | tr -d ' ')"
if [[ "${LINES}" -lt 6 ]]; then
  echo "ERROR: combined SHA256SUMS has only ${LINES} line(s); refusing to publish" >&2
  cat "${COMBINED}" >&2
  exit 1
fi
echo "Combined SHA256SUMS (${LINES} assets):"
cat "${COMBINED}"

UPLOAD=("${COMBINED}")

if [[ -n "${RELEASE_GPG_PRIVATE_KEY:-}" ]]; then
  command -v gpg >/dev/null 2>&1 || { echo "ERROR: gpg required for signing" >&2; exit 1; }
  export GNUPGHOME="${WORK}/gnupg"
  mkdir -p "${GNUPGHOME}"
  chmod 700 "${GNUPGHOME}"
  printf '%s\n' "${RELEASE_GPG_PRIVATE_KEY}" | gpg --batch --quiet --import
  FPR="$(gpg --with-colons --list-secret-keys 2>/dev/null | awk -F: '$1 == "fpr" {print $10; exit}')"
  if [[ -z "${FPR}" ]]; then
    echo "ERROR: RELEASE_GPG_PRIVATE_KEY did not import a secret key" >&2
    exit 1
  fi
  GPG_ARGS=(--batch --yes --armor --detach-sign --local-user "${FPR}" --output "${COMBINED}.asc")
  if [[ -n "${RELEASE_GPG_PASSPHRASE:-}" ]]; then
    printf '%s\n' "${RELEASE_GPG_PASSPHRASE}" \
      | gpg "${GPG_ARGS[@]}" --pinentry-mode loopback --passphrase-fd 0 "${COMBINED}"
  else
    gpg "${GPG_ARGS[@]}" "${COMBINED}"
  fi
  gpg --verify "${COMBINED}.asc" "${COMBINED}"
  echo "Signed SHA256SUMS with ${FPR}"
  UPLOAD+=("${COMBINED}.asc")
else
  echo "WARNING: RELEASE_GPG_PRIVATE_KEY not set; uploading unsigned SHA256SUMS only" >&2
fi

gh release upload "${TAG}" "${UPLOAD[@]}" --repo "${REPO}" --clobber
echo "Uploaded: ${UPLOAD[*]##*/}"
