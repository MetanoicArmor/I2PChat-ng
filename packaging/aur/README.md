# AUR: i2pchat-bin / i2pchat-tui-bin / i2pchat-ng-git / i2pchat-ng-tui-git

В AUR есть пакет **`i2pchat-git`** — это **другой** апстрим ([`vituperative/i2pchat`](https://github.com/vituperative/i2pchat), Qt5), не этот репозиторий. Для **MetanoicArmor/I2PChat** (ветка **`python`**) используйте пакеты ниже.

- [`i2pchat-bin/`](i2pchat-bin/) — GUI: официальный **AppImage**. В одном PKGBUILD два контура: **`source_x86_64`** и **`source_aarch64`**.
- [`i2pchat-tui-bin/`](i2pchat-tui-bin/) — только TUI: **`I2PChat-linux-*-tui-v*.zip`**, установка в `/opt/i2pchat-tui`, команда **`i2pchat-tui`**.
- [`i2pchat-ng-git/`](i2pchat-ng-git/) — GUI из git (`python` branch), wheel через `python -m build`.
- [`i2pchat-ng-tui-git/`](i2pchat-ng-tui-git/) — TUI из git (`python` branch); GUI entrypoint из пакета удаляется.

Суффикс **`-ng`** нужен, потому что имя **`i2pchat-git`** уже занято чужим пакетом.

## Публикация на AUR

1. Зарегистрируйтесь на [aur.archlinux.org](https://aur.archlinux.org/).
2. Добавьте в профиль AUR **SSH public key** (Account → SSH Keys). Проверка: `ssh -T aur@aur.archlinux.org` → приветствие **Welcome to AUR, …**
3. **Первый раз** для нового имени: `git clone ssh://aur@aur.archlinux.org/<pkg>.git` на пустой репозиторий (пакет уже должен существовать на AUR, иначе clone не сработает). При необходимости создайте пакет через [Submit](https://aur.archlinux.org/submit/) или пустой clone после регистрации имени.
4. **Один раз задайте автора git-коммитов** (в корне I2PChat или глобально), иначе `publish-to-aur.sh` остановится на `git commit`:
   ```bash
   git config user.email "you@example.com"
   git config user.name "Your Name"
   ```
   Либо только для скрипта: `export AUR_GIT_EMAIL=… AUR_GIT_NAME=…`.

5. **Дальнейшие обновления** — из корня клона I2PChat:
   ```bash
   eval "$(ssh-agent -s)"
   ssh-add ~/.ssh/aur_ed25519
   ./packaging/aur/publish-to-aur.sh
   ```

   **Fish:** `eval "$(ssh-agent -s)"` — синтаксис **bash/zsh**. Удобно:
   `bash -lc 'eval "$(ssh-agent -s)"; ssh-add ~/.ssh/aur_ed25519; cd ~/GitHub/I2PChat && ./packaging/aur/publish-to-aur.sh'`

После изменения `PKGBUILD` пересоберите метаданные:

```bash
cd packaging/aur/i2pchat-bin
makepkg --printsrcinfo > .SRCINFO
```

## Bump версии

0. Актуальные хеши с GitHub Releases: **`./packaging/refresh-checksums.sh vX.Y.Z`**.

1. Обновите `pkgver` (и при необходимости `pkgrel`) в `-bin` PKGBUILD.
2. Обновите `sha256sums` для zip и `icon.png`.
3. Выполните `makepkg --printsrcinfo > .SRCINFO`, проверьте `makepkg -f` / `namcap`.
4. Для `-git` обычно достаточно `publish-to-aur.sh` — `pkgver()` пересчитается на стороне сборщика.

Скрипт [`../refresh-checksums.sh`](../refresh-checksums.sh) печатает контрольные суммы zip-файлов релиза.
