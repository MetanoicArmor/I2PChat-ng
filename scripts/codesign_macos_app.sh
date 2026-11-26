#!/usr/bin/env bash
# Ad-hoc codesign a PyInstaller onedir wrapped as Foo.app so macOS TCC
# (Camera / Microphone) binds to CFBundleIdentifier and Info.plist.
#
# PyInstaller puts the whole _internal tree in Contents/Frameworks/. Apple
# treats every top-level entry there as nested code; dirs like PIL/.dylibs
# and *.dist-info are not valid bundles unless they get a minimal Info.plist.
# Without a bound Info.plist, Privacy → Camera never lists the app.
set -euo pipefail

APP="${1:?usage: codesign_macos_app.sh /path/to/App.app}"
BUNDLE_ID="${2:-}"

if [[ ! -d "$APP/Contents" ]]; then
  echo "ERROR: not an app bundle: $APP" >&2
  exit 1
fi

if [[ -z "$BUNDLE_ID" ]]; then
  BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Contents/Info.plist" 2>/dev/null || true)"
fi
if [[ -z "$BUNDLE_ID" ]]; then
  BUNDLE_ID="net.i2pchat.I2PChat"
fi

add_bndl_plist() {
  local dir="$1"
  local rel id_suffix
  rel="${dir#"$APP/Contents/Frameworks/"}"
  id_suffix="$(printf '%s' "$rel" | tr '/ ' '..' | tr -c 'A-Za-z0-9.-' '-' | cut -c1-80)"
  [[ -n "$id_suffix" ]] || id_suffix="$(basename "$dir")"
  mkdir -p "$dir"
  cat > "$dir/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key>
	<string>${BUNDLE_ID}.d.${id_suffix}</string>
	<key>CFBundleName</key>
	<string>$(basename "$dir")</string>
	<key>CFBundlePackageType</key>
	<string>BNDL</string>
	<key>CFBundleVersion</key>
	<string>1</string>
</dict>
</plist>
EOF
}

echo "==> codesign: preparing bundle stubs under Frameworks"

# Dirs whose names look like bundles to codesign (*.dist-info, .dylibs, …)
# plus every top-level non-.framework directory.
find "$APP/Contents/Frameworks" -type d \( -name '*.*' -o -name '.dylibs' \) 2>/dev/null \
  | while IFS= read -r d; do
      [[ "$d" == *'.framework'* ]] && continue
      [[ -f "$d/Info.plist" ]] || add_bndl_plist "$d"
    done

find "$APP/Contents/Frameworks" -mindepth 1 -maxdepth 1 -type d ! -name '*.framework' 2>/dev/null \
  | while IFS= read -r d; do
      [[ -f "$d/Info.plist" ]] || add_bndl_plist "$d"
    done

# Iteratively satisfy codesign --deep (new stub dirs may appear as subcomponents).
for _ in $(seq 1 80); do
  if out="$(codesign --force --deep --sign - --timestamp=none --identifier "$BUNDLE_ID" "$APP" 2>&1)"; then
    break
  fi
  sub="$(printf '%s\n' "$out" | sed -n 's/.*In subcomponent: //p' | tail -1)"
  if [[ -z "$sub" ]]; then
    printf '%s\n' "$out" >&2
    echo "ERROR: codesign --deep failed without a fixable subcomponent" >&2
    exit 1
  fi
  if [[ -d "$sub" ]]; then
    add_bndl_plist "$sub"
  elif [[ -f "$sub" ]]; then
    codesign --force --sign - --timestamp=none "$sub" 2>/dev/null || true
    parent="$(dirname "$sub")"
    base="$(basename "$parent")"
    if [[ ! -f "$parent/Info.plist" && ( "$base" == *.* || "$base" == .* ) ]]; then
      add_bndl_plist "$parent"
    fi
  else
    printf '%s\n' "$out" >&2
    echo "ERROR: missing subcomponent: $sub" >&2
    exit 1
  fi
done

echo "==> codesign: signing all Mach-O payloads"
while IFS= read -r f; do
  if file "$f" | grep -q 'Mach-O'; then
    codesign --force --sign - --timestamp=none "$f" 2>/dev/null || true
  fi
done < <(find "$APP/Contents" -type f \( -name '*.so' -o -name '*.dylib' -o -name '*.dylib.*' -o -perm +111 \))

echo "==> codesign: re-seal $APP ($BUNDLE_ID)"
codesign --force --deep --sign - --timestamp=none --identifier "$BUNDLE_ID" "$APP"

codesign --verify "$APP"
codesign -dv --verbose=4 "$APP" 2>&1 | grep -E 'Identifier=|Info\.plist|Sealed Resources|Signature=' || true
echo "==> codesign: OK"
