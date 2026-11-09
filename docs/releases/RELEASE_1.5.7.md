# I2PChat v1.5.7

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- TUI `/img` and `/img-bw` no longer crash on pictures wider than 80 pixels (`TypeError: 'int' object is not iterable`); non-image paths report an error instead of exiting.
- Help text explains `/sendfile`, `/sendpic`, `/img`, `/img-bw`, `/recent`, `/reply`, and the new `/savefile`.
- Switching profile while SAM startup is still running cancels the old init and starts the new one; an abandoned SESSION CREATE closes its SAM control socket.
- Contacts Use (F2) and `/contacts use` dial the chosen peer when the session is up, matching `/connect`.
- With ephemeral media on, received files can be kept: F3 **Save received item as…** or `/savefile <path|folder>`.
- Android sent pictures are stored under the active images directory and show as thumbnails; names come from the document `DISPLAY_NAME`; staging leftovers are cleared on launch.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.5.7**.

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

- TUI `/img` и `/img-bw` больше не падают на широких картинках; не-картинка даёт ошибку, а не выход из программы.
- В `/help` пояснены `/sendfile`, `/sendpic`, `/img`, `/img-bw`, `/recent`, `/reply` и новый `/savefile`.
- Смена профиля во время старта SAM отменяет старую инициализацию и поднимает новую; брошенный SESSION CREATE закрывает control-сокет.
- Кнопка Use в F2 и `/contacts use` дозваниваются до контакта, как `/connect`.
- При эфемерных медиа принятое можно сохранить: F3 **Save received item as…** или `/savefile <путь|каталог>`.
- На Android отправленные картинки лежат в каталоге изображений и показываются превью; имя берётся из `DISPLAY_NAME`; staging чистится при запуске.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.5.7** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.7.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.7.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.7.zip` | Unzip → open I2PChat.app |
