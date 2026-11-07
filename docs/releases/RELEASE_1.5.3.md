# I2PChat v1.5.3

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- A Status section sits beside Chats and Settings. Service notices stay out of the conversation. The TUI sends on Enter; a new line is Shift/Ctrl/Alt+Enter.
- A router on another machine (LAN or Yggdrasil) is an explicit opt-in. The SAM connection is not encrypted. Bundled i2pd still binds loopback only.
- Incoming files and images have separate folders. Accept uses that folder; Save as… keeps one file elsewhere. An ephemeral mode deletes received files on exit and on profile switch.
- A peer that already had a secure channel this run reconnects on its own: 10s, then 20, 30, 40, 50, then every 60s. An explicit disconnect stops that.
- A profile can take an optional password (Argon2id). The same file opens on the computer and the phone. A profile backup still uses its own passphrase and moves the address and contacts between devices.
- Check for updates reads [GitHub releases](https://github.com/MetanoicArmor/I2PChat-ng/releases) and opens that page. A custom `*.i2p` listing still goes through the I2P HTTP proxy.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Password-less profiles keep opening. A passworded profile asks again after import.

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

- Рядом с чатами и настройками есть раздел Status. Служебные сообщения не попадают в переписку. В TUI Enter отправляет сообщение, новая строка — Shift/Ctrl/Alt+Enter.
- Роутер на другой машине (локальная сеть или Yggdrasil) включается явно. Соединение SAM не шифруется. Встроенный i2pd по-прежнему слушает только loopback.
- Файлы и картинки сохраняются в разные папки. «Принять» кладёт файл туда, «Сохранить как…» оставляет один файл в другом месте. Временный режим удаляет полученные файлы при выходе и при смене профиля.
- Собеседник, с которым в этом запуске уже был защищённый канал, переподключается сам: 10 с, затем 20, 30, 40, 50 и дальше каждые 60 с. Явный разрыв это останавливает.
- У профиля может быть пароль (Argon2id). Один и тот же файл открывается на компьютере и на телефоне. Архив профиля по-прежнему со своим паролем и переносит адрес и контакты между устройствами.
- Проверка обновлений читает [релизы на GitHub](https://github.com/MetanoicArmor/I2PChat-ng/releases) и открывает эту страницу. Свой список `*.i2p` по-прежнему идёт через HTTP-прокси I2P.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Профили без пароля открываются как раньше. Профиль с паролем после импорта спрашивает его снова.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.3.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.3.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.3.zip` | Unzip → open I2PChat.app |
