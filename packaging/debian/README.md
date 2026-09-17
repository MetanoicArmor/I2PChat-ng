# Debian / Ubuntu (.deb)

Кратко: **I2PChat** — **C++** клиент: оконный **Qt 6 GUI** и отдельно **FTXUI TUI** (терминал). Готовые пакеты собираются из релизных Linux zip (AppImage / TUI onedir).

Попасть в официальные архивы Debian/Ubuntu без мейнтейнера в дистрибутиве нельзя. Варианты:

| Подход | Плюсы | Минусы |
|--------|--------|--------|
| **`.deb` с GitHub Release** | `sudo apt install ./пакет.deb` | Обновлять вручную с каждым релизом |
| **Свой apt (GitHub Pages)** | Дальше — обычный `apt install` | GPG и инфраструктура; здесь — [`packaging/apt/`](../apt/README.md) |
| **PPA** | Привычно для Ubuntu | Рецепты, очередь сборки |
| **Flatpak** | Один формат на много дистрибутивов | Не `apt`; отдельный манифест |

---

## Скачать `.deb` с релиза (основной способ)

В **Assets** на [последнем релизе](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest):

- **`i2pchat_<версия>_{amd64|arm64}.deb`** — GUI
- **`i2pchat-tui_<версия>_{amd64|arm64}.deb`** — TUI

Установка: `sudo apt install ./имя.deb`.

CI: [`.github/workflows/release-linux-pkgs.yml`](../../.github/workflows/release-linux-pkgs.yml).

---

## apt-зеркало (GitHub Pages)

Живое зеркало: [metanoicarmor.github.io/I2PChat-ng](https://metanoicarmor.github.io/I2PChat-ng/). Подключение — в [`packaging/apt/README.md`](../apt/README.md). Без зеркала используйте `.deb` с релиза выше.

---

## Сборка `.deb` локально (из официальных zip)

Нужны `bash`, `curl`, `unzip`, **`dpkg-deb`**. Запуск из **корня** клона (на macOS без Linux — `.deb` не собрать).

| Пакет | Скрипт | Заметки |
|--------|--------|---------|
| GUI | [`build-deb-from-appimage.sh`](build-deb-from-appimage.sh) | Zip с **AppImage** внутри или **portable** onedir. amd64 / arm64: `I2PCHAT_DEB_ARCH=arm64 …` |
| Терминал (TUI) | [`build-tui-deb-from-release-zip.sh`](build-tui-deb-from-release-zip.sh) | Те же архитектуры |

Версия — аргумент или первая строка **`VERSION`** в корне репо.

**glibc** у бинарников — как у хоста сборки zip; релизные Linux zip собирают на Ubuntu 22.04 в CI ([`build-linux-release-artifacts.yml`](../../.github/workflows/build-linux-release-artifacts.yml)).

---

## Исторический source package в корне `debian/`

В корне репозитория может оставаться дерево **`debian/`** от прежней Python-линии (`python3-i2pchat`). Оно **не** является способом поставки текущего C++ клиента. Актуальные пользовательские пакеты — **`.deb` с Releases** и apt-зеркало выше.

Для сборки C++ из исходников см. [`cpp/README.md`](../../cpp/README.md) и [`cpp/packaging/README.md`](../../cpp/packaging/README.md).

---

## См. также

- [`packaging/apt/README.md`](../apt/README.md) — Pages apt
- [`packaging/fedora/README.md`](../fedora/README.md) — RPM
- Корневой [README](../../README.md) — Quick Start
