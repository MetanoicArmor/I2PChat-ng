# Подготовка PR в netblue30/firejail

Профили уже в формате upstream (`etc/templates/profile.template`). Ниже — дополнительные правки, которые обычно требуют ревьюеры.

## 1. Скопировать профили

В fork firejail:

```text
etc/profile-a-l/i2pchat.profile
etc/profile-a-l/i2pchat-tui.profile
etc/profile-a-l/I2PChat.profile
etc/profile-a-l/I2PChat.AppImage.profile
etc/profile-a-l/I2PChat-tui.profile
```

(Файлы из этого каталога `packaging/firejail/`.)

## 2. `etc/inc/disable-programs.inc`

Добавить (список алфавитный):

```
blacklist ${HOME}/.config/I2PChat
```

рядом с другими `blacklist ${HOME}/.config/…`, и:

```
blacklist ${HOME}/.i2pchat
```

рядом с `blacklist ${HOME}/.i2p`.

Так другие sandboxed-приложения не читают данные I2PChat; сами профили делают `noblacklist` для этих путей.

## 3. `src/firecfg/firecfg.config`

Добавить (алфавитно):

```
I2PChat
I2PChat-tui
i2pchat
i2pchat-tui
```

`I2PChat.AppImage` в firecfg обычно не нужен: AppImage запускают с `--appimage`, профиль подхватывается по basename или `--profile=`.

## 4. Описание для PR

Suggested title: `Add profiles for I2PChat (GUI) and I2PChat-tui`

Body (EN):

```markdown
## Summary
- Add Firejail profiles for I2PChat Qt GUI (`i2pchat` / AppImage) and terminal UI (`i2pchat-tui`).
- Whitelist `~/.i2pchat` and `~/.config/I2PChat`; allow network for I2P/SAM/BlindBox; D-Bus secrets (+ notifications/tray for GUI).
- Blacklist the same data dirs in `disable-programs.inc`; register binaries in `firecfg.config`.

## Test plan
- [ ] `firejail i2pchat` / `firejail --appimage I2PChat.AppImage` — start, load profile, chat, file/image from Downloads, BlindBox HTTPS, tray/notify
- [ ] `firejail i2pchat-tui` — start via shell launcher, SAM or bundled i2pd, keyring or wrap file
- [ ] System i2pd on `127.0.0.1:7656` and bundled router under `~/.i2pchat/i2pd`
```

## 5. Ссылки

- Project: https://github.com/MetanoicArmor/I2PChat-ng  
- Profile requests issue (если сначала обсуждение): https://github.com/netblue30/firejail/issues/1139  
- Template rules: `etc/templates/profile.template` in firejail
