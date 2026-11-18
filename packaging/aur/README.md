# AUR: I2PChat-ng packages

В AUR уже есть **`i2pchat-git`** — это **другой** апстрим ([`vituperative/i2pchat`](https://github.com/vituperative/i2pchat), Qt5), не этот репозиторий. Для **MetanoicArmor/I2PChat-ng** (C++ Qt 6 / FTXUI) используйте пакеты ниже.

| Package | Kind | Notes |
|---|---|---|
| [`i2pchat-bin/`](i2pchat-bin/) | Binary GUI | Official AppImage zip (`I2PChat-linux-{x86_64,aarch64}-v*.zip`) |
| [`i2pchat-tui-bin/`](i2pchat-tui-bin/) | Binary TUI | Official TUI zip → `/opt/i2pchat-tui`, command **`i2pchat-tui`** |
| [`i2pchat-ng-git/`](i2pchat-ng-git/) | Source GUI | Builds Qt 6 client from git (`i2pchat-git` name is taken) |
| [`i2pchat-ng-tui-git/`](i2pchat-ng-tui-git/) | Source TUI | Builds FTXUI client from git (FTXUI via FetchContent if missing) |

`pkgdesc` style: *P2P (peer-to-peer) chat client over I2P network (GUI/TUI version)* — without repeating the package name.

## Публикация на AUR

1. Зарегистрируйтесь на [aur.archlinux.org](https://aur.archlinux.org/).
2. Добавьте в профиль AUR **SSH public key** (Account → SSH Keys). Проверка: `ssh -T aur@aur.archlinux.org` → **Welcome to AUR, …**
3. **Первый раз — зарегистрировать имена пакетов.** Пока пакета нет в AUR, `git clone ssh://aur@aur.archlinux.org/<name>.git` не сработает. Откройте [Submit](https://aur.archlinux.org/submit/) и создайте каждый новый пакет, подставив `PKGBUILD` и `.SRCINFO` из соответствующего каталога здесь. Уже существующие **`i2pchat-bin`** / **`i2pchat-tui-bin`** достаточно обновлять через скрипт.
4. Автор git-коммитов (иначе `publish-to-aur.sh` остановится):
   ```bash
   git config user.email "you@example.com"
   git config user.name "Your Name"
   ```
   Или: `export AUR_GIT_EMAIL=… AUR_GIT_NAME=…`.

5. Обновление из корня клона I2PChat:
   ```bash
   eval "$(ssh-agent -s)"
   ssh-add ~/.ssh/aur_ed25519
   ./packaging/aur/publish-to-aur.sh
   ```
   Скрипт синхронизирует все четыре пакета в ветку **`master`**.

   **Fish:** `eval "$(ssh-agent -s)"` — синтаксис bash/zsh. Удобнее:
   `bash -lc 'eval "$(ssh-agent -s)"; ssh-add ~/.ssh/aur_ed25519; cd ~/GitHub/I2PChat && ./packaging/aur/publish-to-aur.sh'`

После правки `PKGBUILD`:

```bash
cd packaging/aur/i2pchat-bin
makepkg --printsrcinfo > .SRCINFO
```

## Bump версии (-bin)

0. Хеши с GitHub Releases: **`./packaging/refresh-checksums.sh vX.Y.Z`**
1. Обновите `pkgver` / `pkgrel` и `sha256sums` в обоих `-bin` PKGBUILD.
2. `makepkg --printsrcinfo > .SRCINFO`, затем `./packaging/aur/publish-to-aur.sh`.

`-git` пакеты сами берут `pkgver` из `git describe` при сборке у пользователя.
