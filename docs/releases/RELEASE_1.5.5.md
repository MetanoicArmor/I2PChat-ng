# I2PChat v1.5.5

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- Android chat shell refresh: dark chat list as home, circular hashed avatars, rounded composer, and the Status / Chats / Settings bottom tabs again on those three screens.
- Terminal-style color palettes on Android (Tokyo Night, Dracula, Nord, Gruvbox, and the rest of the Textual set) next to the default grass theme.
- Profile tools on the chooser: show the base32 address, rename, and delete saved profiles (password-protected profiles stay locked until the password is entered).
- Message actions on Android catch up with desktop: long-press copy/reply/retry, rename contact notes, and copy peer address.
- Notification taps no longer spawn a second activity that drops the open session back to the profile chooser; they reuse the running screen and open the peer chat when the session is live.
- Image and file transfers no longer drop an in-flight frame on a read timeout (avoids desyncing replay protection). Android also surfaces the real bundled-router failure text and lets i2pd bind IPv6/SSU2.
- macOS bundled i2pd uses the static `darwin-arm64` payload again (system libs only). The previous Homebrew-linked binary failed at launch without Boost/OpenSSL `*.dylib` next to it.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.5.5**.

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

- Обновлённый Android-шелл чата: тёмный список как дом, круглые аватары, скруглённый composer и снова три вкладки Status / Chats / Settings на этих экранах.
- Палитры в стиле терминала (Tokyo Night, Dracula, Nord, Gruvbox и остальные из набора Textual) рядом с темой «трава» по умолчанию.
- На экране выбора профиля: показать base32-адрес, переименовать и удалить сохранённые профили (закрытые паролем остаются закрытыми, пока пароль не введён).
- Действия с сообщениями на Android ближе к десктопу: долгое нажатие — копировать / ответить / повторить, заметка контакта, копирование адреса собеседника.
- Нажатие на уведомление больше не создаёт вторую активность и не сбрасывает сессию на выбор профиля: возвращает текущий экран и открывает чат, если сессия уже запущена.
- Передача картинок и файлов больше не теряет кадр при таймауте чтения (не ломает replay protection). На Android показывается реальный текст ошибки встроенного роутера; i2pd может слушать IPv6/SSU2.
- Встроенный i2pd на macOS снова статический payload `darwin-arm64` (только системные библиотеки). Прежний Homebrew-бинарь без Boost/OpenSSL `*.dylib` рядом не запускался.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.5.5** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.5.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.5.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.5.zip` | Unzip → open I2PChat.app |
