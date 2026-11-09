# I2PChat v1.5.9

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- Keepalive and liveness are tuned for I2P tunnel rebuilds: ping every **20s**, silent sessions stay up for **180s** before drop (was 15s / 90s on C++, and a 50s read timeout on desktop).
- Auto-reconnect after a secure drop waits **20s**, then **40 / 80 / 160**, and caps at **180s**, with jitter so both sides do not dial on the same tick (was linear 10…60s).
- Failed outbound dials use a slower exponential backoff (**8…120s**) so SAM is not hammered while tunnels are still building.
- C++ peer-link handshake timeout matches the desktop client at **90s** (was 45s).

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.5.9**.

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

- Keepalive и liveness подогнаны под пересборку туннелей I2P: пинг каждые **20 с**, тишина до **180 с** не рвёт сессию (было 15/90 на C++ и 50 с read timeout на десктопе).
- Авто-reconnect после обрыва защищённого канала: **20 с**, затем **40 / 80 / 160**, потолок **180 с**, плюс jitter (было линейно 10…60 с).
- Неудачный исходящий dial ждёт дольше (**8…120 с**), чтобы не долбить SAM, пока туннель ещё строится.
- Таймаут handshake на C++ peer-link — **90 с**, как на десктопе (было 45 с).

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.5.9** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.9.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.9.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.9.zip` | Unzip → open I2PChat.app |
