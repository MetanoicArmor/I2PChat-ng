# I2PChat v1.5.8

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- Default SAM tunnel length is **4** hops on desktop and Android (was 3). BlindBox replica hops stay at 2.
- With **Do not keep files** on Android, received pictures stay in RAM and are never written to storage; the chat shows them from memory and can Save as… while the session is open.
- With the same setting, other received files are encrypted into the app cache with a random key that exists only while the chat is running. Closing the chat (or switching profile) destroys the key; ciphertext left on flash cannot be opened again. Open and Save as… still work while the chat is up.
- Ephemeral cache is cleared on stop; sealed leftovers after a crash remain unreadable without the wiped key.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.5.8**.

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

- Длина туннеля SAM по умолчанию — **4** хопа на десктопе и Android (было 3). У BlindBox replica по-прежнему 2.
- При **Не хранить файлы** на Android принятые картинки остаются в оперативной памяти и не пишутся на диск; превью из памяти, Save as… пока чат открыт.
- При том же режиме остальные принятые файлы шифруются в кэше приложения ключом, который существует только пока чат запущен. Закрытие чата (или смена профиля) уничтожает ключ; шифртекст на флеше больше не открыть. Open и Save as… работают, пока чат жив.
- Эфемерный кэш чистится при остановке; оставшийся после краша шифртекст без ключа бесполезен.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.5.8** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.8.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.8.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.8.zip` | Unzip → open I2PChat.app |
