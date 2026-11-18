# Firejail-профили для I2PChat

Профили в стиле upstream [firejail](https://github.com/netblue30/firejail/tree/master/etc/profile-m-z): `include <name>.local`, `include globals.local`, `disable-*.inc`, `whitelist-*.inc`. Локальные правки пользователя кладите в `~/.config/firejail/i2pchat.local` / `i2pchat-tui.local` — они подключаются первыми и переживают обновления.

| Профиль | Что запускает |
|---------|---------------|
| [`i2pchat.profile`](i2pchat.profile) | GUI: `/usr/bin/i2pchat` (deb, AUR `i2pchat-ng-git`) и AppImage `/opt/i2pchat/I2PChat.AppImage` (deb, AUR `i2pchat-bin`) |
| [`i2pchat-tui.profile`](i2pchat-tui.profile) | TUI: `/usr/bin/i2pchat-tui` (deb, AUR `i2pchat-tui-bin`, `i2pchat-tui-ng-git`) |

## Что разрешено

- **Данные:** только `~/.i2pchat/**` (профили, загрузки, `router_prefs.json`, datadir встроенного i2pd). Остальной `$HOME` скрыт (`whitelist`). Если в настройках задан другой каталог загрузок/картинок — раскомментируйте `noblacklist` или добавьте `whitelist` в `.local`.
- **Эфемерные медиа:** `/dev/shm/i2pchat-<uid>/` — доступно, т.к. `private-dev` оставляет `/dev/shm` (отдельный `whitelist` на `/dev/shm` не нужен и сломал бы `/dev`).
- **Сеть:** `protocol unix,inet,inet6,netlink`, сетевой namespace не изолируется. Приложению нужны loopback-порты: SAM **7656** (системный i2pd) и **17656** (встроенный), а для встроенного i2pd ещё **14444** (HTTP proxy), **14447** (SOCKS), **17070** (web-консоль) плюс исходящий интернет для самого роутера. Firejail без приватного netns не умеет фильтровать по портам, поэтому порты задокументированы в профиле, а не принудительно ограничены. Для жёсткой фильтрации используйте `net <iface>` + `netfilter` в `.local`.
- **GUI:** X11 / Wayland (через `whitelist-runuser-common.inc`), DRI (`no3d` не включён), D-Bus `filter`: `org.freedesktop.secrets`, `org.kde.kwalletd5/6`, `org.freedesktop.Notifications`, `org.kde.StatusNotifierWatcher`, `org.freedesktop.portal.*`; system bus выключен. Для Wayland-only добавьте в `i2pchat.local` строку `x11 none`.
- **TUI:** `x11 none`, `no3d`, `nosound`; D-Bus только для keyring. Буфер обмена через `xclip`/`xsel` требует `ignore x11 none` в `i2pchat-tui.local`.
- **Запуск i2pd:** `/opt/i2pchat*` остаётся исполняемым и доступным **read-only** (portable-раскладка `vendor/i2pd/<os-arch>/i2pd`; в AppImage — распаковка в `/tmp/.mount_*` / `/tmp/appimage_extracted_*`). `noexec` стоит только на `$HOME` и `$XDG_RUNTIME_DIR`. `disable-exec.inc`, `disable-devel.inc`, `disable-interpreters.inc` **не подключены** сознательно (нужны python для deb/AUR-варианта и exec из `/tmp` для AppImage).

## Установка профилей

```bash
mkdir -p ~/.config/firejail
cp packaging/firejail/i2pchat.profile packaging/firejail/i2pchat-tui.profile ~/.config/firejail/
# системно (для автоматического запуска через `firecfg`):
# sudo cp packaging/firejail/*.profile /etc/firejail/
```

Профили из `~/.config/firejail/` имеют приоритет над `/etc/firejail/`.

## Проверка

Нужен `firejail` (≥ 0.9.72), запущенный десктоп для GUI и работающий i2pd (`127.0.0.1:7656`) либо режим «встроенный i2pd» (только AppImage/portable).

### 1. Установка из .deb (`/usr/bin/i2pchat`, `/usr/bin/i2pchat-tui`)

```bash
sudo apt install ./i2pchat_*_amd64.deb ./i2pchat-tui_*_amd64.deb

# Синтаксис профиля / итоговые правила
firejail --profile=$HOME/.config/firejail/i2pchat.profile --debug-caps /bin/true

# GUI
firejail --profile=$HOME/.config/firejail/i2pchat.profile /usr/bin/i2pchat
# TUI
firejail --profile=$HOME/.config/firejail/i2pchat-tui.profile /usr/bin/i2pchat-tui
```

Проверить: окно открылось, `~/.i2pchat` читается/пишется, SAM-подключение успешно (статус роутера в UI), keyring (Secret Service) отвечает, `ls ~` внутри песочницы (`firejail --profile=… bash`) не показывает чужие файлы.

> В deb `/usr/bin/i2pchat` — симлинк на `/opt/i2pchat/I2PChat.AppImage` (см. [`../debian/build-deb-from-appimage.sh`](../debian/build-deb-from-appimage.sh)), поэтому проверка deb-GUI — это фактически проверка AppImage-пути ниже. Пакеты из исходников (`debian/`, AUR `-ng-git`) запускают python-скрипт и AppImage не используют.

### 2. AppImage (`/opt/i2pchat/I2PChat.AppImage`)

AppImage монтирует себя через FUSE, а профиль включает `nonewprivs` и `caps.drop all` — `fusermount` внутри песочницы не сработает. Два рабочих способа:

```bash
# а) встроенная поддержка firejail (монтирование делает firejail)
firejail --appimage --profile=$HOME/.config/firejail/i2pchat.profile /opt/i2pchat/I2PChat.AppImage

# б) без FUSE: распаковка в /tmp (private-tmp это допускает)
APPIMAGE_EXTRACT_AND_RUN=1 firejail --profile=$HOME/.config/firejail/i2pchat.profile \
  /opt/i2pchat/I2PChat.AppImage
```

Проверить дополнительно: встроенный i2pd стартует (в настройках роутера выбран «bundled»), слушает `127.0.0.1:17656`, `14444`, `14447`, `17070` (`ss -ltnp` на хосте, процесс `i2pd` из дерева firejail: `firejail --tree`), данные пишутся только в `~/.i2pchat`.

### 3. Диагностика отказов

```bash
firejail --debug --profile=$HOME/.config/firejail/i2pchat.profile /usr/bin/i2pchat 2>&1 | less
sudo journalctl -k | grep -i -E 'seccomp|apparmor'   # блокировки
```

Типичные правки в `.local`: доступ к своему каталогу загрузок (`whitelist ${HOME}/Downloads`), `ignore nonewprivs` (если `--appimage` не подходит), `ignore apparmor` (если нет профиля `firejail-default`), `ignore seccomp.block-secondary`.

Профили не тестировались на каждом дистрибутиве: после изменения путей установки в пакетах перепроверьте обе схемы (deb и AppImage).
