# Flatpak (Flathub templates)

Шаблоны для **двух** приложений с разными app-id (GUI и TUI). Официальные бинарники — **C++** сборки с [GitHub Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest); перед отправкой в [Flathub](https://github.com/flathub/flathub) обновите **`sha256`** в модулях `archive` (см. [`../refresh-checksums.sh`](../refresh-checksums.sh)).

| Файл | Назначение |
|------|------------|
| [`net.i2pchat.I2PChat.yml`](net.i2pchat.I2PChat.yml) | Qt 6 GUI — архив с **AppImage** внутри (`I2PChat-linux-x86_64-v*.zip`). |
| [`net.i2pchat.I2PChat.TUI.yml`](net.i2pchat.I2PChat.TUI.yml) | FTXUI TUI — архив **`I2PChat-linux-x86_64-tui-v*.zip`**. |

## Локальная проверка

```bash
flatpak install flathub org.freedesktop.Platform//24.08 org.freedesktop.Sdk//24.08
flatpak-builder --user --install --force-clean build-dir packaging/flatpak/net.i2pchat.I2PChat.TUI.yml
flatpak run net.i2pchat.I2PChat.TUI
```

Замените версию runtime в манифесте при необходимости на актуальную ветку Freedesktop SDK.

## Публикация

1. Ознакомьтесь с [Flathub submission](https://github.com/flathub/flathub/wiki/App-Submission).
2. Обычно для каждого **app-id** создаётся отдельный репозиторий `flathub/net.i2pchat.I2PChat` и `flathub/net.i2pchat.I2PChat.TUI`.
3. Подставьте реальные `sha256` после выкладки релизных zip на GitHub.
