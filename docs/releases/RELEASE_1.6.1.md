# I2PChat v1.6.1

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- Desktop **Connect** stays usable after an inbound hang-up / auto-reconnect: busy and `RECONNECTING` no longer grey the button; a click preempts a stuck silent dial.
- Android **Disconnect** no longer blocks later inbound from that peer — hang-up only cancels local outbound / auto-reconnect.
- Android **TOFU** on first inbound keeps the wire handshake through **FINISHED** and completes the same session on Accept (desktop → Android no longer requires the reverse dial first).
- Status tunnels stay OK while transport is `degraded` / `reconnecting`; BlindBox Status still waits for tunnels ready.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x**, **1.5.x**, and **1.6.0**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.6.1**.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

Per-platform build jobs skip GPG (`I2PCHAT_SKIP_GPG_SIGN=1`); the `sign-checksums` job merges platform sums and must upload `SHA256SUMS.asc` (release or apt signing secret, fingerprint above). Re-sign locally if needed: `./scripts/sign_release_checksums.sh v1.6.1`.

## RU

### Кратко

Ветка **`python`** остаётся десктопом (PyQt6 и Textual TUI). Android — Kotlin поверх `cpp/core`. Handshake **v4** не менялся.

### Основные изменения

- На десктопе **Connect** снова активна после обрыва / авто-reconnect: busy и `RECONNECTING` не гасят кнопку; клик перехватывает зависший silent dial.
- **Disconnect** на Android больше не блокирует входящие от того же пира — hang-up отменяет только свой исходящий / авто-reconnect.
- **TOFU** на первом входящем Android держит handshake до **FINISHED** и завершает ту же сессию по Accept (дозвон десктоп → Android больше не требует сначала звонка с телефона).
- В Status туннели остаются OK при `degraded` / `reconnecting`; BlindBox Status по-прежнему ждёт готовности туннелей.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x**, **1.5.x** и **1.6.0**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.6.1** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.6.1.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.6.1.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.6.1.zip` | Unzip → open I2PChat.app |
