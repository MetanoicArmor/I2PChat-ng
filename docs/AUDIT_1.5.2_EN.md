# I2PChat 1.5.2 Security Audit

**Audit date:** 2026-11-07
**Version audited:** 1.5.2 (see `VERSION`; branch `python`)
**Previous audit:** [docs/AUDIT_EN.md](AUDIT_EN.md) (1.3.3 → remediated in 1.4.0)
**Method:** manual source review along four parallel tracks (Python core and desktop clients; C++ core `cpp/core`; Android Kotlin/JNI; CI and packaging), followed by verification of every finding against the live code, targeted remediation and regression tests. Additionally: `pip-audit` against `uv.lock`, `actionlint` over the workflows, and hash pinning of the bundled `i2pd` binaries.
**Scope:** `i2pchat/`, `cpp/core/`, `android/`, `.github/workflows/`, `scripts/`, `packaging/`, `build-*.sh`. The vendored `i2pd` was not reverse-engineered; only its hashes were recorded.
**Remediation constraint:** only changes **compatible with the 1.5.x wire protocol** (HS4, codec v4, groups wire v1/v3, BlindBox) were applied. Anything that would change the protocol or key formats is listed under "Remaining recommendations"; the other items that were initially deferred were implemented in a second pass (section "Second pass").

---

## Executive summary

Every fix from the 1.3.3 audit is still in place (see the regression check at the end). The cryptographic core (Encrypt-then-MAC, anti-replay, HKDF subkeys, Ed25519 TOFU, ephemeral X25519) remains sound in both the Python and the C++ implementation; no frame-format divergence between the two was found.

The most serious new finding is in the **C++ core (Android)**: its trust logic was weaker than the reference Python implementation. A saved contact's first-seen key was **pinned automatically**, and an inbound connection was **not bound to the destination reported by the SAM router**. Together, anyone who knew a contact's address could introduce themselves as that contact and permanently occupy the address with their own key. In addition, Android **accepted incoming files without confirmation** (up to 2 GiB). Both are fixed; Android gained a file-offer dialog mirroring the trust dialog.

The Python clients had Medium/Low defects: the TUI crashed (MarkupError) or applied Rich markup embedded in a peer's ASCII image and passed terminal control sequences through; a repeated `F` header leaked a descriptor and a partial file; history import from a backup did not validate the path segment; unbounded sizes when inflating a backup and fetching the update page; the "Open downloads page" button handed a `.i2p` URL to the system browser (DNS leak). The `Pillow` 12.3.0 dependency carried 39 published CVEs and was bumped to 13.0.0.

In CI: a tag object instead of a commit in the `setup-uv` pin, broad workflow-level `permissions`, persisted `checkout` tokens in publishing jobs, direct `github.event.*` interpolation into shell, an unpinned `uv` download, a shallow clone for gitleaks, and no integrity control over the bundled `i2pd`. Signing release APKs with the public debug key is now an explicit opt-in.

A second pass then implemented the nine items that had initially been deferred as recommendations (see "Second pass"): conditional GPG signing of `SHA256SUMS` and an upload keystore from secrets in the release workflow; the `.dat.wrap` sidecar only when no keyring is available; non-blocking trust and file prompts on Android; a separate cap and deadline for pre-handshake connections (Python and C++); connection limits and per-command deadlines on the replica servers; group-title sanitisation; a key whitelist for `restore_gui_settings`; digest/tarball pins for the docker image and `uv` on macOS arm64, and a gitleaks allowlist narrowed to a single rule.

**Fixed in this audit:** 0 Critical, 5 High, 18 Medium, 14 Low (second pass: 2 High, 4 Medium, 3 Low).
**Remaining recommendations:** 4 — provisioning secrets (maintainer action, 2 items), protocol-level title/epoch authentication, Gradle `verification-metadata.xml`.

---

## Vulnerabilities fixed

### C++ core (`cpp/core`, used by Android)

#### [High] Auto-pinning for saved contacts + no SAM destination binding
**Files:** `cpp/core/src/runtime/chat_service.cpp` (`link_config`, accept loop), `cpp/core/src/session/trust_store.cpp` (`verify_or_pin`), `cpp/core/src/session/peer_session.cpp` (`identity_verifier`)

`link_config` passed `auto_first = saved_contact || trust_auto_accept_first_sighting` to `verify_or_pin`: if the address was already in the contact list, the first key seen was pinned without a prompt. The accept loop created inbound links via `link_config({}, Inbound)` and **discarded** the destination SAM reports in the `STREAM ACCEPT` reply; the `identity_verifier` hook was never installed. Consequently anyone who knew a contact's b32 address could open an inbound connection, name that address in the identity preface and have their key pinned under it. The Python implementation does not do this: a saved contact still goes through the ordinary TOFU prompt.

`verify_or_pin` also pinned **silently** when no prompt handler was installed ("the TOFU bargain"), which on Android without a listener is equivalent to auto-accept.

**Fix:**
- `auto_first` is now only `config_.trust_auto_accept_first_sighting` (explicit opt-in).
- For inbound links the base64 destination from `stream.peer_destination` is parsed (`sam::Destination::from_public_base64(...).base32()`) and an `identity_verifier` is installed that requires the preface address to equal the router-reported one; an unparseable destination rejects the connection.
- `TrustStore::verify_or_pin` returns `Reject` on a first sighting when neither `auto_accept` nor a prompt handler is available.

#### [Medium] Group invite not checked against the inviter's pinned key
**File:** `cpp/core/src/runtime/chat_service.cpp` (`join_group_invite`)

The invite signature was verified only against the key embedded in the invite. The Python implementation (`join_group_from_invite`) additionally requires `inviter_signing_pub` to match the pin for `inviter_id` when one exists; C++ did not. Anyone could mint an "invite from a known contact".

**Fix:** the same check via `trust_.pin_for(normalize_peer_address(inviter_id))` and `crypto::constant_time_equal`; mismatch throws `std::runtime_error`.

#### [Medium] Corrupt trust store silently wiped
**Files:** `cpp/core/src/session/trust_store.cpp` (`load`), `include/i2pchat/session/trust_store.hpp`, `chat_service.cpp` (`start`)

A parse error in `{profile}.trust.json` yielded an empty pin set; the next `save()` **overwrote** the file. Every contact became a "first sighting", and with auto-accept on, was re-pinned without the user.

**Fix:** the damaged file is renamed to `*.trust.json.corrupt-<unix-ts>`, a `degraded()` flag is set, `ChatService::start` emits an error to the user, and `verify_or_pin` ignores auto-accept and always prompts while degraded.

#### [Medium] Android accepted incoming files without confirmation
**Files:** `cpp/core/src/runtime/chat_service.cpp` (`callbacks.accept_file`), `android/app/src/main/cpp/native_engine.cpp`, `NativeEngine.kt`, `ChatBridge.kt`, `MainActivity.kt`

With `events_.accept_file` unset the core answered `true`; the Android engine never set it. Any connected peer could write files up to `MAX_FILE_SIZE` (2 GiB) into app storage.

**Fix:** an `accept_file` callback in JNI, the `nativeFileRespond` export, the listener method `onFileOffer(peer, name, size)`, `fileOffer` state and a Compose `FileOfferDialog`. Initially the answer was awaited on the io thread with a 5-minute timeout; the second pass made the prompt non-blocking (see "Second pass", item 4).

#### [Medium] Blocking prompts on the single io thread without a timeout
**File:** `android/app/src/main/cpp/native_engine.cpp`

`on_trust_prompt` blocked the io thread in `condition_variable::wait` until the user answered — one unanswered dialog stalled the whole transport (keepalives, other sessions, BlindBox). The `stopping` flag was not atomic.

**Fix:** first pass — `wait_for(kPromptTimeout = 5 min)` for both prompts, `std::atomic<bool> stopping`, both waits woken in `nativeStop`. Second pass — the io-thread wait is gone entirely: prompts return `Deferred` and the UI's answer is applied by a separate call (see "Second pass", item 4).

#### [Medium] Gzip bomb on backup import
**File:** `cpp/core/src/storage/profile_backup.cpp` (`gunzip_bytes`)

Inflation had no output bound and the whole payload is held in memory; a bundle of a few KiB could expand into gigabytes.

**Fix:** `kMaxBackupPayloadSize = 512 MiB`; exceeding it fails the import.

#### [Medium] `std::regex` over untrusted update-page HTML
**File:** `cpp/core/src/updates/release_index.cpp` (`find_latest_for_prefix`)

A `std::sregex_iterator` with `I2PChat-[^\s"'<>]+\.zip` ran over the entire document from an eepsite. `std::regex` implementations are recursive; a long run of allowed characters overflows the stack.

**Fix:** candidates are extracted by a linear scan with a token length cap (256) and a document cap (4 MiB); `std::regex_match` runs only on those short names.

#### [Low] History import did not validate the path segment
**File:** `cpp/core/src/storage/profile_backup.cpp` (`import_history_bundle`)

Unlike `import_profile_bundle`, the `history/<suffix>` suffix was not passed through `is_safe_path_segment`; `..` or separators could escape the profile directory. (Python counterpart below.)

**Fix:** `is_safe_path_segment(suffix)` added.

#### [Low] Profile names `.`/`..` accepted by the validator
**Files:** `cpp/core/src/storage/profile_paths.cpp`, `profile_backup.cpp`, `chat_service.cpp`

The local regex check in `profile_backup.cpp` accepted `..`. The profile name from a backup manifest fed into the profile directory path.

**Fix:** a single public `storage::is_valid_profile_name` (1..64 chars of `[A-Za-z0-9._-]`, rejects `.`/`..`), used by backups and by the `ChatService` constructor (covers JNI `nativeStart`).

#### [Low] `LineReader` without a line length bound
**Files:** `cpp/core/src/net/line_reader.hpp/.cpp`

A SAM reply or frame without `\n` accumulated in the buffer without limit.

**Fix:** `kMaxLineLength = 64 KiB`; beyond it the buffer is dropped and the read ends.

#### [Low] JNI exports without `try/catch`
**File:** `android/app/src/main/cpp/native_engine.cpp`

`nativeListProfiles`, `nativeDisconnectPeer`, `nativeSetPeerProfile`, `nativeRemovePeer`, `nativeForgetPin`, `nativeClearHistory`, `nativeDeleteGroup`, `nativeEncodeInvite`, `nativeGroupTopology`, `nativeSaveReplicas`, `nativeSetRetention`, and the JSON parsing in `nativeCreateGroup`/`nativeUpdateGroup` — a C++ exception at the JNI boundary is `std::terminate` for the whole process.

**Fix:** all exports are wrapped; errors surface as `onError` or a safe default; `parse_member_list` drops non-string elements instead of throwing.

### Python (`i2pchat/`)

#### [High] Vulnerable dependency Pillow 12.3.0 (39 CVEs)
**Files:** `pyproject.toml`, `uv.lock`

`pip-audit` over `uv.lock` reported 39 vulnerabilities in Pillow 12.3.0. PIL decodes images sent by peers (`render_bw`, inline images) — a direct remote vector.

**Fix:** `Pillow>=13.0.0`, `uv lock --upgrade-package pillow`.

#### [Medium] TUI: Rich markup and control characters from peer text
**File:** `i2pchat/gui/chat_python.py` (`handle_image_received`, `post`, `_render_chat_message`)

A peer's ASCII image went into `Panel(art)` **without** `escape`: an unbalanced tag raised `MarkupError` (render crash) and valid markup was applied. On every output path C0/C1 characters (ESC, BEL, CSI) reached the terminal: window retitling, screen clearing, spoofed log lines.

**Fix:** `strip_terminal_controls()` (removes `[\x00-\x08\x0b-\x1f\x7f-\x9f]`), `Panel(Text(...))` for the image, stripping in `post` and in message text / sender label. Tests: `tests/test_tui_peer_text_rendering.py`.

#### [Medium] TUI accepted files without confirmation
**File:** `i2pchat/gui/chat_python.py` (`handle_file_offer`)

The callback returned `True` synchronously. It now shows a modal `TuiTrustDecisionScreen` and awaits the decision; the filename is sanitized and escaped. Test: `tests/test_tui_file_offer.py`.

#### [Low] Descriptor leak on a repeated `F` header
**File:** `i2pchat/core/i2p_chat_core.py` (`F` handling)

A new `F` header while `incoming_file` was open overwrote the reference without closing it — the descriptor and the partial file in downloads remained. A peer could exhaust descriptors and fill the directory without ever completing a transfer.

**Fix:** the previous file is closed and removed and the UI receives a `received=-1` event. Test: `tests/test_protocol_framing_vnext.py::test_repeated_file_offer_closes_previous_partial_file` (verified to fail on the pre-fix code).

#### [Low] History import from a backup did not validate the path segment
**File:** `i2pchat/storage/profile_backup.py` (`import_history_bundle`)

**Fix:** `_is_safe_path_segment(suffix)`. Test: `test_history_import_rejects_unsafe_history_suffix` (the bundle is built with `tarfile` directly, bypassing the exporter).

#### [Low] Unbounded sizes when inflating a backup and fetching the update page
**Files:** `i2pchat/storage/profile_backup.py` (`_read_tar_payload`), `i2pchat/updates/release_index.py` (`fetch_releases_page`)

**Fix:** `MAX_BUNDLE_MEMBER_BYTES = 256 MiB`, `MAX_BUNDLE_TOTAL_BYTES = 512 MiB`, reads via `extracted.read(member.size)`; `MAX_UPDATE_PAGE_BYTES = 4 MiB`. Tests: `test_bundle_member_size_is_bounded`, `test_oversized_page_is_rejected`.

#### [Low] "Open downloads page" handed a `.i2p` URL to the system browser
**File:** `i2pchat/gui/main_qt.py` (update-check dialog)

The default browser would resolve `*.b32.i2p` through clearnet DNS: the request fails and tells the resolver that this machine uses I2PChat.

**Fix:** the button copies the address to the clipboard and shows a hint about the I2P HTTP proxy.

#### [Low] Profile names `.`/`..` from a backup manifest
**File:** `i2pchat/core/i2p_chat_core.py` (`is_valid_profile_name`)

**Fix:** explicit rejection of `.`/`..`. Test: `test_import_rejects_dot_dot_profile_name_from_manifest`.

#### [Low] Saved-file path check in the GUI
**File:** `i2pchat/gui/main_qt.py`

A `[file] ...` path from a history entry was opened if the file existed. It now also has to be `isfile` **and** inside the downloads directory (`_is_path_within_directory`).

### Android (`android/`)

#### [High] Absolute paths in history and an over-broad `FileProvider`
**Files:** `ChatBridge.kt` (`resolveMediaPath`, `copyUri`), `MainActivity.kt` (`openReceivedFile`, `isReceivedMediaFile`), `res/xml/file_paths.xml`

`[file] /abs/path` history entries (peer-influenced through the filename, and in the C++ core through the full path) resolved to any file; `file_paths.xml` exported all of `files/`; when `FileProvider` failed there was a `Uri.fromFile` fallback. The filename from another app's `Uri.lastPathSegment` went into a path unsanitized.

**Fix:** `resolveMediaPath` accepts a path only if its parent is `profiles/<p>/{downloads,images,...}`; `openReceivedFile` opens only files passing `isReceivedMediaFile`, and only through `FileProvider`; `file_paths.xml` is limited to `i2pchat/profiles/`; `copyUri` sanitizes the name (`safeLocalFileName`) and writes strictly into `cacheDir/staging/`.

#### [Medium] Release APKs signed with the public debug key by default
**Files:** `android/app/build.gradle.kts`, `build-android.sh`, `.github/workflows/{android,release-artifacts}.yml`, `android/README.md`

Anyone can build an APK that installs as an "update" over such a release. No real upload keystore is provisioned in the repository.

**Fix:** debug-signing a release build requires `I2PCHAT_ANDROID_ALLOW_DEBUG_SIGNING=1` (loud warning in Gradle and in the script); without keys and without the flag the build fails with instructions. In the second pass the release workflow takes the upload keystore from secrets and only enables the debug fallback when they are absent (see "Second pass", item 2).

#### [Medium] Staged copies in `cacheDir` never deleted
**File:** `ChatBridge.kt` (`sendFile`, `backup`)

Copies of outgoing files and of profile backups (keys, history) lingered in the app cache.

**Fix:** `try/finally { delete() }` on both paths (the engine streams every chunk before `nativeSendFile` returns).

#### [Medium] Manifest: `usesCleartextTraffic="true"`, unneeded permission
**Files:** `AndroidManifest.xml`, `res/xml/network_security_config.xml`

**Fix:** `networkSecurityConfig` permits cleartext only for `127.0.0.1`/`localhost` (SAM); the unused `RECEIVE_BOOT_COMPLETED` was removed.

### CI / packaging

#### [Medium] `astral-sh/setup-uv` pinned to the tag object of the moving `v6` tag
**File:** `.github/workflows/test-gate.yml`

`d0d8abe…` is the annotated tag object for `refs/tags/v6`, not a commit; its content would change as the tag moves. Verified with `git ls-remote`: `v6^{}` = `v6.8.0` = `d0cc045d…`.

**Fix:** pin replaced with commit `d0cc045d04ccac9d8b7881df0226f9e82c39688e # v6.8.0`.

#### [Medium] Broad workflow-level `permissions`
**Files:** `release-linux-pkgs.yml`, `apt-github-pages.yml`

`pages: write` and `id-token: write` (OIDC) were available to every job, including those downloading and building third-party content.

**Fix:** workflow level is `contents: read`; `contents: write` only on jobs uploading assets; `pages`/`id-token` only on the deploy job.

#### [Medium] `checkout` token persisted in `.git/config` of publishing jobs
**Files:** all release/build workflows (13 `actions/checkout` steps)

**Fix:** `persist-credentials: false` everywhere; no step does `git push`, `gh` uses `GH_TOKEN` from env.

#### [Medium] Direct `${{ github.event.* }}` interpolation into `run:`
**Files:** `build-linux-release-artifacts.yml`, `release-linux-pkgs.yml`, `apt-github-pages.yml`, `release-artifacts.yml`

Dispatch inputs and `release.tag_name` were pasted into shell scripts as text.

**Fix:** passed through `env:` and validated against `^[0-9]+\.[0-9]+\.[0-9]+$`; `source_ref` constrained to `[A-Za-z0-9._/-]+`.

#### [Medium] Unpinned `uv` download in the release build
**File:** `release-artifacts.yml` (macOS x64, Windows)

`releases/latest/download/...` and `astral.sh/uv/install.ps1` without a version.

**Fix:** `UV_VERSION=0.6.14` (same as test-gate); on macOS the tarball is checked against the published `.sha256`.

#### [Medium] Bundled `i2pd` without integrity control
**Files:** `vendor/i2pd/i2pd.sha256` (new), `android/app/src/main/jniLibs/libi2pd.sha256` (new), `scripts/verify_bundled_i2pd.sh` (new), `.gitignore`, `build-android.sh`, workflows

Five desktop `i2pd` binaries (force-added under `vendor/i2pd/`) and two `libi2pd.so` went into every artifact with no recorded hash: a swapped binary would not have shown in a diff.

**Fix:** SHA-256 manifests next to the binaries; `verify_bundled_i2pd.sh` runs in `test-gate`, before every desktop build in the release workflows, before the Android build in CI, and in `build-android.sh`. Recorded hashes: arm64-v8a `388bca67…13ce5`, x86_64 `5bc8f610…2651b`.

#### [Medium] gitleaks scanned a shallow clone
**File:** `secret-scan.yml`

Default `fetch-depth` = 1 → only HEAD. **Fix:** `fetch-depth: 0`.

#### [Low] apt repository signed from the runner's shared keyring
**Files:** `packaging/apt/scripts/sign-release.sh`, `release-linux-pkgs.yml`, `apt-github-pages.yml`

The key was imported into the runner's `~/.gnupg` and "the first secret key" was picked. **Fix:** the script creates a temporary `GNUPGHOME` (0700, removed on EXIT) and imports `APT_REPO_GPG_PRIVATE_KEY` into it; the direct `gpg --import` was removed from the workflows.

#### [Low] Unpinned `@vN` actions
**Files:** `android.yml`, `release-artifacts.yml`

`actions/cache`, `actions/setup-java`, `actions/upload-artifact`, `android-actions/setup-android` → commit pins with version comments.

---

## Second pass: recommendations implemented

The nine items originally listed as recommendations were implemented without changing the 1.5.x wire protocol. Where the complete fix needs secrets or a format change, the compatible part is in code and the remainder is listed under "Remaining recommendations".

#### 1. [High] GPG signing of releases
**Files:** `.github/workflows/release-artifacts.yml` (job `sign-checksums`), `scripts/sign_release_checksums.sh` (new), `README.md`

Previously every release workflow set `I2PCHAT_SKIP_GPG_SIGN=1` and verified with `--skip-gpg`; GitHub's HTTPS was the only integrity guarantee.

**Fix:** a new `sign-checksums` job (after `verify-desktop-assets`, before `publish-release`) downloads every `SHA256SUMS.<platform>`, normalises them into one `SHA256SUMS` (at least 6 lines, lower-case, no `*`) and, when the `RELEASE_GPG_PRIVATE_KEY` secret is set (optionally `RELEASE_GPG_PASSPHRASE`), signs it inside a temporary `GNUPGHOME` (0700, `gpgconf --kill all` + removal on EXIT), checks the signature with `gpg --verify` and uploads `SHA256SUMS` + `SHA256SUMS.asc`. `verify_github_release_assets.sh` then runs **without** `--skip-gpg`; with no secret it prints a `::warning::` and keeps `--skip-gpg` as before. The script was exercised locally against a fake `gh` in both modes.

#### 2. [High] Android upload keystore from secrets
**Files:** `.github/workflows/release-artifacts.yml` (step `Configure Android release signing`), `README.md`

**Fix:** when `ANDROID_UPLOAD_KEYSTORE_B64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS` and `ANDROID_UPLOAD_KEY_PASSWORD` are set, the keystore is decoded into `${RUNNER_TEMP}` (0600), validated with `keytool -list`, handed to the build via `I2PCHAT_ANDROID_KEYSTORE*`, and `I2PCHAT_ANDROID_ALLOW_DEBUG_SIGNING` stays empty; an `if: always()` step deletes the keystore afterwards. Without the secrets: `::warning::` and the previous debug fallback (sideload-only). Secrets only reach the build steps' `env:`, never `GITHUB_ENV`.

#### 3. [Medium] `.dat.wrap` sidecar only when no keyring is available
**Files:** `i2pchat/storage/profile_dat.py`, `cpp/core/src/storage/profile_dat.cpp`, `tests/test_profile_dat.py`

**Fix:** `get_or_create_dat_wrap_key` reports whether the key lives in the system keyring; `write_encrypted_profile_dat` writes `.dat.wrap` only when the key is not in the keyring **or** a sidecar already exists (then it is refreshed so that moving the directory keeps working). On a system with a keyring the key to `.dat` is no longer stored next to it. Tests: `test_sidecar_written_only_without_keyring`, `test_no_sidecar_when_keyring_holds_key`.

#### 4. [Medium] Non-blocking trust and file prompts on Android
**Files:** `cpp/core/include/i2pchat/session/handshake.hpp`, `src/session/{handshake,trust_store}.cpp`, `include/i2pchat/transfer/manager.hpp`, `src/transfer/manager.cpp`, `include/i2pchat/runtime/chat_service.hpp`, `src/runtime/chat_service.cpp`, `android/app/src/main/cpp/native_engine.cpp`, `NativeEngine.kt`, `ChatBridge.kt`

**Fix:** `HandshakeMachine` is synchronous, so instead of rewriting it around a queue a third answer state was introduced. `TrustDecision::Deferred` cleanly rejects the handshake ("awaits the user's decision") without the io thread waiting; JNI remembers the (peer, key) pair in `trust_asked`, shows the dialog once, and `nativeTrustRespond(peer, keyHex, accept)` pins the key via `verify_or_pin(..., true)` and re-dials the peer on accept, or caches `Reject` until the next manual `connectPeer`. For files, `OfferDecision::Deferred` spools the data into `<path>.pending`; `respond_file_offer(peer, accept)` either renames it to the target, sends the ACK and `on_file_received`, or removes the spool and sends `REJECT_FILE`. `kPromptTimeout`, the mutexes and the `condition_variable`s were removed from JNI; `nativeStop` clears the answer caches.

#### 5. [Medium] Separate cap and deadline for pre-handshake connections
**Files:** `i2pchat/core/live_peer_session.py`, `i2pchat/core/i2p_chat_core.py` (`accept_loop`, `_pre_handshake_deadline`), `tests/test_pre_handshake_limit.py` (new); `cpp/core/include/i2pchat/runtime/chat_service.hpp`, `src/runtime/chat_service.cpp` (`accept_loop`)

Previously Python's single handshake watchdog was re-armed by every new connection, leaving earlier silent streams without a deadline, and the live-session cap was consumed by connections that had not handshaken. C++ had a 45-second timer but no cap.

**Fix:** Python — `LivePeerSession.inbound`, a `max_pending_inbound_handshakes()` cap (env `I2PCHAT_MAX_PENDING_HANDSHAKES`, default 3, clamped 1..16) that closes excess inbound connections before reading anything, and a per-connection `_pre_handshake_deadline` task (`PRE_HANDSHAKE_TIMEOUT = 30 s`) that drops the session if the handshake has not completed (sessions waiting on the local TOFU dialog are left alone). C++ — `ChatServiceConfig::max_pending_inbound_handshakes = 3`, `inbound_handshake_timeout = 30 s`, a `pending_inbound_` set cleared in `on_established`/`on_closed`.

#### 6. [Medium] BlindBox replica servers: connection limit and per-command deadline
**Files:** `i2pchat/blindbox/blindbox_server_example.py`, `i2pchat/blindbox/blindbox_local_replica.py`, `cpp/core/include/i2pchat/blindbox/replica_server.hpp`, `cpp/core/src/blindbox/replica_server.cpp`

**Fix:** Python server — `BLINDBOX_MAX_CONNECTIONS` (256) and `BLINDBOX_COMMAND_TIMEOUT_SEC` (30) via a `_bounded()` wrapper around the TCP and HTTP handlers (events `connection_limit`, `command_timeout`); local replica — `max_connections=64`, `command_timeout=30.0`, `wait_for` on line/byte reads. C++ — the same environment variables, an RAII connection slot and a `command_deadline` coroutine that closes the socket when the timer expires (re-armed per command).

#### 7. [Low] Group titles: sanitisation (compatible part)
**Files:** `i2pchat/groups/models.py`, `cpp/core/include/i2pchat/groups/models.hpp`, `cpp/core/src/groups/models.cpp`

Authenticating title/epoch towards observers needs a `GROUP_CONTROL` change and remains a recommendation. The compatible part is in: `sanitize_group_title` (C0/C1 controls and `0x7f` → space, whitespace collapsed, cut at 128 code points, empty → `None`) is applied in the `GroupState` constructor of both implementations; renames were already attributed to the sender by `render_group_control_text`.

#### 8. [Low] `restore_gui_settings`: key whitelist
**Files:** `i2pchat/storage/profile_export.py`, `tests/test_profile_export.py`

**Fix:** `RESTORABLE_GUI_SETTINGS` (theme, compose, notification and history preferences) plus type checks (`filter_restorable_gui_settings`: scalars or a flat dict of scalars, strings ≤ 256). `privacy_mode`/`lock_*`/`pins`, `releases_custom_*` and unknown keys are dropped; when nothing survives the filter, `gui.json` is not written. Tests: `test_gui_settings_restore_is_whitelisted`, `test_gui_settings_restore_skips_file_when_nothing_allowed`.

#### 9. [Low] Supply chain
**Files:** `.github/workflows/release-linux-pkgs.yml`, `.github/workflows/release-artifacts.yml`, `.gitleaks.toml`

**Fix:** the RPM build image is pinned by manifest-list digest (`fedora:42@sha256:99e203b8…97814`, refresh command in a comment); macOS arm64 fetches the same pinned `uv 0.6.14` tarball as x64 instead of `brew install uv`, and for both architectures the SHA-256 is now committed **in the workflow** (`UV_SHA256_X64`/`UV_SHA256_ARM64`) rather than taken from the `.sha256` published next to the archive; `.gitleaks.toml` replaces the file-wide allowlist with an allowlist extension of the `generic-api-key` rule only, scoped to fixtures (`tests/test_{clear_locked_peer,profile_dat}.py`, `cpp/testdata/vectors/*.json`, `cpp/tests/test_*.cpp`) — verified with gitleaks 8.24.2 (CI) and 8.30.1: 0 findings over the repository history, while fake `github-pat`/`stripe` tokens placed in those paths are still detected. `pip-audit` already runs on push/PR in `security-audit.yml` (`--require-hashes` over `uv export --frozen`), so duplicating it in `test-gate` is unnecessary.

---

## Remaining recommendations

1. **[High, maintainer action] Provision the `RELEASE_GPG_PRIVATE_KEY` (+ `RELEASE_GPG_PASSPHRASE`) secrets** and publish the key fingerprint in `docs/`. Until then `sign-checksums` prints a warning and releases ship without `SHA256SUMS.asc`.
2. **[High, maintainer action] Provision `ANDROID_UPLOAD_KEYSTORE_B64` / `..._PASSWORD` / `ANDROID_UPLOAD_KEY_ALIAS` / `ANDROID_UPLOAD_KEY_PASSWORD`.** Until then APKs are debug-signed and sideload-only.
3. **[Low] Group title and epoch in the groups wire** are not authenticated towards observer members (needs a `GROUP_CONTROL` format change, i.e. a new groups wire version). The sanitisation in item 7 only fixes presentation, not spoofing.
4. **[Low] Gradle `verification-metadata.xml`** for the Android dependencies (a large generated file that needs separate validation across all build configurations). Optionally, passphrase-based `.dat` encryption instead of / on top of the keyring (changes the profile format).

---

## Regression check against the 1.3.3 audit

Every fix from the previous audit is present in 1.5.2 and covered by the `test-gate.yml` tests (`tests.test_audit_remediation`, `tests.test_protocol_framing_vnext`, `tests.test_sam_input_validation` and others): profile-name validation on history import, `blindbox/dat` rejected in backups, the pre-handshake `QUIT` filter, no private key in logs/telemetry, `.b32.i2p` normalization, atomic writes, `0600` on profile files, the `.dat` lock, MAC-before-decrypt, anti-replay. No new divergence between Python and C++ in the HS4 / codec v4 frame format was found.

---

## Verification

- `pytest` (offscreen, `tests/`, excluding the known-hanging `test_create_group_dialog_saves_checked_saved_peers`) after the second pass — 874 passed, 3 failed; all 3 failures are in `test_gui_group_smoke.py`, reproduce on the clean pre-audit HEAD and are unrelated to these changes. New tests: `tests/test_pre_handshake_limit.py` (4), `test_profile_dat.py` (+2), `test_profile_export.py` (+2).
- Android `:app:assembleStandardDebug` after the second pass builds (this also compiles `cpp/core`, including `replica_server.cpp`, `transfer/manager.cpp` and `groups/models.cpp`, under the NDK).
- `actionlint` over all workflows — only the two pre-existing `ubuntu-22.04-arm` label warnings.
- `gitleaks detect` (8.24.2 and 8.30.1) over the repository history with the new `.gitleaks.toml` — 0 findings; fake tokens placed in the allowlisted paths are detected.
- `scripts/sign_release_checksums.sh` — run locally against a fake `gh` with and without a key; `gpg --verify` → "Good signature".
- `uv 0.6.14` pins: `UV_SHA256_X64`/`UV_SHA256_ARM64` checked against freshly downloaded tarballs; the `fedora:42` digest was taken from `docker buildx imagetools inspect`.
- `scripts/verify_bundled_i2pd.sh` — 7/7 binaries match their manifests.
- `pip-audit` over `uv.lock` after the Pillow bump — no known vulnerabilities in the production group.
