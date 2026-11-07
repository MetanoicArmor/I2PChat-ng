# I2PChat for Android

Native phone client on this branch: **Kotlin + Jetpack Compose** UI over `cpp/core` (`libi2pchat_core` / `ChatService`). The core speaks the same vNext protocol as the Python client. The APK version is the repo `VERSION` file.

Open the `android/` folder in Android Studio, or build from the repo root with `./build-android.sh`.

## First build (Android Studio)

1. Install Android Studio, **NDK r28** (`28.2.13676358`), and CMake (SDK Manager → SDK Tools).
2. File → Open → `android/`.
3. Let Gradle sync. The first native configure **downloads Boost, nlohmann/json and libsodium 1.0.20** via CMake FetchContent (slow, needs network). If CMake configure previously failed, delete `app/.cxx` and sync again.
4. Run on a **phone (arm64)** or a **x86_64 emulator** (Device Manager → system image **x86_64**, not armeabi-v7a).

The first Run on the emulator compiles native code for **x86_64**. That download/build of Boost and libsodium can take several minutes.

If install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`, the AVD is not x86_64/arm64 — create a new virtual device with an **x86_64** Google APIs image.

Minimum SDK 26. Application id: `org.i2pchat.android`. Default UI theme: **dark**.

## CLI build (`./build-android.sh`)

From the **repository root** (needs JDK 17+, `ANDROID_HOME` / SDK, NDK):

```bash
./build-android.sh --debug              # default — signed with the debug keystore
./build-android.sh --release            # release APK; needs I2PCHAT_ANDROID_KEYSTORE/_KEYSTORE_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD,
                                        # or I2PCHAT_ANDROID_ALLOW_DEBUG_SIGNING=1 for a sideload-only build with the public debug key
./build-android.sh --debug --install    # build + adb install -r
./build-android.sh --arm32 --release    # 32-bit sideload APK, no bundled i2pd
```

Output: `dist/I2PChat-android-v<VERSION>-<debug|release>.apk` and a matching `.sha256` sidecar.

`--arm32` writes `dist/I2PChat-android-v<VERSION>-arm32-<debug|release>.apk`. It is **armeabi-v7a only**, Android 8.0+ (minSdk 26), and does not ship `libi2pd.so`. The phone needs a separate i2pd with SAM on `127.0.0.1:7656`. Play Store still wants the 64-bit APK; this one is sideload only. It uses the same application id, so it replaces the 64-bit install.

Bundled `libi2pd.so` for **arm64-v8a** and **x86_64** must exist under `app/src/main/jniLibs/` (checked into the tree). Refresh them from the official i2pd-android APKs:

```bash
./android/scripts/fetch-i2pd.sh
```

## I2P router

The core talks SAM on loopback, same as desktop.

- **Bundled i2pd (default):** PurpleI2P **3.0.0** JNI `libi2pd.so` from the official APKs (already **16 KiB** aligned; refresh with `./android/scripts/fetch-i2pd.sh`), started **in-process in a separate `:i2pd` service**. Open waits for SAM on `127.0.0.1:17656`. Chat native code is also NDK r28 / 16 KiB.
- **External SAM:** switch to system router in Settings → I2P router (`127.0.0.1:7656`).

I2P on a phone cannot hide in the background; the persistent notification is required so tunnels are not killed.

## What is implemented

Profile picker, contacts, 1:1 chat, TOFU, groups (create / join / invite / topology JSON), files and images via the system picker, emoji, BlindBox poll and replica list, router settings, backups, light/dark/system theme (default **dark**), notifications, compose drafts, history retention. Tap the green **Online! My Address** banner to copy your destination.

Profiles live under `files/i2pchat/profiles/<name>/` in the same layout as desktop, so a desktop backup bundle can be imported.
