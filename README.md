<p align="center">
  <img src="image.png" alt="I2PChat Logo" width="280" />
</p>

<h1 align="center">I2PChat</h1>

<p align="center">
  <a href="https://github.com/MetanoicArmor/I2PChat-ng/releases/latest"><img src="https://img.shields.io/github/v/release/MetanoicArmor/I2PChat-ng?label=release" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/MetanoicArmor/I2PChat-ng" alt="License"></a>
  <a href="cpp/CMakeLists.txt"><img src="https://img.shields.io/badge/C%2B%2B-20-00599C.svg" alt="C++20"></a>
  <a href="android/README.md"><img src="https://img.shields.io/badge/Android-APK-3DDC84.svg" alt="Android"></a>
  <a href="https://i2pd.website"><img src="https://img.shields.io/badge/I2P-SAM%20API-purple.svg" alt="I2P"></a>
</p>

<p align="center">
  <b>Experimental peer‑to‑peer chat client for the <a href="https://i2pd.website">I2P</a> anonymity network.</b><br>
  Cross‑platform <b>Qt 6 GUI</b>, <b>FTXUI TUI</b>, and <b>Android</b> (Kotlin + Compose) on one shared <b>C++20</b> core (<code>libi2pchat_core</code>).<br>
  Prebuilt releases usually ship a <b>bundled <code>i2pd</code></b>; you can switch to a system router in the app (see manuals).
</p>

---

### Language / Язык

[![English manual](https://img.shields.io/badge/📖%20Manual-EN-blue.svg)](docs/MANUAL_EN.md)
[![Русский мануал](https://img.shields.io/badge/📖%20Мануал-RU-red.svg)](docs/MANUAL_RU.md)
[![Roadmap EN](https://img.shields.io/badge/🗺️%20Roadmap-EN-teal.svg)](docs/ROADMAP.md)
[![Roadmap RU](https://img.shields.io/badge/🗺️%20Roadmap-RU-red.svg)](docs/ROADMAP_RU.md)
[![Issue Backlog EN](https://img.shields.io/badge/📝%20Issue%20Backlog-EN-blueviolet.svg)](docs/ISSUE_BACKLOG.md)
[![Issue Backlog RU](https://img.shields.io/badge/📝%20Issue%20Backlog-RU-orange.svg)](docs/ISSUE_BACKLOG_RU.md)

---

### 📑 Table of contents

- [✨ Features](#-features)
- [🧠 Core architecture](#-core-architecture)
- [🔌 Protocol overview](#-protocol-overview)
- [📬 BlindBox in short](#-blindbox-in-short)
- [📸 Screenshots](#-screenshots)
- [🛠 Building and running from source (C++)](#-building-and-running-from-source-c)
- [Cross‑platform release builds](#-cross-platform-release-builds)
- [Android](#android)
- [📄 License](#-license)
- [☕ Developer Support](#-developer-support)
- [🚀 Quick Start](#-quick-start) — downloads, package managers, **INSTALL.md**

### ✨ Features

- **End‑to‑end communication over I2P SAM** (in-tree SAM client in `libi2pchat_core`)
- **E2E encryption** — handshake, key signing and verification
- **TOFU** — peer key pinning on first contact
- **Multi-peer profiles** — switch between **Saved peers** freely; incoming connections are accepted only from addresses present in the contact book (empty book ⇒ no inbound whitelist matches)
- **Qt 6 GUI** with light and dark themes (desktop)
- **File transfer** and **image sending** (Send picture: PNG, JPEG, WebP) between peers
- **Profiles (.dat)** — multiple profiles, load and import; each profile’s data lives under **`profiles/<name>/`** in the app data directory (if older **flat** `*.dat` files still sit in the data root, they are **migrated on startup** into that layout — see **§ profile paths** in [MANUAL_EN](docs/MANUAL_EN.md) / [MANUAL_RU](docs/MANUAL_RU.md))
- **System notifications** — tray toasts for new messages
- **Sound notifications** for incoming messages
- **BlindBox (default-on for named profiles)** — offline message delivery
- **Optional encrypted chat history** — per-peer local history (toggle **Chat history: ON/OFF** in the **⋯** menu); encrypted at rest with keys derived from your profile identity (see **§4.11** in [MANUAL_EN](docs/MANUAL_EN.md) / [MANUAL_RU](docs/MANUAL_RU.md))
- **Contact book (Saved peers)** — left sidebar list backed by **`profiles/<name>/<name>.contacts.json`**: quick switch between saved `.b32.i2p` peers, optional display name/note, unread hints, resize/collapse, and a context menu (edit, trust details, remove). See **§3.1** in [MANUAL_EN](docs/MANUAL_EN.md) / [MANUAL_RU](docs/MANUAL_RU.md).
- **Text groups** — multi-member conversations over the same vNext stream as 1:1 chat; offline delivery fans out per member via **pairwise** BlindBox (see the manuals for prerequisites and **§** on group BlindBox behavior)
- **Terminal client (TUI)** — FTXUI console UI; shipped as **`*-tui-*`** release zips and **`i2pchat-tui`** packages (Homebrew, apt, AUR, winget)
- **Android** — Kotlin + Jetpack Compose over the same C++ core; release APK on [GitHub Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest) or build with [`./build-android.sh`](build-android.sh) ([details](android/README.md))
- Cross‑platform build scripts (Linux, macOS, Windows, Android)

#### 📖 Manuals

- **English manual**: [**docs/MANUAL_EN.md**](docs/MANUAL_EN.md)
- **Русский мануал**: [**docs/MANUAL_RU.md**](docs/MANUAL_RU.md)

### 🧠 Core architecture

I2PChat is a **C++20** project: shared **`libi2pchat_core`**, desktop **Qt 6 GUI**, **FTXUI TUI**, and **Android** (JNI + Compose). Historical Python sources may still exist for interop tests; shipping builds and CI are C++.

| Layer | Role |
|-------|------|
| `cpp/apps/gui` | Qt 6 desktop UI |
| `cpp/apps/tui` | FTXUI terminal UI |
| `cpp/apps/cli` | Headless / tooling entry |
| `libi2pchat_core` | SAM, crypto, vNext framing, BlindBox, sessions, groups |
| `android/` | Kotlin UI + JNI over the same core |

```mermaid
flowchart TB
    subgraph UI["User interfaces"]
        qt["Qt 6 GUI<br/>cpp/apps/gui"]
        tui["FTXUI TUI<br/>cpp/apps/tui"]
        and["Android Compose<br/>android/"]
    end

    subgraph Core["libi2pchat_core"]
        session["Session / peer transport"]
        protocol["vNext codec + delivery"]
        crypto["X25519 / Ed25519 / SecretBox"]
        blindbox["BlindBox client"]
        sam["SAM client"]
    end

    subgraph External["External"]
        router["I2P router (i2pd / Java I2P)"]
        peers["Remote peers"]
        boxes["BlindBox replicas"]
    end

    qt --> Core
    tui --> Core
    and --> Core
    session --> protocol
    session --> crypto
    session --> blindbox
    session --> sam
    sam <--> router
    router <--> peers
    blindbox <--> boxes
```

Runtime in practice:

1. **Startup**: GUI / TUI / Android create a core session, load or create the profile identity, open the long-lived SAM session, warm tunnels, and start accept / tunnel watch loops.
2. **Transport**: per-peer state (connecting / handshaking / secure / stale / failed), outbound policy, stream registry, and reconnect live in the session layer. Parallel live traffic is keyed by peer id.
3. **Live chat**: one I2P stream per peer after handshake and TOFU; encrypted vNext frames thereafter. Multiple peers can be connected at once.
4. **Text groups**: group envelopes over the same vNext stream; offline text fans out per member via BlindBox.
5. **Offline (BlindBox)**: when no live secure session is available, text can be queued as padded encrypted blobs on BlindBox replicas and delivered when the peer returns.
6. **UI split**: the core stays UI-agnostic; Qt / FTXUI / Compose render chat and status.

### 🔌 Protocol overview

Traffic is a **byte stream** over **I2P SAM** (one TCP session to the router). Application data is split into **vNext binary frames**:

```
┌─────────── vNext frame ────────────────────────────────────────┐
│ MAGIC (4) │ VER (1) │ TYPE (1) │ FLAGS (1) │ MSG_ID (8) │ LEN (4) │ PAYLOAD (LEN bytes) │
└──────────────────────────────────────────────────────────────────┘
```

- **Handshake** uses **plain** frame bodies (UTF‑8 text: identities, `INIT` / replies, signatures).
- After the secure handshake, payloads are **encrypted** (`FLAGS` marks it): each body is **sequence (8 B) + ciphertext + MAC** (NaCl SecretBox + HMAC over metadata).
- **Message IDs** and **sequence numbers** tie frames to ordering and replay protection; see also [padding](#protocol-metadata-and-padding-profile) below.

For a developer-oriented specification with framing, handshake, ACK, transfer,
BlindBox, and code-map sections, see [**docs/PROTOCOL.md**](docs/PROTOCOL.md).

Runtime layout summary: [**docs/ARCHITECTURE.md**](docs/ARCHITECTURE.md). Release scripts, signing, checksums, NixOS, BlindBox daemon notes: [**docs/BUILD.md**](docs/BUILD.md).

### 📬 BlindBox

BlindBox is your “send now, deliver later” mode for text messages.

Why users like it:

- You can message people even when they are temporarily offline.
- Delivery happens automatically when they come back online.
- The chat stays clean and readable: only real messages, no technical noise.
- Works naturally with normal live chat — no extra routine in daily use.

Simple flow:

1. If the peer is online, the message is delivered live.
2. If the peer is offline, the app keeps it in the offline queue.
3. When the peer returns, the message appears automatically.

Practical notes:

- For named profiles BlindBox is enabled by default.
- For the transient profile `random_address` (CLI alias `default`) BlindBox is off.
- Disable explicitly with `I2PCHAT_BLINDBOX_ENABLED=0`.
- Deployments can set Blind Box endpoints via env (`I2PCHAT_BLINDBOX_REPLICAS`, `I2PCHAT_BLINDBOX_DEFAULT_REPLICAS`, or `I2PCHAT_BLINDBOX_DEFAULT_REPLICAS_FILE`). Built-in release defaults and further options → manuals / release notes above.

### 📸 Screenshots

<p align="center">
  <img src="screenshots/1.png" alt="I2PChat – main window" width="900" /><br>
  <img src="screenshots/4.png" alt="I2PChat – chat and file transfer (sending)" width="900" /><br>
  <img src="screenshots/10.png" alt="I2PChat – TUI (terminal UI)" width="900" />
</p>

The gallery above is a short subset. **`screenshots/2.png`** (⋯ menu), **`3.png`** (profile picker), **`5.png`** (emoji picker), **`6.png`** (BlindBox diagnostics), **`8.png`** (I2P router dialog), **`9.png`** (Blind Box setup examples — `install.sh` / **Copy curl** for a custom replica), and **`10.png`** (TUI) are documented inline in [**MANUAL_EN.md**](docs/MANUAL_EN.md) / [**MANUAL_RU.md**](docs/MANUAL_RU.md).

### 🛠 Building and running from source (C++)

The supported client is **C++20** under [`cpp/`](cpp/). It is wire-compatible with Python 1.4.x peers and reads the same profile directory. CMake presets: [`cpp/README.md`](cpp/README.md). Packaging / cutover: [`cpp/packaging/README.md`](cpp/packaging/README.md), [`cpp/docs/CUTOVER.md`](cpp/docs/CUTOVER.md).

Requirements (all platforms):

- **CMake ≥ 3.24** and a C++20 compiler (GCC 12+, Clang 15+, Apple Clang, MSVC 2022)
- **libsodium**, **Boost ≥ 1.86** (Asio; `asio::cancel_after`), **nlohmann/json**
- **FTXUI** (TUI), **Qt 6 Widgets** (GUI)
- **Catch2 3** only if you build tests
- a **system** [i2pd](https://i2pd.website) with **SAM** on port `7656`, or a **bundled** `i2pd` staged by the release scripts

<img src="docs/icons/icons8-debian-48.png" alt="Debian" width="28" height="28" align="middle" /> <img src="docs/icons/icons8-ubuntu-48.png" alt="Ubuntu" width="28" height="28" align="middle" /> **Linux (Debian/Ubuntu)**

```bash
sudo apt install cmake ninja-build g++ pkg-config \
  libsodium-dev libboost-dev nlohmann-json3-dev \
  qt6-base-dev libxcb-cursor0
# FTXUI: distro package if present, else vcpkg feature `tui`.
# X11: libxcb-cursor0 is required for the Qt xcb platform plugin.

cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DI2PCHAT_BUILD_TUI=ON -DI2PCHAT_BUILD_GUI=ON -DI2PCHAT_BUILD_TESTS=ON
cmake --build cpp/build -j
ctest --test-dir cpp/build --output-on-failure

./cpp/build/apps/tui/i2pchat-tui --help
./cpp/build/apps/gui/i2pchat-gui --profile default
```

<img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS (Homebrew)**

```bash
brew install cmake ninja libsodium boost nlohmann-json catch2 ftxui qt

cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DI2PCHAT_BUILD_TUI=ON -DI2PCHAT_BUILD_GUI=ON
cmake --build cpp/build -j

./cpp/build/apps/tui/i2pchat-tui -p default
./cpp/build/apps/gui/i2pchat-gui -p default
```

Homebrew prefixes are picked up automatically on Apple Silicon. Profiles live in `~/Library/Application Support/I2PChat/` (same as the historical Python client).

<img src="docs/icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" /> **Windows (MSVC + vcpkg)**

```powershell
# Visual Studio 2022 with “Desktop development with C++”, CMake, and Git.
$env:VCPKG_ROOT = "C:\src\vcpkg"   # after bootstrap-vcpkg.bat
$env:VCPKG_MANIFEST_FEATURES = "tests;tui;gui"

cmake -S cpp -B cpp/build `
  -DCMAKE_TOOLCHAIN_FILE="$env:VCPKG_ROOT\scripts\buildsystems\vcpkg.cmake" `
  -DI2PCHAT_BUILD_TUI=ON -DI2PCHAT_BUILD_GUI=ON -DI2PCHAT_BUILD_TESTS=ON
cmake --build cpp/build --config RelWithDebInfo --parallel
ctest --test-dir cpp/build -C RelWithDebInfo --output-on-failure

.\cpp\build\apps\tui\RelWithDebInfo\i2pchat-tui.exe --help
.\cpp\build\apps\gui\RelWithDebInfo\i2pchat-gui.exe --profile default
```

Without vcpkg, install libsodium/Boost/nlohmann-json/Qt/FTXUI yourself and pass **`CMAKE_PREFIX_PATH`**.

**CLI flags** (TUI and GUI share the parser): `-p` / `--profile`, `--app-root`, `--sam-host`, `--sam-port`, `--bundled-router`, `--connect`, `--replica`, `--replica-direct`, `--poll-seconds`, `--help`, `--version`.

**SAM:** the C++ stack talks SAM v3 itself. You do not need Python or `i2plib`.

**BlindBox daemon:** `i2pchat-blindbox-daemon`. systemd / fail2ban: [`cpp/apps/blindbox-daemon/packaging/`](cpp/apps/blindbox-daemon/packaging/). Public replicas behind I2P may keep replica auth empty; raw TCP should still use a token. See **§4.9** in [MANUAL_EN](docs/MANUAL_EN.md) / [MANUAL_RU](docs/MANUAL_RU.md).

Legacy Python sources (if present) are only for interop / golden-vector tests; they are **not** required to build or run shipping clients.

### 🔧  Cross-platform release builds

The project is **cross-platform**. Release scripts compile the **C++** GUI (`i2pchat-gui` → `I2PChat`), TUI (`i2pchat-tui` → `I2PChat-tui`) and BlindBox daemon, then pack the same artifact names as before so apt/AUR/winget/Homebrew consumers only need new checksums.

Shared helper: [`scripts/build_cpp_binaries.sh`](scripts/build_cpp_binaries.sh) (`cmake` + install). Optional bundled router is still staged by [`scripts/ensure_bundled_i2pd.sh`](scripts/ensure_bundled_i2pd.sh) — see [`docs/BUILD.md`](docs/BUILD.md).

#### <img src="docs/icons/icons8-linux-48.png" alt="linux" width="28" height="28" align="middle" /> Linux (GUI AppImage)

```bash
./build-linux.sh
```

This script:

- Requires **cmake** (≥ 3.24), a C++20 compiler, **Qt 6**, **FTXUI**, libsodium, Boost, nlohmann/json. **Ninja** is used when present.
- Builds into `cpp/build-release` and installs to `dist/cpp-install`.
- Stages `I2PChat` / `I2PChat-tui` plus linked libraries and Qt platform plugins, packs **`I2PChat.AppDir`**, then **`appimagetool`** (pinned SHA-256, same as before).
- Writes `dist/I2PChat-linux-<arch>-v<version>.AppImage` and **`I2PChat-linux-<arch>-v<version>.zip`** (by default one AppImage inside). **`arch`** is **`x86_64`** or **`aarch64`**. Set **`I2PCHAT_LINUX_GUI_ZIP_MODE=portable`** for a zip of the onedir (`I2PChat`, `I2PChat-tui`, `lib/`, `vendor/`).
- Also packs **`I2PChat-linux-<arch>-tui-v<version>.zip`** (launcher `i2pchat-tui` + `usr/bin/I2PChat-tui`).
- **Bundled `i2pd`:** [`scripts/ensure_bundled_i2pd.sh`](scripts/ensure_bundled_i2pd.sh) into `vendor/i2pd/` (default clone [i2pchat-bundled-i2pd](https://github.com/MetanoicArmor/i2pchat-bundled-i2pd)). Boost SONAME staging is unchanged (`stage_i2pd_linux_shlibs.sh`).

Need **`zip`** on PATH for the archives. **`wget`** for appimagetool.

#### <img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> macOS (GUI .app bundle)

```bash
./build-macos.sh
```

- Homebrew **cmake**, **qt**, **ftxui**, libsodium, boost, nlohmann-json.
- Builds `dist/I2PChat.app` (`Contents/MacOS/I2PChat` and `I2PChat-tui`).
- Runs **`macdeployqt`** when found (`brew --prefix qt`).
- Always ad-hoc **`codesign`** nested Qt libraries after **`macdeployqt`** (required on recent macOS). Optional **`I2PCHAT_CODESIGN_IDENTITY`** for Developer ID + hardened runtime.
- Zips **`I2PChat-macOS-<arch>-v<version>.zip`** and **`I2PChat-macos-<arch>-tui-v<version>.zip`**.

#### <img src="docs/icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" /> Windows

```powershell
powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
```

Safer one-off:

```powershell
powershell -NoProfile -Command "Set-ExecutionPolicy -Scope Process RemoteSigned; .\build-windows.ps1"
```

This:

1. Requires **cmake** and a C++20 toolchain (MSVC 2022). Prefer **vcpkg**: set **`VCPKG_ROOT`** (manifest features `tui;gui`). Otherwise set **`CMAKE_PREFIX_PATH`** / **`QTDIR`**.
2. Installs into `dist\cpp-install`, then copies **`dist\I2PChat\I2PChat.exe`** and **`I2PChat-tui.exe`**.
3. Runs **`windeployqt`** on the GUI when available.
4. Packs **`I2PChat-windows-x64-v<version>.zip`**, **`I2PChat-windows-tui-x64-v<version>.zip`**, and winget zips **without** embedded i2pd (`*-winget-*`) so Microsoft validation does not flag Riskware.I2PD.

The GUI zip is self-contained after `windeployqt` + vcpkg DLLs; machines do not need MSVC or Python.

### <img src="docs/icons/icons8-android-48.png" alt="Android" width="28" height="28" align="middle" /> Android

Native phone client: **Kotlin + Jetpack Compose** over the same C++ `libi2pchat_core` / `ChatService`. Application id `org.i2pchat.android`, min SDK 26. Details: [`android/README.md`](android/README.md).

```bash
./build-android.sh --debug            # installable debug APK (default)
./build-android.sh --release          # release APK (unsigned unless a keystore is configured)
./build-android.sh --debug --install  # adb install -r after build
```

- Needs **JDK 17+**, **Android SDK**, **NDK r28** (`28.2.13676358`), and **CMake** (Android Studio SDK Manager or CI `sdkmanager`).
- Bundled **`libi2pd.so`** (arm64 + x86_64) lives under `android/app/src/main/jniLibs/` (rebuild with [`./android/scripts/build-i2pd-16k.sh`](android/scripts/build-i2pd-16k.sh) if needed).
- Output: **`dist/I2PChat-android-v<version>-<debug|release>.apk`** (+ `.sha256`).
- Day-to-day: open **`android/`** in Android Studio; use an **arm64** device or **x86_64** emulator.

Default theme is **dark**. Tap the green **Online! My Address** notice to copy your destination to the clipboard.

### Verify release artifacts

Release build scripts generate:

- `SHA256SUMS` file for produced release archive(s) (Linux aarch64 builds may use a separate **`SHA256SUMS.linux-aarch64`** on GitHub Releases so amd64 sums are not overwritten);
- detached armored GPG signature `SHA256SUMS.asc` (best-effort by default).

These files are **not** tracked in git (they differ per OS/build); upload them **with the release assets** on GitHub.

Build-time controls:

- `I2PCHAT_SKIP_GPG_SIGN=1` — always skip detached signature creation;
- `I2PCHAT_REQUIRE_GPG=1` — fail build if GPG signing is unavailable or fails;
- `I2PCHAT_GPG_KEY_ID=<keyid>` — select a specific key for detached signature (avoids “no default secret key” when you have several keys or no `default-key` in `gpg.conf`);
- `I2PCHAT_GPG_BATCH=0|1` — override auto mode: by default the Linux/macOS scripts use **`gpg --batch`** only when **neither** stdin nor stdout is a TTY (typical CI). If either is a TTY (including `build.sh | tee log`), they omit `--batch` so **pinentry** can ask for your passphrase. Force batch with `I2PCHAT_GPG_BATCH=1` (needs **gpg-agent** with a cached passphrase if the key is protected).

**Official release builds** should set `I2PCHAT_REQUIRE_GPG=1` so unsigned archives are not produced silently; publish `SHA256SUMS` and `SHA256SUMS.asc` next to each asset.

Verification example:

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

### Protocol metadata and padding profile

The transport is encrypted after handshake, but some protocol metadata remains
observable on the wire:

- frame type (`TYPE`);
- frame length (`LEN`);
- pre-handshake peer identity preface exchange.

To reduce traffic-shape leakage, encrypted payloads use a padding profile:

- default: `balanced` (pads encrypted plaintext to 128-byte buckets);
- optional: `off` (disable padding).

You can override the profile with:

```bash
I2PCHAT_PADDING_PROFILE=off ./I2PChat   # or I2PChat-tui / Android env
```

Trade-off: stronger padding reduces length correlation but increases bandwidth.

#### ❄️ NixOS

```bash
# Run directly
nix run github:MetanoicArmor/I2PChat

# Install into your profile (adds `i2pchat`, `i2pchat-tui`, desktop entries and icon)
nix profile install github:MetanoicArmor/I2PChat

# Development shell
nix develop github:MetanoicArmor/I2PChat
```

The flake now wraps the app with the Qt runtime pieces that are easy to miss on NixOS sessions: Wayland/platform plugins, multimedia/image plugins, desktop metadata, and Linux notification/sound helpers (`notify-send`, `canberra-gtk-play`, `paplay`, `aplay`).

System keyring integration is optional. If no Secret Service backend is available, I2PChat falls back to file storage automatically; for native keyring support on NixOS, enable a provider such as `gnome-keyring` or KeepassXC Secret Service.

### 📄 License

I2PChat is licensed under the **GNU Affero General Public License v3.0** (or any later version — see section 14 of the license). The full text is in [`LICENSE`](LICENSE).

Bundled `i2pd` binaries, when injected for portable release builds, follow their upstream licenses. The application **SAM** stack is **`i2pchat.sam`** (no PyPI or vendored **i2plib**).

## ☕ Developer Support

If you like this project and it brings value, you can support its development by buying a virtual coffee:

<div align="center">

**☕ Buy developer a coffee:**

**₿ Bitcoin:**
<div align="center">
<img src="btc_donation_qr.png" width="200">

Минимальная сумма транзакции / Minimum transaction amount: **0.0001 BTC**
</div>

### 📋 Bitcoin Address:

```
bc1qfenneg8pt7g42f94uww3l3d7gtw6rl9dd3uslg
```

*Thank you for your support! It motivates to continue working on the project* 🙏

</div>

---

## 🚀 Quick Start

### 📥 Prebuilt Downloads

**[Latest release](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest)** — C++ desktop (Qt GUI + FTXUI TUI), Linux `.deb`/`.rpm`, and **Android APK**. Bundles match **`v` + [`VERSION`](VERSION)** (**v1.5.0** in the table; update filenames when you tag). No Python runtime needed.

Full zip layouts, **winget**, **`.deb`**, **Flatpak** notes → [**docs/INSTALL.md**](docs/INSTALL.md).

| Variant | Download | Launch |
|---------|----------|--------|
| <img src="docs/icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" /> **Windows — GUI** | [I2PChat-windows-x64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-windows-x64-v1.5.0.zip) | Unzip → run `I2PChat.exe` |
| <img src="docs/icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" /> **Windows — TUI only** | [I2PChat-windows-tui-x64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-windows-tui-x64-v1.5.0.zip) | `I2PChat-tui.exe` in the extracted tree |
| <img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS — GUI (arm64)** | [I2PChat-macOS-arm64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-macOS-arm64-v1.5.0.zip) | Unzip → open **`I2PChat-macOS-arm64-bundle/I2PChat.app`** (see **INSTALL.md**) |
| <img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS — TUI only (arm64)** | [I2PChat-macOS-arm64-tui-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-macOS-arm64-tui-v1.5.0.zip) | Run **`./i2pchat-tui`** from the extracted folder |
| <img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS — GUI (Intel x64)** | [I2PChat-macOS-x64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-macOS-x64-v1.5.0.zip) | Unzip → open **`I2PChat-macOS-x64-bundle/I2PChat.app`** (see **INSTALL.md**) |
| <img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS — TUI only (Intel x64)** | [I2PChat-macOS-x64-tui-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-macOS-x64-tui-v1.5.0.zip) | Run **`./i2pchat-tui`** from the extracted folder |
| <img src="docs/icons/icons8-linux-48.png" alt="Linux" width="28" height="28" align="middle" /> **Linux — GUI (x86_64)** | [I2PChat-linux-x86_64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-linux-x86_64-v1.5.0.zip) | Unzip → `chmod +x I2PChat.AppImage` → run |
| <img src="docs/icons/icons8-linux-48.png" alt="Linux" width="28" height="28" align="middle" /> **Linux — GUI (aarch64)** | [I2PChat-linux-aarch64-v1.5.0.zip](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-linux-aarch64-v1.5.0.zip) | Same — AppImage inside the zip |
| <img src="docs/icons/icons8-linux-48.png" alt="Linux" width="28" height="28" align="middle" /> **Linux — TUI** | [x86_64 TUI](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-linux-x86_64-tui-v1.5.0.zip) · [aarch64 TUI](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-linux-aarch64-tui-v1.5.0.zip) | After unzip: **`./i2pchat-tui`** |
| <img src="docs/icons/icons8-android-48.png" alt="Android" width="28" height="28" align="middle" /> **Android — APK** | [I2PChat-android-v1.5.0.apk](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-android-v1.5.0.apk) (or build with `./build-android.sh`) | Install via `adb install -r …` or the system package installer. |

> **Router backend:** On a **fresh install** (no `router_prefs.json` yet), I2PChat defaults to a **system** `i2pd` **SAM** endpoint (typically `127.0.0.1:7656`). Switch to the **bundled** sidecar when your build includes it via **More actions → I2P router…** (shortcut **Cmd/Ctrl+R**); the choice is persisted. The same dialog opens the router data/log paths and can restart the bundled router.

### 📦 Package managers

<img src="docs/icons/icons8-windows-48.png" alt="Windows" width="28" height="28" align="middle" /> **Windows (x64) — [winget](https://learn.microsoft.com/windows/package-manager/winget/)** (community manifests in [winget-pkgs](https://github.com/microsoft/winget-pkgs); ships the **`*-winget-*`** zip **without** embedded `i2pd`. For a bundled router, use the full **`*-windows-x64-v*.zip`** from [Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases).)

```powershell
winget install MetanoicArmor.I2PChat       # GUI
winget install MetanoicArmor.I2PChat.TUI   # TUI only
```

If the catalog lags behind a fresh release merge, **`winget show MetanoicArmor.I2PChat`** lists available versions; use **`--version x.y.z`** only when you need to pin one.

<img src="docs/icons/icons8-macos-48.png" alt="macOS" width="28" height="28" align="middle" /> **macOS (arm64) — [Homebrew](https://brew.sh)** ([tap](https://github.com/MetanoicArmor/homebrew-i2pchat))

```bash
brew install --cask metanoicarmor/i2pchat/i2pchat       # GUI — I2PChat.app
brew install --cask metanoicarmor/i2pchat/i2pchat-tui   # TUI only
```

Отдельный `brew tap` подключать не нужно — при установке через `metanoicarmor/i2pchat/...` tap подтянется автоматически.
(`brew tap MetanoicArmor/i2pchat` then `brew install --cask i2pchat` тоже работает, если нужен классический путь.)

<img src="docs/icons/icons8-arch-linux-48.png" alt="Arch Linux" width="28" height="28" align="middle" /> **Arch Linux — [AUR](https://aur.archlinux.org/)** (x86_64 and aarch64; example [yay](https://github.com/Jguer/yay))

```bash
yay -S i2pchat-bin       # GUI — AppImage from release
yay -S i2pchat-tui-bin   # TUI only
```

> **Not this repo:** [**`i2pchat-git`**](https://aur.archlinux.org/packages/i2pchat-git) builds [**vituperative/i2pchat**](https://github.com/vituperative/i2pchat) — another I2P chat client (**Qt5**). For **this** project use **`i2pchat-bin`** / **`i2pchat-tui-bin`**, or build the C++ clients from [`cpp/`](cpp/).

<img src="docs/icons/icons8-debian-48.png" alt="Debian" width="28" height="28" align="middle" /> <img src="docs/icons/icons8-ubuntu-48.png" alt="Ubuntu" width="28" height="28" align="middle" /> **Debian / Ubuntu — `.deb` from [Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases)** (works without any mirror):

```bash
# after downloading e.g. i2pchat_1.5.0_amd64.deb
sudo apt install ./i2pchat_*_amd64.deb
# optional TUI-only: sudo apt install ./i2pchat-tui_*_amd64.deb
```

**Optional apt mirror** (GitHub Pages, **amd64** + **arm64**): [metanoicarmor.github.io/I2PChat-ng](https://metanoicarmor.github.io/I2PChat-ng/) — see [`packaging/apt/README.md`](packaging/apt/README.md). (Repo rename: old `…/I2PChat/` Pages path **404**; use **`I2PChat-ng`**.) Or install `.deb` from Releases above.

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
sudo apt install i2pchat i2pchat-tui   # pick one or both
```

Legacy one-line:  
`echo 'deb [signed-by=/etc/apt/keyrings/i2pchat.gpg] https://metanoicarmor.github.io/I2PChat-ng stable main' | sudo tee /etc/apt/sources.list.d/i2pchat.list`

### ℹ️ About

I2PChat is a cross‑platform chat client for the [I2P](https://i2pd.website) anonymity network over **SAM** — **C++ Qt 6 GUI**, **FTXUI TUI**, and **Android** on one shared core.

### Audit / Аудит

[![English audit](https://img.shields.io/badge/🔍%20Audit-EN-green.svg)](docs/AUDIT_EN.md)
[![Русский аудит](https://img.shields.io/badge/🔍%20Аудит-RU-orange.svg)](docs/AUDIT_RU.md)

---

<details>
<summary>📜 <i>Sur le secret</i> — Pierre Janet</summary>

<br>

> *Chez l'homme naïf la croyance est liée à son expression. Avoir une croyance, c'est l'exprimer, l'affirmer; beaucoup de personnes disent: «Si je ne peux pas parler tout haut, je ne peux pas penser. Si je ne parle pas de ce en quoi je crois, je ne peux pas y croire. Et, au contraire, quand je crois quelque chose, il faut que je l'affirme; quand je pense quelque chose, il faut que je le dise.» Si l'on empêche ces personnes de parler, elles penseront à autre chose. Le secret n'est donc pas une fonction psychologique primitive, c'est un phénomène tardif. Il apparaît à l'époque de la réflexion.*
>
> *Il vaut mieux ne pas communiquer ses projets: en les racontant on se met immédiatement dans une position défavorable. Même si l'idée n'est pas prise, elle sera critiquée d'avance. Il ne faut pas montrer les brouillons. Que se passera-t-il si vous commencez à exprimer toutes vos rêveries, toutes ces pensées «pour vous-même» qui vous soutiennent? Les autres se moqueront de vous, diront que c'est ridicule, absurde, et détruiront vos rêves. «Peu importe», direz-vous, «puisque je sais bien moi-même que ce ne sont que des rêves». Mais en détruisant vos rêves, ils emporteront aussi votre courage et l'enthousiasme que vous y puisiez.*
>
> *Il vient une époque où il n'est plus toujours bon d'exprimer au dehors les phénomènes psychologiques, de les rendre publics. Dans la société, dans le groupe auquel nous appartenons, il faut savoir garder certaines choses secrètes et en dire d'autres; avoir quelque chose pour soi et quelque chose pour les autres. C'est une opération difficile qui se rapproche de l'évaluation, car pour produire une impression favorable il vaut mieux ne pas tout dire. Tout le monde devrait savoir faire cela. Mais c'est difficile et les timides y réussissent mal; aussi l'une de leurs difficultés dans la société est-elle un trouble de la fonction du secret.*
>
> *Il existe toute une catégorie de personnes — les primitifs, les enfants, les malades — chez qui la fonction du secret n'existe pas; ils ne savent pas ce que c'est. Le petit enfant n'a pas de secret. Le malade en état de désagrégation mentale parle tout haut et dit toutes sortes de sottises: il ne comprend absolument pas qu'il y ait des choses qu'il faut garder secrètes.*

</details>

---

<p align="center">
  Created with ❤️ by <b>Vade</b> for the privacy and anonymity community
  <br><br>
  © 2026 Vade
</p>
