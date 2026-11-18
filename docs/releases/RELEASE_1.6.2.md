# I2PChat v1.6.2

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- **Avatars:** set your picture on desktop and Android; Trusted peers receive it after a secure session; after profile import the peer is asked again via `AVATAR_REQUEST`. Contact lists show the image when available.
- **TUI:** send a literal slash with `//…`; `/reply N text` sends the quote and reply in one go; incoming file offers keep the Accept / Save as… / Reject modal.
- **Android:** bubble clocks use the device timezone; fullscreen image/video viewer; emoji panel toggles with the soft keyboard; SAM session rebuild on tunnel loss; delete a single message from history.
- **Android background chat:** a main-process `ChatForegroundService` keeps the SAM/session alive when the UI is backgrounded; last profile is remembered and auto-resumed after a cold start (#46).
- **Packaging:** Firejail profiles for Linux GUI/TUI under `packaging/firejail/`; AUR `-bin` plus `i2pchat-ng-git` / `i2pchat-ng-tui-git`.
- **Release integrity:** CI merges platform checksums and requires a signed `SHA256SUMS.asc` (release or apt signing key). BlindBox SAM tunnels default to **4** hops like live chat.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x**, **1.5.x**, and **1.6.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.6.2**.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

Per-platform build jobs skip GPG (`I2PCHAT_SKIP_GPG_SIGN=1`); the `sign-checksums` job merges platform sums and must upload `SHA256SUMS.asc` (release or apt signing secret, fingerprint above). Re-sign locally if needed: `./scripts/sign_release_checksums.sh v1.6.2`.

## RU

### Кратко

Ветка **`python`** остаётся десктопом (PyQt6 и Textual TUI). Android — Kotlin поверх `cpp/core`. Handshake **v4** не менялся.

### Основные изменения

- **Аватары:** своя картинка на десктопе и Android; Trusted-пиры получают её после secure; после импорта профиля — повторный запрос `AVATAR_REQUEST`. В списках контактов показывается изображение.
- **TUI:** литеральный `/` через `//…`; `/reply N текст` сразу отправляет цитату и ответ; оффер файла — Accept / Save as… / Reject.
- **Android:** локальное время в пузырях; fullscreen image/video; emoji ↔ клавиатура; пересборка SAM при потере туннелей; удаление одного сообщения.
- **Android фон:** `ChatForegroundService` в main-процессе держит SAM/сессию при уходе UI в фон; последний профиль запоминается и поднимается после cold start (#46).
- **Пакеты:** Firejail-профили GUI/TUI; AUR `-bin` и `-ng-git` / `-ng-tui-git`.
- **Релиз:** обязательная подпись `SHA256SUMS.asc`; BlindBox SAM по умолчанию **4** хопа.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x**, **1.5.x** и **1.6.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.6.2** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.6.2.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.6.2.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.6.2.zip` | Unzip → open I2PChat.app |
