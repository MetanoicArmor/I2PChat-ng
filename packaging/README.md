# Распространение через менеджеры пакетов

Здесь лежат **шаблоны и инструкции** для Homebrew (cask), winget, AUR, **`.deb` (Debian/Ubuntu)** и **RPM (Fedora)**. Клиент — **C++** (Qt 6 GUI + FTXUI TUI); бинарники берутся с [GitHub Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest) (`MetanoicArmor/I2PChat-ng`). Android APK публикуется там же.

## Статус публикации (v1.5.0)

| Канал | Состояние |
|-------|-----------|
| **GitHub Releases** | Desktop zips, `.deb`, `.rpm`, **Android APK**, `SHA256SUMS*` — [latest](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest) |
| **winget** | Манифесты **`MetanoicArmor.I2PChat`** / **`MetanoicArmor.I2PChat.TUI`** в [`winget/`](winget/) и [`winget-tui/`](winget-tui/) (см. каталог `1.5.0/`). PR в [winget-pkgs](https://github.com/microsoft/winget-pkgs) — отдельно для GUI и TUI. |
| **Homebrew tap** | [MetanoicArmor/homebrew-i2pchat](https://github.com/MetanoicArmor/homebrew-i2pchat): `brew tap MetanoicArmor/i2pchat`. Casks в [`homebrew/Casks/`](homebrew/Casks/). |
| **AUR** | [i2pchat-bin](https://aur.archlinux.org/packages/i2pchat-bin), [i2pchat-tui-bin](https://aur.archlinux.org/packages/i2pchat-tui-bin). Шаблоны: [`aur/`](aur/). |
| **Flatpak / COPR** | Шаблоны: [flatpak/README.md](flatpak/README.md), [fedora/](fedora/). |
| **`.deb` / `.rpm` на Release** | Workflow [release-linux-pkgs.yml](../.github/workflows/release-linux-pkgs.yml). См. [debian/README.md](debian/README.md), [fedora/README.md](fedora/README.md). |
| **apt + GitHub Pages** | [metanoicarmor.github.io/I2PChat-ng](https://metanoicarmor.github.io/I2PChat-ng/). Старый URL `…/I2PChat/` — **404**. См. [`apt/README.md`](apt/README.md). |

| Платформа | Каталог | Действие мейнтейнера |
|-----------|---------|----------------------|
| macOS | [`homebrew/`](homebrew/) | Tap `homebrew-i2pchat`: cask **`i2pchat`** / **`i2pchat-tui`** |
| Windows | [`winget/`](winget/), [`winget-tui/`](winget-tui/) | Два PR в winget-pkgs |
| Arch | [`aur/`](aur/) | **`i2pchat-bin`**, **`i2pchat-tui-bin`** |
| Flatpak | [`flatpak/`](flatpak/) | Шаблоны для Flathub |
| Debian/Ubuntu | [`debian/`](debian/), [`apt/`](apt/) | `.deb` из zip + Pages apt |
| Fedora | [`fedora/`](fedora/) | RPM из zip / COPR |

## Версии и checksums

Файлы привязаны к последнему опубликованному релизу (`version` / `pkgver`). Корневой [`VERSION`](../VERSION) может опережать тег — после публикации `vX.Y.Z` обновите манифесты.

```bash
./packaging/refresh-checksums.sh          # latest release
./packaging/refresh-checksums.sh 1.5.0   # конкретный тег (без v)
```

Если на релизе вручную заменили только Linux zip:

```bash
./packaging/refresh-linux-sha256sums.sh v1.5.0
gh release upload v1.5.0 dist/SHA256SUMS --clobber --repo MetanoicArmor/I2PChat-ng
```

## См. также

- [**packaging/docker/README.md**](docker/README.md) — Docker-сборки Linux zip
- [**docs/INSTALL.md**](../docs/INSTALL.md) — установка с релизов
- Корневой README: **Quick Start**
- [`cpp/packaging/README.md`](../cpp/packaging/README.md) — как C++ бинарники попадают в те же артефакты
