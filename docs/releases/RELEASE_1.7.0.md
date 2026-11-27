# I2PChat v1.7.0

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- **Calls (desktop + Android):** Opus/VP8 quality path with jitter/FEC/XOR parity, adaptive bitrate, and hardened signaling (peer-bound ACCEPT/HANGUP/MEDIA, datagram sender check, invite rate limits, bounded JPEG/VP8 decode).
- **One-way video fixes:** accepting a video call turns the camera on again; frames rebuilt from XOR parity are delivered as video; cold datagram paths probe at one frame per second until RX starts so i2pd LeaseSet warmup does not crush quality to 160p with RX 0.
- **Android call UI:** status-bar safe insets so settings/minimize stay tappable; tap a video pane to expand it nearly full-screen with a corner PiP for the other feed.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x**, **1.5.x**, and **1.6.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.7.0**.
- Both ends of a call should be on **1.7.0** for two-way video after accept and for parity recovery; older peers still talk audio.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

Per-platform build jobs skip GPG (`I2PCHAT_SKIP_GPG_SIGN=1`); the `sign-checksums` job merges platform sums and must upload `SHA256SUMS.asc` (release or apt signing secret, fingerprint above). Re-sign locally if needed: `./scripts/sign_release_checksums.sh v1.7.0`.

## RU

### Кратко

Ветка **`python`** остаётся десктопом (PyQt6 и Textual TUI). Android — Kotlin поверх `cpp/core`. Handshake **v4** не менялся.

### Основные изменения

- **Звонки (десктоп + Android):** Opus/VP8, jitter/FEC/XOR parity, адаптивный битрейт; жёсткая привязка сигналинга и датаграм к собеседнику, лимиты invite, ограничение JPEG/VP8.
- **Одностороннее видео:** ответ на видеозвонок снова включает камеру; кадры после parity приходят как видео; пока нет RX, уходит не больше одного кадра в секунду — без сброса в 160p из‑за прогрева LeaseSet.
- **Android UI звонка:** отступы от статус-бара; тап по картинке разворачивает её почти на весь экран, второе окно — в угол.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x**, **1.5.x** и **1.6.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.7.0** открываются.
- Для двустороннего видео после ответа и восстановления parity оба конца лучше держать на **1.7.0**.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.7.0.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.7.0.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.7.0.zip` | Unzip → open I2PChat.app |
