# winget

Пакет **`MetanoicArmor.I2PChat`** (GUI, C++ zip `*-winget-*`). Отдельный идентификатор **TUI** — только в [`../winget-tui/`](../winget-tui/): в [winget-pkgs](https://github.com/microsoft/winget-pkgs) это **два разных приложения**, поэтому нужны **два отдельных PR**.

**Путь в форке winget-pkgs (GUI):** `manifests/m/MetanoicArmor/I2PChat/<version>/` — в этом репозитории для **1.5.0**: [`manifests/m/MetanoicArmor/I2PChat/1.5.0/`](manifests/m/MetanoicArmor/I2PChat/1.5.0/).

Installer URL указывают на [MetanoicArmor/I2PChat-ng](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest).

## Публикация в community-репозитории

1. Форкните [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs).
2. Скопируйте **три** YAML этого пакета **только для `MetanoicArmor.I2PChat`** в ветку, например:

   `manifests/m/MetanoicArmor/I2PChat/1.5.0/`

3. Откройте **отдельный** pull request по [инструкции winget-pkgs](https://github.com/microsoft/winget-pkgs/blob/master/README.md).
4. Для **TUI** — второй PR из [`../winget-tui/`](../winget-tui/) (`manifests/m/MetanoicArmor/I2PChat/TUI/<version>/`).

Проверка локально:

```powershell
winget validate --manifest .\packaging\winget\manifests\m\MetanoicArmor\I2PChat\1.5.0
```

## Обновление на новый релиз

Скопируйте каталог под новую версию, обновите `PackageVersion`, `InstallerUrl` / `InstallerSha256` и при необходимости `ReleaseDate`. Либо [`../refresh-checksums.sh`](../refresh-checksums.sh).

## Microsoft: Installers Scan / i2pd

Пайплайн **winget-pkgs** распаковывает zip; **встроенный i2pd** даёт детекции вроде **Win64/Riskware.I2PD.A**.

**Решение:** `build-windows.ps1` дополнительно упаковывает C++ бинарники **без** embedded i2pd:

- `I2PChat-windows-x64-winget-v<версия>.zip`
- `I2PChat-windows-tui-x64-winget-v<версия>.zip`

Манифесты winget указывают на **эти** URL. Для bundled router используйте полный zip с [Releases](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest).

**Перед merge в winget-pkgs:**

1. Залить оба `*-winget-*.zip` на **тот же** GitHub Release, что и обычные Windows zip.
2. Подставить SHA256: вывод в конце `build-windows.ps1` или `./packaging/refresh-checksums.sh vX.Y.Z` (секции *winget*).
3. В `MetanoicArmor.I2PChat*.installer.yaml` заменить placeholder `0000…0000` на реальные хеши и запушить в ветку PR.

### Блок для PR в winget-pkgs (смена URL на `*-winget-*` и Installers Scan)

> We switched the manifest to the **`*-winget-*`** release assets built **without** the embedded i2pd binary so the installer passes Microsoft’s **Installers Scan** (`binaryValidation` / ESRP). The standard `I2PChat-windows-x64-v*.zip` (and the full TUI zip) on GitHub Releases still include the bundled i2pd router for users who want it. i2pd upstream: https://github.com/PurpleI2P/i2pd
