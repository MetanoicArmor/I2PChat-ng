# I2PChat for Android

Native phone client: **Kotlin + Jetpack Compose** UI over the existing C++ `libi2pchat_core` / `ChatService`. Open the `android/` folder in Android Studio.

## First build

1. Install Android Studio, NDK, and CMake (SDK Manager → SDK Tools).
2. File → Open → `android/`.
3. Let Gradle sync. The first native configure **downloads Boost, nlohmann/json and libsodium 1.0.20** via CMake FetchContent (slow, needs network). If CMake configure previously failed, delete `app/.cxx` and sync again.
4. Run on a **phone (arm64)** or a **x86_64 emulator** (Device Manager → system image **x86_64**, not armeabi-v7a).

The first Run on the emulator compiles native code for **x86_64**. That download/build of Boost and libsodium can take several minutes.

If install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`, the AVD is not x86_64/arm64 — create a new virtual device with an **x86_64** Google APIs image.

Minimum SDK 26. Application id: `org.i2pchat.android`.

## I2P router

The core talks SAM on loopback, same as desktop.

- **Bundled i2pd (default):** PurpleI2P **2.61.0** JNI `libi2pd.so` from the official APK, started **in-process** (the CLI binary segfaults on 16 KB emulator pages). Open waits for SAM on `127.0.0.1:17656`.
- **External SAM:** switch to system router in Settings → I2P router (`127.0.0.1:7656`).

I2P on a phone cannot hide in the background; the persistent notification is required so tunnels are not killed.

## What is implemented

Profile picker, contacts, 1:1 chat, TOFU, groups (create / join / invite / topology JSON), files and images via the system picker, emoji, BlindBox poll and replica list, router settings, backups, light/dark/system theme, notifications, compose drafts, history retention.

Profiles live under `files/i2pchat/profiles/<name>/` in the same layout as desktop, so a desktop backup bundle can be imported.
