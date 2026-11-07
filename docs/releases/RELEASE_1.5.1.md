# I2PChat v1.5.1 — inbound trust boundary, group roster, Android arm32

As of **1.5.2** the shipping desktop is the `python` branch again. This page is the record of the **1.5.1** C++ release. The Trust gate described here is what 1.5.2 ports onto the Python client and the Android core.

## EN

### Scope

Patch on top of **1.5.0**. Handshake stays **v4** and remains wire-compatible with **1.4.x** and **1.5.0**.

### Highlights

- An unknown peer who opens a tunnel is not written into Saved peers and does not become the active conversation until you accept Trust. Rejecting the dialog closes the stream and saves nothing.
- A remote group frame cannot replace the local roster. Unknown groups are rejected (join with a signed invite). A `join` announcement adds only the authenticated sender.
- Release builds now include the **armeabi-v7a** sideload APK (`I2PChat-android-v1.5.1-arm32-release.apk`). It does not bundle i2pd; the phone needs SAM on `127.0.0.1:7656`. The 64-bit APK is unchanged.

### Compatibility

- **Wire handshake v4** unchanged vs **1.4.x** / **1.5.0**.
- Profiles, contacts, and history layout are unchanged.

### Verification

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS   # or: shasum -a 256 -c SHA256SUMS
```

## RU

### Кратко

Патч к **1.5.0**. Handshake **v4**, совместим с **1.4.x** и **1.5.0**.

### Основные изменения

- Незнакомый пир не попадает в Saved peers и не становится текущим собеседником, пока вы не нажмёте Trust. Отказ закрывает поток и ничего не сохраняет.
- Чужой кадр группы не подменяет локальный состав. Неизвестная группа отклоняется; `join` добавляет только самого отправителя.
- В релиз добавлен sideload-APK **armeabi-v7a** без встроенного i2pd (`I2PChat-android-v1.5.1-arm32-release.apk`). 64-битный APK без изменений.

### Совместимость

По проводу как **1.5.0**. Профили те же.

### Проверка

```bash
gpg --keyserver keys.openpgp.org --recv-keys 2BA0C56D8240077F9773248A2C05CFB3F6DFDF99
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

---

### 🌐 Cross-platform I2P Chat Client

**One app. Three platforms. No Python required.**

| Platform | Download | Launch |
|----------|----------|--------|
| Windows | `I2PChat-windows-x64-v1.5.1.zip` | Unzip → run I2PChat.exe |
| Linux | `I2PChat-linux-x86_64-v1.5.1.zip` | Unzip → chmod +x I2PChat.AppImage → run |
| macOS | `I2PChat-macOS-arm64-v1.5.1.zip` | Unzip → open I2PChat.app |
