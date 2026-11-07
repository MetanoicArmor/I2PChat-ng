# I2PChat v1.5.2 — Python desktop is the shipping line again

## EN

### Scope

**`python`** is the development branch. Desktop builds are PyQt6 and the Textual TUI again. Android stays Kotlin + Compose over `cpp/core` and uses the same vNext handshake (**v4**).

**1.5.0** and **1.5.1** remain the C++ desktop releases. This tag does not replace those zip bytes; it is the next version, built from this branch.

### Highlights

- Unknown peers stay out of Saved peers and do not become the active conversation until Trust is accepted. The same gate is on the Python core and on the Android native core (`trust_auto_accept_first_sighting` is off).
- A remote group frame cannot replace the local roster. Unknown groups are rejected. A `join` adds only the authenticated sender.
- macOS quit no longer calls `setOverrideCursor(WaitCursor)` while AppKit is terminating. Qt 6.11 on macOS 27 turned that cursor into a `CGImage` and trapped in `CGColorSpaceGetType`.
- A tag `vX.Y.Z` builds Linux (Ubuntu 22.04), macOS, Windows, and both Android APKs into a draft. The draft is published only after the expected asset names are present. `.deb` and the x86_64 `.rpm` are attached by the existing Linux packages workflow.

### Compatibility

- Wire handshake **v4** is unchanged versus **1.4.x** and **1.5.x**.
- Profiles, contacts, and history layout match the Python 1.4.1 client.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

CI uploads the draft without `SHA256SUMS.asc` (`I2PCHAT_SKIP_GPG_SIGN=1`). Sign that file before treating the release as final, or pass `--skip-gpg` to `scripts/verify_github_release_assets.sh`.

## RU

### Кратко

Ветка **`python`** снова основная. Десктоп — PyQt6 и Textual TUI. Android — Kotlin поверх `cpp/core`, тот же handshake **v4**.

**1.5.0** и **1.5.1** остаются релизами десктопа на C++. Этот тег их не подменяет.

### Основные изменения

- Незнакомый пир не попадает в Saved peers и не становится текущим собеседником, пока не принято Trust. Та же граница стоит в Python-ядре и в нативном ядре Android.
- Чужой кадр группы не подменяет локальный состав. Неизвестная группа отклоняется; `join` добавляет только отправителя.
- Выход на macOS больше не ставит курсор ожидания во время `NSApplication terminate`. На Qt 6.11 / macOS 27 это падало в `CGImageCreate`.
- Тег `vX.Y.Z` собирает Linux (Ubuntu 22.04), macOS, Windows и оба Android APK в черновик. Черновик публикуется после проверки имён ассетов. `.deb` и `.rpm` дописывает отдельный workflow.

### Совместимость

- Handshake **v4** тот же, что у **1.4.x** и **1.5.x**.
- Профили, контакты и история совпадают с клиентом 1.4.1.

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.2.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.2.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.2.zip` | Unzip → open I2PChat.app |
