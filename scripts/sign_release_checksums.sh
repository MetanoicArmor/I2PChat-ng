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
#   RELEASE_GPG_PRIVATE_KEY   armored secret key (preferred for CI)
#   RELEASE_GPG_PASSPHRASE    optional passphrase for that key
#   APT_REPO_GPG_PRIVATE_KEY  fallback armored key (same release fingerprint)
#   APT_REPO_GPG_PASSPHRASE   optional passphrase for the apt key
#   I2PCHAT_GPG_KEY_ID        local key id/fingerprint when no armored key is set
#   I2PCHAT_REQUIRE_GPG=1     fail instead of uploading unsigned SHA256SUMS
#
# With an armored key the script imports into a throwaway GNUPGHOME (removed on
# exit). Without one it signs from the caller’s normal keyring (maintainer
# laptop). Signing is all-or-nothing when a key source is present or when
# I2PCHAT_REQUIRE_GPG=1.
set -euo pipefail

REPO="${I2PCHAT_GH_REPO:-MetanoicArmor/I2PChat-ng}"
# Published release-signing fingerprint (docs/BUILD.md, MANUAL_*, release notes).
EXPECTED_FPR="${I2PCHAT_RELEASE_GPG_FPR:-2BA0C56D8240077F9773248A2C05CFB3F6DFDF99}"
REQUIRE_GPG="${I2PCHAT_REQUIRE_GPG:-0}"

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

KEY_MATERIAL="${RELEASE_GPG_PRIVATE_KEY:-${APT_REPO_GPG_PRIVATE_KEY:-}}"
KEY_PASSPHRASE="${RELEASE_GPG_PASSPHRASE:-${APT_REPO_GPG_PASSPHRASE:-}}"
LOCAL_KEY_ID="${I2PCHAT_GPG_KEY_ID:-${EXPECTED_FPR}}"

sign_with_imported_key() {
  command -v gpg >/dev/null 2>&1 || { echo "ERROR: gpg required for signing" >&2; exit 1; }
  export GNUPGHOME="${WORK}/gnupg"
  mkdir -p "${GNUPGHOME}"
  chmod 700 "${GNUPGHOME}"
  printf '%s\n' "${KEY_MATERIAL}" | gpg --batch --quiet --import
  FPR="$(gpg --with-colons --list-secret-keys 2>/dev/null | awk -F: '$1 == "fpr" {print $10; exit}')"
  if [[ -z "${FPR}" ]]; then
    echo "ERROR: armored private key did not import a secret key" >&2
    exit 1
  fi
  if [[ "${FPR}" != "${EXPECTED_FPR}" ]]; then
    echo "ERROR: imported key fingerprint ${FPR} != expected release key ${EXPECTED_FPR}" >&2
    exit 1
  fi
  GPG_ARGS=(--batch --yes --armor --detach-sign --local-user "${FPR}" --output "${COMBINED}.asc")
  if [[ -n "${KEY_PASSPHRASE}" ]]; then
    printf '%s\n' "${KEY_PASSPHRASE}" \
      | gpg "${GPG_ARGS[@]}" --pinentry-mode loopback --passphrase-fd 0 "${COMBINED}"
  else
    gpg "${GPG_ARGS[@]}" "${COMBINED}"
  fi
  gpg --verify "${COMBINED}.asc" "${COMBINED}"
  echo "Signed SHA256SUMS with imported key ${FPR}"
}

sign_with_local_keyring() {
  command -v gpg >/dev/null 2>&1 || { echo "ERROR: gpg required for signing" >&2; exit 1; }
  RESOLVED="$(gpg --with-colons --list-secret-keys "${LOCAL_KEY_ID}" 2>/dev/null \
    | awk -F: '$1 == "fpr" {print $10; exit}')"
  if [[ -z "${RESOLVED}" ]]; then
    echo "ERROR: no local secret key for '${LOCAL_KEY_ID}'" >&2
    exit 1
  fi
  if [[ "${RESOLVED}" != "${EXPECTED_FPR}" ]]; then
    echo "ERROR: local key '${LOCAL_KEY_ID}' fingerprint ${RESOLVED} != expected ${EXPECTED_FPR}" >&2
    exit 1
  fi
  # Prefer pinentry when a TTY is available (maintainer laptop).
  GPG_ARGS=(--yes --armor --detach-sign --local-user "${LOCAL_KEY_ID}" --output "${COMBINED}.asc")
  if [[ (! -t 0 && ! -t 1) || "${I2PCHAT_GPG_BATCH:-}" == "1" ]]; then
    GPG_ARGS=(--batch "${GPG_ARGS[@]}")
  fi
  gpg "${GPG_ARGS[@]}" "${COMBINED}"
  gpg --verify "${COMBINED}.asc" "${COMBINED}"
  echo "Signed SHA256SUMS with local key ${RESOLVED}"
}

if [[ -n "${KEY_MATERIAL}" ]]; then
  sign_with_imported_key
  UPLOAD+=("${COMBINED}.asc")
elif gpg --list-secret-keys --with-colons "${LOCAL_KEY_ID}" >/dev/null 2>&1; then
  sign_with_local_keyring
  UPLOAD+=("${COMBINED}.asc")
else
  msg="no RELEASE_GPG_PRIVATE_KEY / APT_REPO_GPG_PRIVATE_KEY and no local secret key ${LOCAL_KEY_ID}"
  if [[ "${REQUIRE_GPG}" == "1" ]]; then
    echo "ERROR: ${msg}; refusing unsigned SHA256SUMS (I2PCHAT_REQUIRE_GPG=1)" >&2
    exit 1
  fi
  echo "WARNING: ${msg}; uploading unsigned SHA256SUMS only" >&2
fi

gh release upload "${TAG}" "${UPLOAD[@]}" --repo "${REPO}" --clobber
echo "Uploaded: ${UPLOAD[*]##*/}"
