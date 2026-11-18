# Firejail-профили I2PChat (Linux)

Черновики профилей для [firejail](https://github.com/netblue30/firejail): Qt GUI и TUI. После локального теста их можно отправить PR в upstream (`etc/profile-a-l/`).

| Файл | Назначение |
|------|------------|
| [`i2pchat.profile`](i2pchat.profile) | GUI (`i2pchat`, AppImage) |
| [`i2pchat-tui.profile`](i2pchat-tui.profile) | TUI (`i2pchat-tui` → `I2PChat-tui`) |
| `I2PChat.profile`, `I2PChat.AppImage.profile` | алиасы → GUI |
| `I2PChat-tui.profile` | алиас → TUI |
| [`UPSTREAM.md`](UPSTREAM.md) | что добавить в PR firejail (`disable-programs.inc`, `firecfg.config`) |

## Что учитывают профили

- Данные: `~/.i2pchat` (профили, history, downloads/images, bundled `i2pd`)
- GUI settings (Qt): `~/.config/I2PChat`
- Сеть: SAM/`i2pd` + BlindBox HTTPS (`protocol unix,inet,inet6`, `netfilter`, `private-etc @tls-ca`)
- Keyring: D-Bus `org.freedesktop.secrets`
- GUI: уведомления, tray (`?ALLOW_TRAY`), звук, X11/Wayland через `@x11`
- TUI: shell-лаунчер, без X11/звука/3D
- Пакеты: `/opt/i2pchat`, `/opt/i2pchat-tui`

Отправка файлов из произвольных каталогов: по умолчанию whitelisted `${DOWNLOADS}` и `${PICTURES}`. Остальное — в `*.local` (см. ниже).

## Локальный тест

```bash
# поставить профили в пользовательский каталог firejail
mkdir -p ~/.config/firejail
cp packaging/firejail/*.profile ~/.config/firejail/

# GUI: пакет /usr/bin/i2pchat (symlink на AppImage)
firejail --profile=i2pchat i2pchat

# GUI: сырой AppImage (обязателен --appimage)
firejail --appimage --profile=i2pchat /path/to/I2PChat.AppImage
# или по basename:
firejail --appimage /opt/i2pchat/I2PChat.AppImage

# TUI
firejail --profile=i2pchat-tui i2pchat-tui
```

Чеклист:

1. Старт с system `i2pd` (SAM `127.0.0.1:7656`) и с bundled router.
2. Загрузка профиля, keyring / `.dat.wrap`.
3. Чат, картинка/файл из Downloads, BlindBox endpoint HTTPS.
4. GUI: tray, нотификации, звук, выход.
5. TUI: навигация, те же сетевые сценарии.
6. При поломке: `firejail --profile=i2pchat --ignore=OPTION …` или правки в `~/.config/firejail/i2pchat.local`.

Пример `~/.config/firejail/i2pchat.local`:

```
# доступ к Документам для бэкапов / отправки файлов
noblacklist ${DOCUMENTS}
whitelist ${DOCUMENTS}

# если apparmor/seccomp мешает AppImage или i2pd:
#ignore apparmor
#ignore restrict-namespaces
```

Системная установка для теста (нужен root):

```bash
sudo cp packaging/firejail/i2pchat.profile \
        packaging/firejail/i2pchat-tui.profile \
        packaging/firejail/I2PChat.profile \
        packaging/firejail/I2PChat.AppImage.profile \
        packaging/firejail/I2PChat-tui.profile \
        /etc/firejail/
```

## PR в firejail

См. [`UPSTREAM.md`](UPSTREAM.md). Кратко: скопировать профили в `etc/profile-a-l/`, дописать blacklist в `etc/inc/disable-programs.inc`, бинарники в `src/firecfg/firecfg.config`, пройти их checklist из `etc/templates/profile.template`.
