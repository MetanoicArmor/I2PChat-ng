#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FETCH_SCRIPT="${ROOT}/scripts/fetch_bundled_i2pd.sh"
SOURCE_DIR="${I2PCHAT_BUNDLED_I2PD_SOURCE_DIR:-}"
SIBLING_DIR="$(cd "${ROOT}/.." && pwd)/i2pchat-bundled-i2pd"
# Default public payloads repo. Empty I2PCHAT_BUNDLED_I2PD_GIT_URL disables git fetch; unset uses default.
DEFAULT_BUNDLED_I2PD_GIT_URL="https://github.com/MetanoicArmor/i2pchat-bundled-i2pd.git"
GIT_URL=""
CACHE_DIR="${I2PCHAT_BUNDLED_I2PD_CACHE_DIR:-${ROOT}/.cache/bundled-i2pd-source}"

usage() {
  cat <<'EOF'
Ensure local bundled i2pd binaries are staged under vendor/i2pd/.

Resolution order:
1. existing vendor/i2pd files
2. I2PCHAT_BUNDLED_I2PD_SOURCE_DIR
3. sibling repo ../i2pchat-bundled-i2pd
4. git clone (default https://github.com/MetanoicArmor/i2pchat-bundled-i2pd.git) into .cache/bundled-i2pd-source/
   Override URL: I2PCHAT_BUNDLED_I2PD_GIT_URL. Disable git: empty I2PCHAT_BUNDLED_I2PD_GIT_URL or I2PCHAT_SKIP_BUNDLED_I2PD_GIT=1

Usage:
  ./scripts/ensure_bundled_i2pd.sh
EOF
}

log() {
  printf '%s\n' "$*"
}

normalize_linux_i2pd_bundle_dir() {
  local dir="$1"
  [[ -d "$dir" ]] || return 0

  # Bundled i2pd must stay executable; do not add SONAME symlinks to arbitrary
  # newer Boost *.so — C++ ABI differs and causes runtime symbol lookup errors.
  if [[ -f "${dir}/i2pd" ]]; then
    chmod +x "${dir}/i2pd" 2>/dev/null || true
  fi
}

# True when the Mach-O still needs Homebrew/OpenSSL/Boost (not redistributable alone).
darwin_i2pd_needs_third_party_dylibs() {
  local bin="$1"
  [[ -f "$bin" ]] || return 1
  [[ "$(uname -s)" == "Darwin" ]] || return 1
  command -v otool >/dev/null 2>&1 || return 1
  otool -L "$bin" 2>/dev/null | awk 'NR>1 {print $1}' | grep -Eq 'homebrew|/opt/|@rpath/lib(ssl|crypto|boost)|@loader_path/lib(ssl|crypto|boost)'
}

refresh_darwin_i2pd_if_dynamic() {
  # Old vendor copies were Homebrew-linked (~7–8 MiB) without matching *.dylib.
  # Prefer a static payload from sibling/git (~11 MiB, system libs only).
  local sub bin
  case "$(uname -m)" in
    x86_64) sub=darwin-x64 ;;
    arm64) sub=darwin-arm64 ;;
    *) return 0 ;;
  esac
  bin="${ROOT}/vendor/i2pd/${sub}/i2pd"
  darwin_i2pd_needs_third_party_dylibs "$bin" || return 0

  log "==> Bundled Darwin i2pd links third-party dylibs — refreshing from payload source"
  rm -f "${ROOT}/vendor/i2pd/${sub}/"*.dylib
  if [[ -n "${SOURCE_DIR}" ]] && stage_from_dir "${SOURCE_DIR}"; then
    return 0
  fi
  if stage_from_dir "${SIBLING_DIR}"; then
    return 0
  fi
  if stage_from_git; then
    return 0
  fi
  log "==> WARN: could not refresh Darwin i2pd; run scripts/stage_i2pd_darwin_dylibs.sh or replace vendor/i2pd/${sub}/i2pd with a static build"
}

normalize_bundled() {
  normalize_linux_i2pd_bundle_dir "${ROOT}/vendor/i2pd/linux-x86_64"
  normalize_linux_i2pd_bundle_dir "${ROOT}/vendor/i2pd/linux-aarch64"
  refresh_darwin_i2pd_if_dynamic || true
}

has_any_bundled() {
  find "${ROOT}/vendor/i2pd" -maxdepth 3 -type f \
    \( -name 'i2pd' -o -name 'i2pd.exe' \) 2>/dev/null | grep -q .
}

stage_from_dir() {
  local dir="$1"
  [[ -d "$dir" ]] || return 1
  "${FETCH_SCRIPT}" --from "$dir" >/dev/null
  return 0
}

stage_from_git() {
  [[ -n "${GIT_URL}" ]] || return 1
  command -v git >/dev/null 2>&1 || return 1
  mkdir -p "$(dirname "${CACHE_DIR}")"
  if [[ -d "${CACHE_DIR}/.git" ]]; then
    git -C "${CACHE_DIR}" pull --ff-only >/dev/null 2>&1 || return 1
  else
    rm -rf "${CACHE_DIR}"
    git clone --depth=1 "${GIT_URL}" "${CACHE_DIR}" >/dev/null 2>&1 || return 1
  fi
  stage_from_dir "${CACHE_DIR}"
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  if [[ "${I2PCHAT_SKIP_BUNDLED_I2PD_GIT:-}" == "1" ]]; then
    GIT_URL=""
  elif [[ -n "${I2PCHAT_BUNDLED_I2PD_GIT_URL+x}" ]]; then
    GIT_URL="${I2PCHAT_BUNDLED_I2PD_GIT_URL}"
  else
    GIT_URL="${DEFAULT_BUNDLED_I2PD_GIT_URL}"
  fi

  if has_any_bundled; then
    normalize_bundled
    log "==> Bundled i2pd: FOUND in vendor/i2pd/"
    exit 0
  fi

  if [[ -n "${SOURCE_DIR}" ]] && stage_from_dir "${SOURCE_DIR}"; then
    normalize_bundled
    log "==> Bundled i2pd: STAGED from I2PCHAT_BUNDLED_I2PD_SOURCE_DIR=${SOURCE_DIR}"
    exit 0
  fi

  if stage_from_dir "${SIBLING_DIR}"; then
    normalize_bundled
    log "==> Bundled i2pd: STAGED from sibling repo ${SIBLING_DIR}"
    exit 0
  fi

  if stage_from_git; then
    normalize_bundled
    log "==> Bundled i2pd: STAGED from git source ${GIT_URL}"
    exit 0
  fi

  log "==> Bundled i2pd: NOT FOUND; building without embedded router"
}

main "$@"
