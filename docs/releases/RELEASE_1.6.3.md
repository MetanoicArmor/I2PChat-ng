# I2PChat v1.6.3

## EN

### Scope

**`python`** stays the shipping desktop (PyQt6 and the Textual TUI). Android stays Kotlin + Compose over `cpp/core`. Handshake **v4** is unchanged.

### Highlights

- **File transfer (Kennedy g2387):** live outbound writes are serialized so chat text cannot interrupt an in-flight file send; incoming files auto-accept into the downloads folder (no offer modal); multi-file send queue; TUI `/savefile <N>` resolves message `#` refs.
- **Android media:** themed chat wallpapers per terminal palette (gradient + motif behind the message list); media viewer survives rotation (`configChanges` + `rememberSaveable`); video ±10s double-tap seek and inline audio scrub polish; emoji caret stays after the inserted glyph.
- **Docs:** refreshed Android chat / Status screenshots including the wallpaper shot.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x**, **1.5.x**, and **1.6.x**.
- Compact group invites from **1.5.4** remain **not** readable by **1.5.3** and older. Older invite tokens still open on **1.6.3**.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

Per-platform build jobs skip GPG (`I2PCHAT_SKIP_GPG_SIGN=1`); the `sign-checksums` job merges platform sums and must upload `SHA256SUMS.asc` (release or apt signing secret, fingerprint above). Re-sign locally if needed: `./scripts/sign_release_checksums.sh v1.6.3`.

## RU

### Кратко

Ветка **`python`** остаётся десктопом (PyQt6 и Textual TUI). Android — Kotlin поверх `cpp/core`. Handshake **v4** не менялся.

### Основные изменения

- **Файлы (Kennedy g2387):** исходящие записи сериализованы — текст не рвёт отправку файла; входящие файлы принимаются сразу в downloads; очередь нескольких файлов; в TUI `/savefile <N>` по номеру сообщения.
- **Android media:** фоны чата под палитры тем; viewer не сбрасывается при повороте; scrub видео/аудио; caret после emoji.
- **Docs:** обновлены скриншоты Android (в т.ч. с wallpaper).

### Совместимость

- Handshake **v4** тот же, что у **1.4.x**, **1.5.x** и **1.6.x**.
- Компактные инвайты из **1.5.4** по-прежнему **не** читаются на **1.5.3** и старше. Старые токены на **1.6.3** открываются.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.6.3.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.6.3.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.6.3.zip` | Unzip → open I2PChat.app |
