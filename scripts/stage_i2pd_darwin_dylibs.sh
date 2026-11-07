#!/usr/bin/env bash
# Copy Homebrew OpenSSL/Boost dylibs next to vendor/i2pd/darwin-*/i2pd and
# rewrite install names to @loader_path so the bundled router starts without
# a Homebrew prefix (PyInstaller and the .app copy this directory as-is).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) SUB=darwin-x64 ;;
  arm64) SUB=darwin-arm64 ;;
  *)
    echo "Unsupported machine: $ARCH" >&2
    exit 1
    ;;
esac

DIR="${ROOT}/vendor/i2pd/${SUB}"
I2PD="${DIR}/i2pd"

if [[ ! -f "$I2PD" ]]; then
  echo "No bundled i2pd at ${I2PD} — run ensure_bundled_i2pd.sh first." >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "stage_i2pd_darwin_dylibs.sh is macOS-only." >&2
  exit 1
fi

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

need_cmd otool
need_cmd install_name_tool
need_cmd codesign

is_system_lib() {
  case "$1" in
    /usr/lib/*|/System/*) return 0 ;;
    *) return 1 ;;
  esac
}

resolve_lib() {
  local path="$1"
  if [[ -f "$path" ]]; then
    printf '%s\n' "$path"
    return 0
  fi
  local base cand
  base="$(basename "$path")"
  for cand in \
    "/opt/homebrew/opt/openssl@3/lib/${base}" \
    "/opt/homebrew/opt/boost/lib/${base}" \
    "/usr/local/opt/openssl@3/lib/${base}" \
    "/usr/local/opt/boost/lib/${base}" \
    "/opt/homebrew/lib/${base}" \
    "/usr/local/lib/${base}" \
    "${DIR}/${base}"; do
    if [[ -f "$cand" ]]; then
      printf '%s\n' "$cand"
      return 0
    fi
  done
  return 1
}

# Print resolvable non-system deps of a Mach-O file (direct only).
# Emits absolute paths when possible so resolve_lib can find Homebrew copies.
direct_deps() {
  local bin="$1" dep base cand
  local bindir
  bindir="$(cd "$(dirname "$bin")" && pwd)"
  while IFS= read -r dep; do
    [[ -n "$dep" ]] || continue
    case "$dep" in
      :*) continue ;;
      /usr/lib/*|/System/*) continue ;;
      @loader_path/*|@rpath/*)
        base="$(basename "$dep")"
        # Prefer a sibling already staged, else ask Homebrew via basename.
        if [[ -f "${bindir}/${base}" ]]; then
          printf '%s\n' "${bindir}/${base}"
        else
          printf '%s\n' "$base"
        fi
        ;;
      @*) continue ;;
      *)
        if is_system_lib "$dep"; then
          continue
        fi
        printf '%s\n' "$dep"
        ;;
    esac
  done < <(otool -L "$bin" | awk 'NR>1 {print $1}')
}

already_staged() {
  local name="$1" entry
  for entry in "${STAGED[@]:-}"; do
    [[ "$entry" == "$name" ]] && return 0
  done
  return 1
}

echo "==> Staging Darwin dylibs next to ${I2PD}"

QUEUE=()
while IFS= read -r dep; do
  [[ -n "$dep" ]] || continue
  QUEUE+=("$dep")
done < <(direct_deps "$I2PD" | sort -u)

if [[ "${#QUEUE[@]}" -eq 0 ]]; then
  echo "==> i2pd already links only system libraries — nothing to stage."
  exit 0
fi

STAGED=()
while [[ "${#QUEUE[@]}" -gt 0 ]]; do
  dep="${QUEUE[0]}"
  QUEUE=("${QUEUE[@]:1}")
  src="$(resolve_lib "$dep" || true)"
  if [[ -z "${src}" ]]; then
    echo "ERROR: cannot locate dependency: $dep" >&2
    exit 1
  fi
  want_base="$(basename "$dep")"
  case "$want_base" in
    @*) want_base="$(basename "$src")" ;;
  esac
  dest="${DIR}/${want_base}"
  if ! already_staged "$want_base"; then
    src_abs="$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
    dest_abs="$(cd "$(dirname "$dest")" 2>/dev/null && pwd)/$(basename "$dest")"
    if [[ "$src_abs" != "$dest_abs" ]]; then
      cp -f "$src" "$dest"
    fi
    chmod u+w "$dest" 2>/dev/null || true
    STAGED+=("$want_base")
    echo "  + ${want_base}  (from ${src})"
  fi

  # Always walk transitive deps (re-runs must still discover boost_container etc.).
  while IFS= read -r td; do
    [[ -n "$td" ]] || continue
    tb="$(basename "$td")"
    if already_staged "$tb"; then
      continue
    fi
    # Avoid infinite growth when resolve points back at the same leaf.
    case " ${QUEUE[*]} " in
      *" ${td} "*) continue ;;
    esac
    QUEUE+=("$td")
  done < <(direct_deps "$dest")
done

# Rewrite install names so everything resolves via @loader_path next to i2pd.
shopt -s nullglob
for lib in "${DIR}"/*.dylib; do
  base="$(basename "$lib")"
  install_name_tool -id "@loader_path/${base}" "$lib" 2>/dev/null || true
  while IFS= read -r dep; do
    [[ -n "$dep" ]] || continue
    db="$(basename "$dep")"
    if [[ -f "${DIR}/${db}" ]]; then
      install_name_tool -change "$dep" "@loader_path/${db}" "$lib" 2>/dev/null || true
    fi
  done < <(otool -L "$lib" | awk 'NR>1 {print $1}')
done
shopt -u nullglob

while IFS= read -r dep; do
  [[ -n "$dep" ]] || continue
  db="$(basename "$dep")"
  if [[ -f "${DIR}/${db}" ]]; then
    install_name_tool -change "$dep" "@loader_path/${db}" "$I2PD" 2>/dev/null || true
  fi
done < <(otool -L "$I2PD" | awk 'NR>1 {print $1}')

# Prefer loading from the binary's own directory even if an older rpath remains.
install_name_tool -add_rpath "@loader_path" "$I2PD" 2>/dev/null || true

# Ad-hoc sign after rewriting load commands (required on Apple Silicon).
codesign --force -s - "$I2PD" >/dev/null 2>&1 || true
shopt -s nullglob
for lib in "${DIR}"/*.dylib; do
  codesign --force -s - "$lib" >/dev/null 2>&1 || true
done
shopt -u nullglob

echo "==> Verifying loader paths"
otool -L "$I2PD" | sed 's/^/  /'
echo "==> Done (${#STAGED[@]} dylibs staged in ${DIR})"
