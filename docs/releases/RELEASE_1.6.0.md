# I2PChat v1.6.0

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- After a live secure session drops, Send waits about **60s** for auto-reconnect before BlindBox offline delivery (explicit offline / force-offline skips the wait).
- Quitting the desktop app can flush remaining direct-chat drafts through BlindBox so unsent compose text is not left behind.
- Android **Status → Exit** offers leave-in-background vs quit; quit can flush drafts the same way and removes the task instead of bouncing back to the profile screen (which used to warm the router again).
- Android launcher uses the same shared **`icon.png`** artwork as desktop.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.6.0**.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

CI uploads the draft without `SHA256SUMS.asc` (`I2PCHAT_SKIP_GPG_SIGN=1`). Sign that file before treating the release as final, or pass `--skip-gpg` to `scripts/verify_github_release_assets.sh`.

## RU

### Кратко

Ветка **`python`** остаётся десктопом (PyQt6 и Textual TUI). Android — Kotlin поверх `cpp/core`. Handshake **v4** не менялся.

### Основные изменения

- После обрыва защищённой сессии Send ждёт около **60 с** авто-reconnect, прежде чем уходить в BlindBox (явный offline / force-offline ждут не будут).
- Выход с десктопа может отправить оставшиеся прямые черновики через BlindBox.
- На Android **Status → Exit**: оставить в фоне или завершить; завершение может сбросить черновики тем же путём и убирает задачу, а не возвращает на экран профиля с повторным стартом роутера.
- Иконка Android launcher — тот же **`icon.png`**, что и на десктопе.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.6.0** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.6.0.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.6.0.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.6.0.zip` | Unzip → open I2PChat.app |
