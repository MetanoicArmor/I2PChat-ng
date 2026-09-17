# Packaging the C++ client

Root [`packaging/`](../../packaging/) is the downstream channel for **shipping
C++** binaries (Homebrew, winget, AUR, `.deb` / apt, RPM, Flatpak). Artifact
names match what users already expect on
[GitHub Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest).

## What is reused

- `scripts/ensure_bundled_i2pd.sh` and staged i2pd trees
- AppDir layout and pinned `appimagetool` SHA
- `SHA256SUMS` (+ optional `.asc`) next to release assets
- Downstream consumers: only version + checksums change when you cut a release

## Build → artifact map

| Script | Outputs |
|---|---|
| `./build-linux.sh` | `I2PChat-linux-<arch>-v*.zip` (AppImage), `*-tui-*` |
| `./build-macos.sh` | `I2PChat-macOS-<arch>-v*.zip`, `*-tui-*` |
| `./build-windows.ps1` | full + `*-winget-*` zips (winget omits bundled i2pd) |
| `./build-android.sh` | `I2PChat-android-v*.apk` |
| `release-linux-pkgs.yml` | `i2pchat_*.deb`, `i2pchat-tui_*.deb`, `i2pchat_*.rpm` |

CMake install targets: `i2pchat-gui` → `I2PChat`, `i2pchat-tui` → `I2PChat-tui`,
plus `i2pchat-blindbox-daemon` when enabled.

## macOS signing

```bash
codesign --force --options runtime --deep --sign "Developer ID Application: …" \
  I2PChat.app
xcrun notarytool submit I2PChat.dmg --wait --keychain-profile i2pchat
xcrun stapler staple I2PChat.dmg
```

Release CI also ad-hoc signs nested Qt helpers before the main binary when
using aqt-provided Qt.

## Debian / RPM from zip

Prefer [`packaging/debian/`](../../packaging/debian/) and
[`packaging/fedora/`](../../packaging/fedora/) scripts that wrap the published
Linux zip — same path CI uses. Building a source `.deb` from `cpp/` with
system Qt/libsodium is optional for distro packaging experiments.

## See also

- [`../README.md`](../README.md) — build from source
- [`../../packaging/README.md`](../../packaging/README.md) — channel status
