# Аудит безопасности I2PChat 1.5.2

**Дата аудита:** 2026-11-07
**Проаудированная версия:** 1.5.2 (см. `VERSION`; ветка `python`)
**Предыдущий аудит:** [docs/AUDIT_RU.md](AUDIT_RU.md) (1.3.3 → исправления в 1.4.0)
**Метод:** ручной анализ исходного кода по четырём направлениям параллельно (Python-ядро и десктоп-клиенты; C++ ядро `cpp/core`; Android Kotlin/JNI; CI и упаковка), затем проверка каждой находки на живом коде, точечные исправления и регрессионные тесты. Дополнительно: `pip-audit` по `uv.lock`, `actionlint` по workflow-файлам, контроль хешей bundled-бинарников `i2pd`.
**Область:** `i2pchat/`, `cpp/core/`, `android/`, `.github/workflows/`, `scripts/`, `packaging/`, `build-*.sh`. Vendored `i2pd` не подвергался обратной разработке — зафиксированы только его хеши.
**Ограничение по исправлениям:** в код внесены только изменения, **совместимые с wire-протоколом 1.5.x** (HS4, codec v4, groups wire v1/v3, BlindBox). Всё, что требует смены формата протокола или ключей, вынесено в раздел «Оставшиеся рекомендации»; остальные первоначально отложенные пункты реализованы вторым этапом (раздел «Второй этап»).

---

## Краткое резюме

Все исправления аудита 1.3.3 на месте (проверка регресса — в конце). Криптографическое ядро (Encrypt-then-MAC, anti-replay, HKDF-subkeys, TOFU Ed25519, эфемерный X25519) по-прежнему корректно и в Python, и в C++-порте; расхождений в формате кадров между реализациями не найдено.

Главная новая находка — в **C++ ядре (Android)**: логика доверия была мягче, чем в референсной Python-реализации. Сохранённому контакту при первом появлении ключ **пиннился автоматически**, а входящее соединение **не привязывалось к destination, который сообщил SAM-роутер**. В сумме это позволяло незнакомцу, знающему адрес вашего контакта, представиться им и навсегда занять его адрес своим ключом. Кроме того, Android **принимал входящие файлы без подтверждения** (до 2 ГиБ). Оба дефекта устранены; на Android добавлен диалог подтверждения файлов, зеркальный диалогу доверия.

В Python-клиентах найдены дефекты уровня Medium/Low: TUI падал (MarkupError) или интерпретировал Rich-разметку в ASCII-картинке от пира и пропускал терминальные управляющие последовательности; повторный заголовок `F` протекал дескриптором и частичным файлом; импорт истории из бэкапа не проверял сегмент пути; ненормированные размеры при распаковке бэкапа и загрузке страницы обновлений; кнопка «Открыть страницу загрузок» отдавала `.i2p` URL системному браузеру (утечка DNS). Зависимость `Pillow` 12.3.0 имела 39 опубликованных CVE — обновлена до 13.0.0.

В CI устранены: тег-объект вместо коммита в пине `setup-uv`, широкие `permissions` на уровне workflow, сохранение токена `checkout` в публикующих job'ах, прямая интерполяция `github.event.*` в shell, непинованная загрузка `uv`, shallow-клон для gitleaks, отсутствие контроля целостности bundled `i2pd`. Подпись release-APK публичным debug-ключом теперь требует явного opt-in.

Вторым этапом реализованы и девять пунктов, изначально отложенных как рекомендации (см. «Второй этап»): условная GPG-подпись `SHA256SUMS` и upload-keystore из секретов в release-workflow; sidecar `.dat.wrap` только при недоступном keyring; неблокирующие промпты доверия и файлов на Android; отдельный лимит и дедлайн для соединений до handshake (Python и C++); лимит соединений и дедлайн на команду у replica-серверов; санитизация названий групп; whitelist ключей при `restore_gui_settings`; пины docker-образа и `uv` для macOS arm64, allowlist gitleaks, ограниченный одним правилом.

**Исправлено в этом аудите:** 0 Critical, 5 High, 18 Medium, 14 Low (из них на втором этапе: 2 High, 4 Medium, 3 Low).
**Осталось как рекомендации:** 4 пункта — провизионирование секретов мейнтейнером (2), аутентификация title/epoch на уровне протокола, Gradle `verification-metadata.xml`.

---

## Исправленные уязвимости

### C++ ядро (`cpp/core`, используется Android)

#### [High] Автопиннинг ключа для сохранённого контакта + отсутствие привязки к SAM-destination
**Файлы:** `cpp/core/src/runtime/chat_service.cpp` (`link_config`, цикл `accept`), `cpp/core/src/session/trust_store.cpp` (`verify_or_pin`), `cpp/core/src/session/peer_session.cpp` (`identity_verifier`)

`link_config` передавал в `verify_or_pin` флаг `auto_first = saved_contact || trust_auto_accept_first_sighting`: если адрес уже был в контактах, первый увиденный ключ пиннился без вопроса. Цикл приёма создавал входящий линк через `link_config({}, Inbound)` и **отбрасывал** destination, который SAM сообщает в ответе `STREAM ACCEPT`; хук `identity_verifier` никогда не устанавливался. В результате любой, кто знал b32-адрес вашего контакта, мог открыть входящее соединение, в identity-префейсе назвать этот адрес и получить свой ключ запиннённым под ним. Python-реализация так не делает: там сохранённый контакт проходит обычный TOFU-промпт.

Также `verify_or_pin` при отсутствии обработчика промпта пиннил ключ **молча** («TOFU-сделка»), что на Android при отсутствующем листенере эквивалентно auto-accept.

**Исправление:**
- `auto_first` теперь равен только `config_.trust_auto_accept_first_sighting` (явный opt-in).
- Для входящих линков из `stream.peer_destination` разбирается base64-destination (`sam::Destination::from_public_base64(...).base32()`) и устанавливается `identity_verifier`, который требует совпадения адреса из префейса с адресом, сообщённым роутером; при нераспознанном destination соединение отклоняется.
- `TrustStore::verify_or_pin` при первом появлении без `auto_accept` и без обработчика промпта возвращает `Reject`.

#### [Medium] Приглашение в группу не сверялось с запиннённым ключом инвайтера
**Файл:** `cpp/core/src/runtime/chat_service.cpp` (`join_group_invite`)

Подпись инвайта проверялась только ключом, вложенным в сам инвайт. Python-реализация (`join_group_from_invite`) дополнительно требует совпадения `inviter_signing_pub` с пином `inviter_id`, если он есть; C++ — нет. Любой мог изготовить «инвайт от известного контакта».

**Исправление:** добавлена та же проверка через `trust_.pin_for(normalize_peer_address(inviter_id))` и `crypto::constant_time_equal`; при несовпадении — `std::runtime_error`.

#### [Medium] Повреждённый trust store молча затирался
**Файлы:** `cpp/core/src/session/trust_store.cpp` (`load`), `include/i2pchat/session/trust_store.hpp`, `chat_service.cpp` (`start`)

Ошибка парсинга `{profile}.trust.json` приводила к пустому набору пинов; следующий `save()` **перезаписывал** файл. Все контакты становились «первым появлением», а при включённом auto-accept — пиннились заново без участия пользователя.

**Исправление:** повреждённый файл переименовывается в `*.trust.json.corrupt-<unix-ts>`, выставляется флаг `degraded()`, `ChatService::start` эмитит ошибку пользователю, а `verify_or_pin` в degraded-режиме игнорирует auto-accept и всегда требует промпт.

#### [Medium] Android принимал входящие файлы без подтверждения
**Файлы:** `cpp/core/src/runtime/chat_service.cpp` (`callbacks.accept_file`), `android/app/src/main/cpp/native_engine.cpp`, `NativeEngine.kt`, `ChatBridge.kt`, `MainActivity.kt`

Если `events_.accept_file` не установлен, ядро отвечало `true`; Android-движок его не устанавливал. Любой подключившийся пир мог записывать файлы до `MAX_FILE_SIZE` (2 ГиБ) в хранилище приложения.

**Исправление:** добавлен коллбэк `accept_file` в JNI, экспорт `nativeFileRespond`, метод листенера `onFileOffer(peer, name, size)`, состояние `fileOffer` и `FileOfferDialog` в Compose. Первоначально ответ ожидался на io-потоке с таймаутом 5 минут; на втором этапе промпт переведён в неблокирующий режим (см. «Второй этап», п. 4).

#### [Medium] Блокирующие промпты на единственном io-потоке без таймаута
**Файл:** `android/app/src/main/cpp/native_engine.cpp`

`on_trust_prompt` блокировал io-поток `condition_variable::wait` до ответа пользователя — одно неотвеченное окно останавливало весь транспорт (keepalive, другие сессии, BlindBox). Флаг `stopping` не был атомарным.

**Исправление:** первым этапом — `wait_for(kPromptTimeout = 5 мин)` для обоих промптов, `std::atomic<bool> stopping`, пробуждение ожиданий в `nativeStop`. Вторым этапом ожидание на io-потоке убрано полностью: промпты возвращают `Deferred`, ответ UI обрабатывается отдельным вызовом (см. «Второй этап», п. 4).

#### [Medium] Gzip-бомба при импорте бэкапа
**Файл:** `cpp/core/src/storage/profile_backup.cpp` (`gunzip_bytes`)

Распаковка без ограничения размера выхода; весь payload хранится в памяти. Крафтовый бандл в несколько КиБ раздувался в гигабайты.

**Исправление:** `kMaxBackupPayloadSize = 512 МиБ`, превышение → `fail(...)`.

#### [Medium] `std::regex` на недоверенном HTML страницы обновлений
**Файл:** `cpp/core/src/updates/release_index.cpp` (`find_latest_for_prefix`)

`std::sregex_iterator` с шаблоном `I2PChat-[^\s"'<>]+\.zip` прогонялся по всему документу с eepsite. Реализация `std::regex` рекурсивна: длинная последовательность допустимых символов приводит к переполнению стека.

**Исправление:** кандидаты выделяются линейным сканированием с ограничением длины токена (256) и объёма документа (4 МиБ); `std::regex_match` применяется только к коротким именам.

#### [Low] Импорт истории не проверял сегмент пути
**Файл:** `cpp/core/src/storage/profile_backup.cpp` (`import_history_bundle`)

В отличие от `import_profile_bundle`, суффикс `history/<suffix>` не проходил `is_safe_path_segment`; `..`/разделители позволяли выйти из каталога профиля. (Python-аналог — ниже.)

**Исправление:** добавлена проверка `is_safe_path_segment(suffix)`.

#### [Low] Имя профиля `.`/`..` принималось валидатором
**Файлы:** `cpp/core/src/storage/profile_paths.cpp`, `profile_backup.cpp`, `chat_service.cpp`

Локальная regex-проверка в `profile_backup.cpp` принимала `..`. Имя профиля из manifest бэкапа попадало в путь каталога профиля.

**Исправление:** единый публичный `storage::is_valid_profile_name` (1..64 символов `[A-Za-z0-9._-]`, отклоняет `.`/`..`), используется в бэкапе и в конструкторе `ChatService` (покрывает JNI `nativeStart`).

#### [Low] `LineReader` без ограничения длины строки
**Файлы:** `cpp/core/src/net/line_reader.hpp/.cpp`

SAM-ответ или кадр без `\n` накапливался в буфере без предела.

**Исправление:** `kMaxLineLength = 64 КиБ`; при превышении буфер сбрасывается и чтение завершается.

#### [Low] JNI-экспорты без `try/catch`
**Файл:** `android/app/src/main/cpp/native_engine.cpp`

`nativeListProfiles`, `nativeDisconnectPeer`, `nativeSetPeerProfile`, `nativeRemovePeer`, `nativeForgetPin`, `nativeClearHistory`, `nativeDeleteGroup`, `nativeEncodeInvite`, `nativeGroupTopology`, `nativeSaveReplicas`, `nativeSetRetention`, разбор JSON в `nativeCreateGroup`/`nativeUpdateGroup` — исключение C++ на границе JNI = `std::terminate` всего процесса.

**Исправление:** все экспорты обёрнуты; ошибки отдаются как `onError` или безопасное значение по умолчанию; `parse_member_list` отбрасывает нестроковые элементы вместо броска.

### Python (`i2pchat/`)

#### [High] Уязвимая зависимость Pillow 12.3.0 (39 CVE)
**Файлы:** `pyproject.toml`, `uv.lock`

`pip-audit` по `uv.lock` сообщил о 39 уязвимостях в Pillow 12.3.0. PIL декодирует изображения, присланные пиром (`render_bw`, inline-images) — это прямой вектор от удалённого собеседника.

**Исправление:** `Pillow>=13.0.0`, `uv lock --upgrade-package pillow`.

#### [Medium] TUI: Rich-разметка и управляющие символы из текста пира
**Файл:** `i2pchat/gui/chat_python.py` (`handle_image_received`, `post`, `_render_chat_message`)

ASCII-картинка от пира передавалась в `Panel(art)` **без** `escape`: незакрытый тег вызывал `MarkupError` (падение рендера), а валидная разметка применялась. Во всех путях вывода C0/C1-символы (ESC, BEL, CSI) доходили до терминала: смена заголовка окна, очистка экрана, подмена строк лога.

**Исправление:** `strip_terminal_controls()` (удаляет `[\x00-\x08\x0b-\x1f\x7f-\x9f]`), `Panel(Text(...))` для картинки, зачистка в `post` и в тексте/метке отправителя сообщений. Тесты: `tests/test_tui_peer_text_rendering.py`.

#### [Medium] TUI принимал файлы без подтверждения
**Файл:** `i2pchat/gui/chat_python.py` (`handle_file_offer`)

Коллбэк возвращал `True` синхронно. Теперь — модальный `TuiTrustDecisionScreen` с ожиданием решения; имя файла санируется и экранируется. Тест: `tests/test_tui_file_offer.py`.

#### [Low] Утечка дескриптора при повторном заголовке `F`
**Файл:** `i2pchat/core/i2p_chat_core.py` (обработка `F`)

Новый заголовок `F` при открытом `incoming_file` перезаписывал ссылку без закрытия — дескриптор и частичный файл в downloads оставались. Пир мог исчерпать дескрипторы и заполнить каталог, не завершив ни одной передачи.

**Исправление:** предыдущий файл закрывается и удаляется, UI получает событие `received=-1`. Тест: `tests/test_protocol_framing_vnext.py::test_repeated_file_offer_closes_previous_partial_file` (проверено: падает на коде до исправления).

#### [Low] Импорт истории из бэкапа не проверял сегмент пути
**Файл:** `i2pchat/storage/profile_backup.py` (`import_history_bundle`)

**Исправление:** `_is_safe_path_segment(suffix)`. Тест: `test_history_import_rejects_unsafe_history_suffix` (бандл собирается напрямую `tarfile`, минуя экспортёр).

#### [Low] Неограниченные размеры при распаковке бэкапа и загрузке страницы обновлений
**Файлы:** `i2pchat/storage/profile_backup.py` (`_read_tar_payload`), `i2pchat/updates/release_index.py` (`fetch_releases_page`)

**Исправление:** `MAX_BUNDLE_MEMBER_BYTES = 256 МиБ`, `MAX_BUNDLE_TOTAL_BYTES = 512 МиБ`, чтение `extracted.read(member.size)`; `MAX_UPDATE_PAGE_BYTES = 4 МиБ`. Тесты: `test_bundle_member_size_is_bounded`, `test_oversized_page_is_rejected`.

#### [Low] «Открыть страницу загрузок» отдавал `.i2p` URL системному браузеру
**Файл:** `i2pchat/gui/main_qt.py` (диалог проверки обновлений)

Браузер по умолчанию резолвил `*.b32.i2p` через clearnet-DNS: запрос не работает и сообщает DNS-резолверу об использовании I2PChat.

**Исправление:** кнопка копирует адрес в буфер обмена и показывает подсказку про I2P HTTP-прокси.

#### [Low] Имя профиля `.`/`..` из manifest бэкапа
**Файл:** `i2pchat/core/i2p_chat_core.py` (`is_valid_profile_name`)

**Исправление:** явный отказ для `.`/`..`. Тест: `test_import_rejects_dot_dot_profile_name_from_manifest`.

#### [Low] Проверка пути сохранённого файла в GUI
**Файл:** `i2pchat/gui/main_qt.py`

Путь из записи истории `[file] ...` открывался, если файл существует. Теперь требуется `isfile` **и** нахождение внутри каталога загрузок (`_is_path_within_directory`).

### Android (`android/`)

#### [High] Абсолютные пути в истории и широкий `FileProvider`
**Файлы:** `ChatBridge.kt` (`resolveMediaPath`, `copyUri`), `MainActivity.kt` (`openReceivedFile`, `isReceivedMediaFile`), `res/xml/file_paths.xml`

Записи `[file] /abs/path` из истории (контролируются пиром через имя файла, а в C++ ядре — через полный путь) разрешались в любой файл; `file_paths.xml` экспортировал весь `files/`; при неудаче `FileProvider` был fallback на `Uri.fromFile`. Имя файла из `Uri.lastPathSegment` чужого content-provider'а попадало в путь без санации.

**Исправление:** `resolveMediaPath` принимает путь только если его родитель — `profiles/<p>/{downloads,images,...}`; `openReceivedFile` открывает только файлы, прошедшие `isReceivedMediaFile`, и только через `FileProvider`; `file_paths.xml` ограничен `i2pchat/profiles/`; `copyUri` санирует имя (`safeLocalFileName`) и пишет строго в `cacheDir/staging/`.

#### [Medium] Release-APK подписывался публичным debug-ключом по умолчанию
**Файлы:** `android/app/build.gradle.kts`, `build-android.sh`, `.github/workflows/{android,release-artifacts}.yml`, `android/README.md`

Любой может собрать APK, который установится «обновлением» поверх такого релиза. Настоящий upload-keystore в репозитории не провизионирован.

**Исправление:** debug-подпись release-сборки требует `I2PCHAT_ANDROID_ALLOW_DEBUG_SIGNING=1` (громкое предупреждение в Gradle и в скрипте); без ключей и без флага сборка падает с инструкцией. На втором этапе release-workflow берёт upload-keystore из секретов и включает debug-fallback только при их отсутствии (см. «Второй этап», п. 2).

#### [Medium] Staging-копии в `cacheDir` не удалялись
**Файл:** `ChatBridge.kt` (`sendFile`, `backup`)

Копии отправляемых файлов и бэкапов профиля (ключи, история) оставались в кэше приложения.

**Исправление:** `try/finally { delete() }` в обоих путях (движок дочитывает все чанки до возврата из `nativeSendFile`).

#### [Medium] Манифест: `usesCleartextTraffic="true"`, лишнее разрешение
**Файлы:** `AndroidManifest.xml`, `res/xml/network_security_config.xml`

**Исправление:** `networkSecurityConfig` разрешает cleartext только для `127.0.0.1`/`localhost` (SAM); удалено неиспользуемое `RECEIVE_BOOT_COMPLETED`.

### CI / упаковка

#### [Medium] `astral-sh/setup-uv` пинован на tag-объект движущегося тега `v6`
**Файл:** `.github/workflows/test-gate.yml`

`d0d8abe…` — annotated tag object для `refs/tags/v6`, не коммит; содержимое менялось бы с перемещением тега. Проверено `git ls-remote`: `v6^{}` = `v6.8.0` = `d0cc045d…`.

**Исправление:** пин заменён на коммит `d0cc045d04ccac9d8b7881df0226f9e82c39688e # v6.8.0`.

#### [Medium] Широкие `permissions` на уровне workflow
**Файлы:** `release-linux-pkgs.yml`, `apt-github-pages.yml`

`pages: write` и `id-token: write` (OIDC) были доступны всем job'ам, включая те, что скачивают и собирают сторонний контент.

**Исправление:** на уровне workflow — `contents: read`; `contents: write` только у job'ов, загружающих ассеты; `pages`/`id-token` — только у deploy-job.

#### [Medium] Токен `checkout` сохранялся в `.git/config` публикующих job'ов
**Файлы:** все release/build workflow (13 шагов `actions/checkout`)

**Исправление:** `persist-credentials: false` везде; ни один шаг не делает `git push`, `gh` использует `GH_TOKEN` из env.

#### [Medium] Прямая интерполяция `${{ github.event.* }}` в `run:`
**Файлы:** `build-linux-release-artifacts.yml`, `release-linux-pkgs.yml`, `apt-github-pages.yml`, `release-artifacts.yml`

Значения dispatch-inputs и `release.tag_name` вставлялись в shell-скрипт как текст.

**Исправление:** передача через `env:` и проверка regex `^[0-9]+\.[0-9]+\.[0-9]+$`; `source_ref` ограничен `[A-Za-z0-9._/-]+`.

#### [Medium] Непинованная загрузка `uv` в release-сборке
**Файл:** `release-artifacts.yml` (macOS x64, Windows)

`releases/latest/download/...` и `astral.sh/uv/install.ps1` без версии.

**Исправление:** `UV_VERSION=0.6.14` (как в test-gate), для macOS — сверка с опубликованным `.sha256`.

#### [Medium] Bundled `i2pd` без контроля целостности
**Файлы:** `vendor/i2pd/i2pd.sha256` (новый), `android/app/src/main/jniLibs/libi2pd.sha256` (новый), `scripts/verify_bundled_i2pd.sh` (новый), `.gitignore`, `build-android.sh`, workflow-файлы

Пять десктопных бинарников `i2pd` (force-added в `vendor/i2pd/`) и два `libi2pd.so` попадали в каждый артефакт без какой-либо фиксации хеша: подмена бинарника не была бы видна в diff.

**Исправление:** манифесты SHA-256 рядом с бинарниками; `verify_bundled_i2pd.sh` запускается в `test-gate`, перед каждой desktop-сборкой в release-workflow, перед Android-сборкой в CI и в `build-android.sh`. Зафиксированные хеши: arm64-v8a `388bca67…13ce5`, x86_64 `5bc8f610…2651b`.

#### [Medium] gitleaks сканировал shallow-клон
**Файл:** `secret-scan.yml`

`fetch-depth` по умолчанию = 1 → только HEAD. **Исправление:** `fetch-depth: 0`.

#### [Low] Подпись apt-репозитория в общем keyring раннера
**Файлы:** `packaging/apt/scripts/sign-release.sh`, `release-linux-pkgs.yml`, `apt-github-pages.yml`

Ключ импортировался в `~/.gnupg` раннера, выбирался «первый секретный ключ». **Исправление:** скрипт сам создаёт временный `GNUPGHOME` (0700, удаляется по EXIT) и импортирует туда `APT_REPO_GPG_PRIVATE_KEY`; из workflow убран прямой `gpg --import`.

#### [Low] Непинованные Actions `@vN`
**Файлы:** `android.yml`, `release-artifacts.yml`

`actions/cache`, `actions/setup-java`, `actions/upload-artifact`, `android-actions/setup-android` → пины на коммит с комментарием версии.

---

## Второй этап: реализованные рекомендации

Девять пунктов, первоначально вынесенных в рекомендации, реализованы без изменения wire-протокола 1.5.x. Там, где полное решение требует секретов или смены формата, в код внесена совместимая часть, а остаток перечислен в «Оставшихся рекомендациях».

#### 1. [High] GPG-подпись релизов
**Файлы:** `.github/workflows/release-artifacts.yml` (job `sign-checksums`), `scripts/sign_release_checksums.sh` (новый), `README.md`

Раньше все release-workflow выставляли `I2PCHAT_SKIP_GPG_SIGN=1`, а проверка шла с `--skip-gpg`: единственной гарантией целостности был HTTPS GitHub.

**Исправление:** новый job `sign-checksums` (после `verify-desktop-assets`, перед `publish-release`) скачивает все `SHA256SUMS.<platform>`, нормализует их в единый `SHA256SUMS` (не менее 6 строк, lower-case, без `*`) и, если задан секрет `RELEASE_GPG_PRIVATE_KEY` (опционально `RELEASE_GPG_PASSPHRASE`), подписывает его во временном `GNUPGHOME` (0700, `gpgconf --kill all` + удаление по EXIT), сверяет подпись `gpg --verify` и загружает `SHA256SUMS` + `SHA256SUMS.asc`. Затем `verify_github_release_assets.sh` запускается **без** `--skip-gpg`; без секрета — `::warning::` и `--skip-gpg`, как раньше. Скрипт проверен локально с подставным `gh` в обоих режимах.

#### 2. [High] Android upload-keystore из секретов
**Файлы:** `.github/workflows/release-artifacts.yml` (шаг `Configure Android release signing`), `README.md`

**Исправление:** если заданы `ANDROID_UPLOAD_KEYSTORE_B64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD`, keystore декодируется в `${RUNNER_TEMP}` (0600), валидируется `keytool -list`, передаётся в сборку через `I2PCHAT_ANDROID_KEYSTORE*`, а `I2PCHAT_ANDROID_ALLOW_DEBUG_SIGNING` остаётся пустым; keystore удаляется шагом `if: always()`. Без секретов — `::warning::` и прежний debug-fallback (sideload-only). Секреты попадают только в `env:` шагов сборки, не в `GITHUB_ENV`.

#### 3. [Medium] Sidecar `.dat.wrap` только при недоступном keyring
**Файлы:** `i2pchat/storage/profile_dat.py`, `cpp/core/src/storage/profile_dat.cpp`, `tests/test_profile_dat.py`

**Исправление:** `get_or_create_dat_wrap_key` сообщает, хранится ли ключ в системном keyring; `write_encrypted_profile_dat` пишет `.dat.wrap` только если ключа нет в keyring **или** sidecar уже существует (тогда он обновляется, чтобы перенос каталога не ломался). На системе с keyring рядом с `.dat` больше нет ключа к нему. Тесты: `test_sidecar_written_only_without_keyring`, `test_no_sidecar_when_keyring_holds_key`.

#### 4. [Medium] Неблокирующие промпты доверия и файлов на Android
**Файлы:** `cpp/core/include/i2pchat/session/handshake.hpp`, `src/session/{handshake,trust_store}.cpp`, `include/i2pchat/transfer/manager.hpp`, `src/transfer/manager.cpp`, `include/i2pchat/runtime/chat_service.hpp`, `src/runtime/chat_service.cpp`, `android/app/src/main/cpp/native_engine.cpp`, `NativeEngine.kt`, `ChatBridge.kt`

**Исправление:** `HandshakeMachine` синхронный, поэтому вместо переписывания его на очередь введено третье состояние ответа. `TrustDecision::Deferred` — handshake корректно отклоняется («awaits the user's decision»), io-поток не ждёт; JNI помнит пару (peer, key) в `trust_asked`, показывает диалог один раз, а `nativeTrustRespond(peer, keyHex, accept)` при согласии пиннит ключ через `verify_or_pin(..., true)` и повторно набирает пира, при отказе кэширует `Reject` до следующего ручного `connectPeer`. Для файлов — `OfferDecision::Deferred`: данные принимаются в `<path>.pending`, `respond_file_offer(peer, accept)` либо переименовывает файл в целевой, шлёт ACK и `on_file_received`, либо удаляет spool и шлёт `REJECT_FILE`. `kPromptTimeout`, мьютексы и `condition_variable` из JNI удалены; `nativeStop` очищает кэши ответов.

#### 5. [Medium] Отдельный лимит и дедлайн для соединений до handshake
**Файлы:** `i2pchat/core/live_peer_session.py`, `i2pchat/core/i2p_chat_core.py` (`accept_loop`, `_pre_handshake_deadline`), `tests/test_pre_handshake_limit.py` (новый); `cpp/core/include/i2pchat/runtime/chat_service.hpp`, `src/runtime/chat_service.cpp` (`accept_loop`)

Раньше в Python единственный «сторожевой» таймер переармировался каждым новым соединением, оставляя предыдущие молчащие потоки без дедлайна; лимит live-сессий расходовался соединениями без handshake. В C++ был 45-секундный таймер, но не было лимита.

**Исправление:** Python — `LivePeerSession.inbound`, лимит `max_pending_inbound_handshakes()` (env `I2PCHAT_MAX_PENDING_HANDSHAKES`, по умолчанию 3, 1..16), при превышении входящее соединение закрывается до чтения; на каждое входящее соединение — отдельная задача `_pre_handshake_deadline` (`PRE_HANDSHAKE_TIMEOUT = 30 с`), которая сбрасывает сессию, если handshake не завершён (сессии, ожидающие локальный TOFU-диалог, не трогаются). C++ — `ChatServiceConfig::max_pending_inbound_handshakes = 3`, `inbound_handshake_timeout = 30 с`, множество `pending_inbound_` с очисткой в `on_established`/`on_closed`.

#### 6. [Medium] Replica-серверы BlindBox: лимит соединений и дедлайн на команду
**Файлы:** `i2pchat/blindbox/blindbox_server_example.py`, `i2pchat/blindbox/blindbox_local_replica.py`, `cpp/core/include/i2pchat/blindbox/replica_server.hpp`, `cpp/core/src/blindbox/replica_server.cpp`

**Исправление:** Python-сервер — `BLINDBOX_MAX_CONNECTIONS` (256) и `BLINDBOX_COMMAND_TIMEOUT_SEC` (30) через обёртку `_bounded()` для TCP- и HTTP-обработчиков (события `connection_limit`, `command_timeout`); локальная реплика — `max_connections=64`, `command_timeout=30.0`, `wait_for` на чтении строк/байтов. C++ — те же переменные окружения, RAII-слот соединения и корутина `command_deadline`, закрывающая сокет по истечении таймера (переармируется на каждую команду).

#### 7. [Low] Названия групп: санитизация (совместимая часть)
**Файлы:** `i2pchat/groups/models.py`, `cpp/core/include/i2pchat/groups/models.hpp`, `cpp/core/src/groups/models.cpp`

Аутентификация title/epoch относительно наблюдателей требует изменения `GROUP_CONTROL` и остаётся рекомендацией. Внесена совместимая часть: `sanitize_group_title` (управляющие символы C0/C1 и `0x7f` → пробел, схлопывание пробелов, обрезка до 128 code points, пустое → `None`) применяется в конструкторе `GroupState` в обеих реализациях; переименования уже атрибутируются отправителю в `render_group_control_text`.

#### 8. [Low] `restore_gui_settings`: whitelist ключей
**Файлы:** `i2pchat/storage/profile_export.py`, `tests/test_profile_export.py`

**Исправление:** `RESTORABLE_GUI_SETTINGS` (тема, настройки ввода, уведомлений и истории) + проверка типов (`filter_restorable_gui_settings`: скаляры или плоский словарь скаляров, строки ≤ 256). `privacy_mode`/`lock_*`/`pins`, `releases_custom_*` и неизвестные ключи отбрасываются; если после фильтра ничего не осталось, `gui.json` не записывается. Тесты: `test_gui_settings_restore_is_whitelisted`, `test_gui_settings_restore_skips_file_when_nothing_allowed`.

#### 9. [Low] Цепочка поставки
**Файлы:** `.github/workflows/release-linux-pkgs.yml`, `.github/workflows/release-artifacts.yml`, `.gitleaks.toml`

**Исправление:** образ сборки RPM пинован по digest manifest-list (`fedora:42@sha256:99e203b8…97814`, команда обновления в комментарии); macOS arm64 вместо `brew install uv` получает тот же пинованный tarball `uv 0.6.14`, что и x64, причём для обеих архитектур SHA-256 теперь зафиксирован **в workflow** (`UV_SHA256_X64`/`UV_SHA256_ARM64`), а не берётся из `.sha256` рядом с архивом; `.gitleaks.toml` вместо file-wide allowlist расширяет allowlist только правила `generic-api-key` для фикстур (`tests/test_{clear_locked_peer,profile_dat}.py`, `cpp/testdata/vectors/*.json`, `cpp/tests/test_*.cpp`) — проверено на gitleaks 8.24.2 (CI) и 8.30.1: 0 находок на истории репозитория, при этом подставные `github-pat`/`stripe` в тех же путях по-прежнему детектируются. `pip-audit` уже выполняется на push/PR в `security-audit.yml` (`--require-hashes` по `uv export --frozen`), дублировать его в `test-gate` не нужно.

---

## Оставшиеся рекомендации

1. **[High, действие мейнтейнера] Провизионировать секреты** `RELEASE_GPG_PRIVATE_KEY` (+ `RELEASE_GPG_PASSPHRASE`) и опубликовать отпечаток ключа в `docs/`. До этого `sign-checksums` печатает предупреждение и релиз остаётся без `SHA256SUMS.asc`.
2. **[High, действие мейнтейнера] Провизионировать `ANDROID_UPLOAD_KEYSTORE_B64` / `..._PASSWORD` / `ANDROID_UPLOAD_KEY_ALIAS` / `ANDROID_UPLOAD_KEY_PASSWORD`.** До этого APK подписывается debug-ключом и остаётся sideload-only.
3. **[Low] Название группы и epoch в groups wire** не аутентифицируются относительно участников-наблюдателей (требует изменения формата `GROUP_CONTROL`, то есть новой версии groups wire). Санитизация из п. 7 снимает только представление, не подмену.
4. **[Low] Gradle `verification-metadata.xml`** для Android-зависимостей (большой генерируемый файл, требует отдельной валидации на всех конфигурациях сборки). Опционально — парольное шифрование `.dat` вместо/поверх keyring (меняет формат профиля).

---

## Проверка регресса относительно аудита 1.3.3

Все исправления предыдущего аудита присутствуют в 1.5.2 и подтверждены тестами из `test-gate.yml` (`tests.test_audit_remediation`, `tests.test_protocol_framing_vnext`, `tests.test_sam_input_validation` и др.): валидация имени профиля при импорте истории, запрет `blindbox/dat` в бэкапе, фильтр `QUIT` до handshake, отсутствие приватного ключа в логах/телеметрии, нормализация `.b32.i2p`, атомарные записи, `0600` на файлах профиля, lock на `.dat`, проверка MAC до расшифровки, anti-replay. Новых расхождений между Python и C++ в формате кадров HS4/codec v4 не обнаружено.

---

## Верификация

- `pytest` (offscreen, `tests/`, без заведомо зависающего `test_create_group_dialog_saves_checked_saved_peers`) после второго этапа — 874 passed, 3 failed; все 3 падения в `test_gui_group_smoke.py` воспроизводятся на чистом HEAD до аудита и не связаны с изменениями. Новые тесты: `tests/test_pre_handshake_limit.py` (4), `test_profile_dat.py` (+2), `test_profile_export.py` (+2).
- Android `:app:assembleStandardDebug` после второго этапа — собирается (это же компилирует `cpp/core`, включая `replica_server.cpp`, `transfer/manager.cpp`, `groups/models.cpp`, под NDK).
- `actionlint` по всем workflow — только два pre-existing предупреждения о метке `ubuntu-22.04-arm`.
- `gitleaks detect` (8.24.2 и 8.30.1) по истории репозитория с новым `.gitleaks.toml` — 0 находок; подставные токены в allowlist-путях детектируются.
- `scripts/sign_release_checksums.sh` — прогнан локально с подставным `gh` с ключом и без ключа; `gpg --verify` → «Good signature».
- Пины `uv 0.6.14`: `UV_SHA256_X64`/`UV_SHA256_ARM64` сверены с реально скачанными tarball'ами; digest `fedora:42` получен через `docker buildx imagetools inspect`.
- `scripts/verify_bundled_i2pd.sh` — 7/7 бинарников совпадают с манифестами.
- `pip-audit` по `uv.lock` после обновления Pillow — без известных уязвимостей в production-группе.
