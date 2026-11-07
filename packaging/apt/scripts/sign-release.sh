#!/usr/bin/env bash
# Sign dists/stable/Release → InRelease + Release.gpg; export public KEY.gpg to site root.
# Requires: gpg with secret key imported; optional APT_REPO_GPG_PASSPHRASE in env.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SITE="${SITE_DIR:-$ROOT/site}"
REL="$SITE/dists/stable/Release"

if [[ ! -f "$REL" ]]; then
  echo "ERROR: missing $REL — run build-apt-site.sh first" >&2
  exit 1
fi

# Sign from an isolated keyring: the caller imports the release key into it
# (or into the default one, which is still honoured when GNUPGHOME is unset),
# and nothing else on the machine's keyring can be picked as "the first
# secret key". A fresh home also leaves no agent state behind on CI runners.
if [[ -z "${GNUPGHOME:-}" && -n "${APT_REPO_GPG_PRIVATE_KEY:-}" ]]; then
  export GNUPGHOME
  GNUPGHOME="$(mktemp -d)"
  chmod 700 "$GNUPGHOME"
  trap 'rm -rf "$GNUPGHOME"' EXIT
  printf '%s\n' "$APT_REPO_GPG_PRIVATE_KEY" | gpg --batch --quiet --import
fi

GPG_OPTS=(--batch --yes)
if [[ -n "${APT_REPO_GPG_PASSPHRASE:-}" ]]; then
  GPG_OPTS+=(--pinentry-mode loopback --passphrase-fd 0)
fi

FPR="$(
  gpg --with-colons --list-secret-keys --keyid-format=long 2>/dev/null \
    | awk -F: '$1 == "fpr" {print $10; exit}'
)"
if [[ -z "$FPR" ]]; then
  echo "ERROR: no GPG secret key in keyring" >&2
  exit 1
fi

cd "$SITE/dists/stable"
if [[ -n "${APT_REPO_GPG_PASSPHRASE:-}" ]]; then
  printf '%s\n' "$APT_REPO_GPG_PASSPHRASE" \
    | gpg "${GPG_OPTS[@]}" --local-user "$FPR" --clearsign --output InRelease Release
  printf '%s\n' "$APT_REPO_GPG_PASSPHRASE" \
    | gpg "${GPG_OPTS[@]}" --local-user "$FPR" -abs -o Release.gpg Release
else
  gpg "${GPG_OPTS[@]}" --local-user "$FPR" --clearsign --output InRelease Release
  gpg "${GPG_OPTS[@]}" --local-user "$FPR" -abs -o Release.gpg Release
fi

gpg --armor --export "$FPR" > "$SITE/KEY.gpg"
touch "$SITE/.nojekyll"

INDEX_SRC="$ROOT/index.html"
if [[ -f "$INDEX_SRC" ]]; then
  cp -f "$INDEX_SRC" "$SITE/index.html"
  echo "Copied landing page: $SITE/index.html"
else
  echo "WARN: missing $INDEX_SRC — site root will have no index.html" >&2
fi

echo "Signed Release; wrote InRelease, Release.gpg, KEY.gpg"
