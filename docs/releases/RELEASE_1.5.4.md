# I2PChat v1.5.4

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- Android bundled **i2pd 3.0.0** starts through the official `org.purplei2p.i2pd.nativelib.I2pdNative` JNI surface. The previous wrapper class made `JNI_OnLoad` fail and showed “failed to load libi2pd.so”. Restart still kills the `:i2pd` process and starts a new one (init may run only once per process).
- Group invites are shorter: new tokens seal a compact binary body (wire version 3). Typical invites with two or three real destinations drop from roughly 700+ characters toward the mid-300s / low-400s. Clients on **1.5.3** and earlier cannot open these new tokens. This build still accepts the older sealed JSON form and the legacy `__I2PCHAT_GROUP_INVITE__:` prefix.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites are **not** readable by **1.5.3** and older. Older invite tokens still open on **1.5.4**.

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

- Встроенный **i2pd 3.0.0** на Android стартует через официальный JNI-класс `org.purplei2p.i2pd.nativelib.I2pdNative`. Старая обёртка ломала `JNI_OnLoad` и показывала «не удалось загрузить libi2pd.so». Перезапуск по-прежнему убивает процесс `:i2pd` и поднимает новый (init в одном процессе можно вызвать только один раз).
- Инвайты в группы короче: новые токены — компактное бинарное тело (wire version 3). Типичный инвайт с двумя–тремя адресами сжимается примерно с 700+ символов до середины 300 / начала 400. Клиенты **1.5.3** и старше такие токены не откроют. Эта сборка по-прежнему принимает старый sealed JSON и префикс `__I2PCHAT_GROUP_INVITE__:`.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты **не** читаются на **1.5.3** и старше. Старые токены на **1.5.4** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.4.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.4.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.4.zip` | Unzip → open I2PChat.app |
