# Installing I2PChat

**Supported binaries** (C++ Qt 6 GUI, FTXUI TUI, Android APK, `.deb` / `.rpm`) are on **[GitHub Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest)**. File names include the version (for example `v1.5.0`); use **Latest** on that page for the current build.

<img src="icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" />

## Windows (x64)

1. Download **`I2PChat-windows-x64-v<version>.zip`**.
2. Extract the archive.
3. Run **`I2PChat.exe`** (GUI) or **`I2PChat-tui.exe`** from a terminal (cmd / PowerShell) for the FTXUI TUI. Optional profile name as the first argument.

No Python runtime is required (native C++ build + Qt / vcpkg DLLs).

**winget** (cmd / PowerShell; elevation may be required by policy):

```powershell
winget install MetanoicArmor.I2PChat       # GUI — *-winget-* zip (no embedded i2pd)
winget install MetanoicArmor.I2PChat.TUI  # TUI only
```

If **`winget show`** does not list the latest version yet, the catalog may still be updating after a [winget-pkgs](https://github.com/microsoft/winget-pkgs) merge — run **`winget source update`** and retry, or pin **`--version x.y.z`** when needed.

**TUI-only zip:** **`I2PChat-windows-tui-x64-v<version>.zip`** contains **`I2PChat-tui.exe`** and runtime libs (no GUI exe). Use this with **`MetanoicArmor.I2PChat.TUI`** on winget.

<img src="icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" />

## macOS (Apple Silicon / arm64)

1. Download **`I2PChat-macOS-arm64-v<version>.zip`**.
2. Unzip and open **`I2PChat-macOS-arm64-bundle/I2PChat.app`** (see zip layout).
3. **TUI (optional):** `I2PChat.app/Contents/MacOS/I2PChat-tui` with an optional profile argument.

**TUI-only zip:** **`I2PChat-macOS-arm64-tui-v<version>.zip`** — run **`./i2pchat-tui`** from the extracted folder. Homebrew: **`brew install --cask metanoicarmor/i2pchat/i2pchat-tui`** (GUI: **`…/i2pchat`**).

Intel: same pattern with **`macOS-x64`** assets.

<img src="icons/icons8-linux-48.png" alt="Linux" width="28" height="28" align="middle" /> <img src="icons/icons8-arch-linux-48.png" alt="Arch Linux" width="28" height="28" align="middle" /> <img src="icons/icons8-debian-48.png" alt="Debian" width="28" height="28" align="middle" /> <img src="icons/icons8-ubuntu-48.png" alt="Ubuntu" width="28" height="28" align="middle" />

## Linux (x86_64)

1. Download **`I2PChat-linux-x86_64-v<version>.zip`** (contains one **AppImage**).
2. `chmod +x` the `.AppImage` file if needed, then run it for the GUI.
3. **TUI (inside AppImage):** mount or extract the image and run **`usr/bin/I2PChat-tui`**, or use the **I2P Chat (terminal)** desktop entry if present.

**TUI-only zip:** **`I2PChat-linux-x86_64-tui-v<version>.zip`** — unpack and run **`./i2pchat-tui`**.

**glibc / `GLIBC_X.XX not found`:** native binaries use the **C library from the build host**. Official Linux zips / `.deb` are built on **Ubuntu 22.04** ([Build Linux release artifacts](../.github/workflows/build-linux-release-artifacts.yml)). If your distro’s glibc is older than that baseline, build from source (see below) or use Flatpak when available.

<img src="icons/icons8-arch-linux-48.png" alt="Arch Linux" width="28" height="28" align="middle" /> **Arch Linux (AUR):** with an AUR helper such as **yay** or **paru**:

```bash
yay -S i2pchat-bin      # GUI: official AppImage → /opt/i2pchat, command i2pchat
yay -S i2pchat-tui-bin  # TUI-only: slim Linux zip → /opt/i2pchat-tui, command i2pchat-tui
```

Package pages: [i2pchat-bin](https://aur.archlinux.org/packages/i2pchat-bin), [i2pchat-tui-bin](https://aur.archlinux.org/packages/i2pchat-tui-bin). Maintainer sources: [`packaging/aur/`](../packaging/aur/).

<img src="icons/icons8-debian-48.png" alt="Debian" width="28" height="28" align="middle" /> <img src="icons/icons8-ubuntu-48.png" alt="Ubuntu" width="28" height="28" align="middle" /> **`.deb` (Debian/Ubuntu):** releases include **`i2pchat_<version>_{amd64|arm64}.deb`** and **`i2pchat-tui_<version>_{amd64|arm64}.deb`**. Install with `sudo apt install ./i2pchat_*_<arch>.deb`. See [`packaging/debian/README.md`](../packaging/debian/README.md).

<img src="icons/icons8-debian-48.png" alt="Debian" width="28" height="28" align="middle" /> <img src="icons/icons8-ubuntu-48.png" alt="Ubuntu" width="28" height="28" align="middle" /> **apt mirror (GitHub Pages):** [metanoicarmor.github.io/I2PChat-ng](https://metanoicarmor.github.io/I2PChat-ng/) (signed; **amd64** + **arm64**). Details: [`packaging/apt/README.md`](../packaging/apt/README.md).

```bash
sudo mkdir -p /etc/apt/keyrings
curl -fsSL "https://metanoicarmor.github.io/I2PChat-ng/KEY.gpg" | sudo gpg --dearmor -o /etc/apt/keyrings/i2pchat.gpg
sudo tee /etc/apt/sources.list.d/i2pchat.sources >/dev/null <<'EOF'
Types: deb
URIs: https://metanoicarmor.github.io/I2PChat-ng
Suites: stable
Components: main
Signed-By: /etc/apt/keyrings/i2pchat.gpg
Architectures: amd64 arm64
EOF
sudo apt update
sudo apt install i2pchat        # GUI
# or: sudo apt install i2pchat-tui   # TUI only
```

Legacy `sources.list` line:  
`echo 'deb [signed-by=/etc/apt/keyrings/i2pchat.gpg] https://metanoicarmor.github.io/I2PChat-ng/ stable main' | sudo tee /etc/apt/sources.list.d/i2pchat.list`

<img src="icons/icons8-linux-48.png" alt="Linux" width="28" height="28" align="middle" /> **`.rpm` (Fedora):** on the same [Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest) page as **`i2pchat_<version>_x86_64.rpm`**, or build via [`packaging/fedora/README.md`](../packaging/fedora/README.md).

## Android

Download **`I2PChat-android-v<version>.apk`** from [Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest), or build with [`./build-android.sh`](../build-android.sh). Details: [`android/README.md`](../android/README.md).

## Router (I2P)

You need a working I2P router with **SAM** enabled (typical system **i2pd**). Fresh installs default to **system** SAM; switch to **bundled** in the app if your build ships embedded `i2pd`. Details: [**MANUAL_EN.md**](MANUAL_EN.md) / [**MANUAL_RU.md**](MANUAL_RU.md) and the **Router backend** note in the [README](../README.md) Quick Start section.

## Third-party package managers

Unofficial packages may exist (Homebrew, winget, AUR, Fedora COPR, etc.). The **authoritative** artifacts remain the GitHub release files above. Maintainer-facing recipes live under **[`packaging/`](../packaging/README.md)** only.

## Build from source

See **Building and running from source (C++)** and **Cross‑platform release builds** in the repository root [`README.md`](../README.md) (`cpp/`, CMake, `build-linux.sh` / `build-macos.sh` / `build-windows.ps1` / `build-android.sh`).

## More documentation

- [MANUAL_EN.md](MANUAL_EN.md) / [MANUAL_RU.md](MANUAL_RU.md) — full user manuals  
- [PROTOCOL.md](PROTOCOL.md) — protocol reference for developers  
