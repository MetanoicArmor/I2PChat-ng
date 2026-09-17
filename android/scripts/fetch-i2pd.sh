#!/usr/bin/env bash
# Refresh bundled Android i2pd JNI libs + certificates from PurpleI2P APKs.
#
# NOTE: Do not post-process with align-elf-16k.py. Official libi2pd.so is still
# linked for 4 KiB pages; faking p_align breaks Android 15+ loading. Keep
# android:pageSizeCompat="enabled" until PurpleI2P ships a real 16 KiB build
# (or we rebuild i2pd-android with NDK r28 + APP_SUPPORT_FLEXIBLE_PAGE_SIZES).
#
# Usage:
#   ./android/scripts/fetch-i2pd.sh [version]
# Default version: 2.61.0
set -euo pipefail

VERSION="${1:-2.61.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="${TMPDIR:-/tmp}/i2pd-android-bin-$$"
ARM_DEST="${ROOT}/android/app/src/main/jniLibs/arm64-v8a/libi2pd.so"
X64_DEST="${ROOT}/android/app/src/main/jniLibs/x86_64/libi2pd.so"
CERTS="${ROOT}/android/app/src/main/assets/i2pd"

cleanup() { rm -rf "${TMP}"; }
trap cleanup EXIT

mkdir -p "${TMP}"
command -v gh >/dev/null 2>&1 || { echo "ERROR: gh CLI required" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 1; }

echo "==> Downloading PurpleI2P i2pd-android ${VERSION}"
gh release download "${VERSION}" --repo PurpleI2P/i2pd-android \
  --pattern "i2pd-${VERSION}-x86_64-release.apk" \
  --pattern "i2pd-${VERSION}-arm64-v8a-release.apk" \
  --pattern "i2pd_${VERSION}_android_binary.zip" \
  --dir "${TMP}" --clobber

extract_lib() {
  local apk="$1" abi="$2" dest="$3"
  python3 - <<PY
import zipfile
from pathlib import Path
apk = Path(r"""${apk}""")
dest = Path(r"""${dest}""")
dest.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(apk) as z:
    name = f"lib/{abi}/libi2pd.so"
    try:
        data = z.read(name)
    except KeyError as exc:
        raise SystemExit(f"missing {name} in {apk}") from exc
    dest.write_bytes(data)
print("extracted", dest)
PY
}

extract_lib "${TMP}/i2pd-${VERSION}-arm64-v8a-release.apk" "arm64-v8a" "${ARM_DEST}"
extract_lib "${TMP}/i2pd-${VERSION}-x86_64-release.apk" "x86_64" "${X64_DEST}"

echo "==> Refreshing i2pd certificates"
rm -rf "${CERTS}"
mkdir -p "${CERTS}"
python3 - <<PY
import zipfile
from pathlib import Path
root = Path(r"""${ROOT}""")
zpath = Path(r"""${TMP}""") / f"i2pd_${VERSION}_android_binary.zip"
certs = root / "android/app/src/main/assets/i2pd"
with zipfile.ZipFile(zpath) as z:
    for info in z.infolist():
        if info.is_dir() or not info.filename.startswith("certificates/"):
            continue
        dest = certs / info.filename
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read(info.filename))
print("updated i2pd JNI", "${VERSION}")
PY
