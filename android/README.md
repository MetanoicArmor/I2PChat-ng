# I2PChat for Android

Native phone client: **Kotlin + Jetpack Compose** UI over the C++ `libi2pchat_core` / `ChatService`.

**Prebuilt APK:** [I2PChat-android-v1.5.0.apk](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest/download/I2PChat-android-v1.5.0.apk) on the [latest release](https://github.com/MetanoicArmor/I2PChat-ng/releases/latest) (or build below).

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
./build-android.sh --release            # release APK (upload keystore via I2PCHAT_ANDROID_* env, else debug keystore)
./build-android.sh --debug --install    # build + adb install -r
```

Output: `dist/I2PChat-android-v<VERSION>-<debug|release>.apk` and a matching `.sha256` sidecar.

Bundled `libi2pd.so` for **arm64-v8a** and **x86_64** must exist under `app/src/main/jniLibs/` (checked into the tree). To rebuild them for 16 KiB pages:

```bash
./android/scripts/build-i2pd-16k.sh
```

## I2P router

The core talks SAM on loopback, same as desktop.

- **Bundled i2pd (default):** PurpleI2P **2.61.0** JNI `libi2pd.so`, rebuilt with **NDK r28** for **16 KiB** ELF alignment (`./android/scripts/build-i2pd-16k.sh`), started **in-process in a separate `:i2pd` service**. Open waits for SAM on `127.0.0.1:17656`. Chat native code is also NDK r28 / 16 KiB.
- **External SAM:** switch to system router in Settings → I2P router (`127.0.0.1:7656`).

I2P on a phone cannot hide in the background; the persistent notification is required so tunnels are not killed.

## What is implemented

Profile picker, contacts, 1:1 chat, TOFU, groups (create / join / invite / topology JSON), files and images via the system picker, emoji, BlindBox poll and replica list, router settings, backups, light/dark/system theme (default **dark**), notifications, compose drafts, history retention. Tap the green **Online! My Address** banner to copy your destination.

Profiles live under `files/i2pchat/profiles/<name>/` in the same layout as desktop, so a desktop backup bundle can be imported.
