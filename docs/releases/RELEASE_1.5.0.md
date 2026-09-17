# I2PChat v1.5.0 — C++ default client, Android, 16 KiB i2pd

## EN

### Scope

First release with the **C++** desktop client as the default tree (`c++` branch), plus a native **Android** app on the same core. Bundled `libi2pd.so` is rebuilt for **16 KiB** ELF pages (no `pageSizeCompat` dialog). Handshake remains **v4** — wire-compatible with **1.4.x** peers.

### Highlights

- **C++ GUI / TUI packaging** on Linux, macOS, and Windows (same zip names as prior PyQt6 releases).
- **Android** (`org.i2pchat.android`): Kotlin + Compose, bundled in-process i2pd, dark theme by default; tap **Online! My Address** to copy the destination.
- **16 KiB Android libs:** NDK r28 `libi2pd.so` + chat JNI; drop `android:pageSizeCompat`.
- **Desktop polish:** native Mac shortcut labels / Switch profile, Dock icon fix, media ACK and live-delivery race fixes (see recent commits).

### Compatibility

- **Wire handshake v4** unchanged vs **1.4.x**.
- Profiles / contacts / history layout match the desktop app; Android can import a desktop backup bundle.
- Historical **PyQt6** sources live on the **`python`** branch.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

### Maintainer checklist (for `v1.5.0` tag + GitHub assets)

1. Build/upload platform artifacts for `v1.5.0` (Windows / macOS arm64+x64 / Linux x86_64+aarch64 / Android APK, plus winget zips).
2. Publish signed `SHA256SUMS` + `SHA256SUMS.asc` (and per-arch sums when needed).
3. Refresh packaging manifests: `./packaging/refresh-checksums.sh 1.5.0`.
4. Publish notes: `gh release edit v1.5.0 --notes-file docs/releases/RELEASE_1.5.0.md`.

## RU

### Кратко

Релиз с **C++**-клиентом по умолчанию и нативным **Android** на том же ядре. Bundled `libi2pd` для **16 KiB** страниц. Handshake **v4**, совместим с **1.4.x**.

### Основные изменения

- Сборки GUI/TUI для Linux / macOS / Windows (как раньше по именам zip).
- Android: Compose, встроенный i2pd, тёмная тема по умолчанию, копирование адреса по тапу.
- 16 KiB ELF без `pageSizeCompat`.
- Правки Mac-меню/иконки и гонок доставки.

### Совместимость

С **1.4.x** по проводу. Профили как на десктопе. PyQt6 — ветка **`python`**.

### Проверка

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.0.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.0.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.0.zip` | Unzip → open I2PChat.app |
