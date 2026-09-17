plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.i2pchat.android"
    compileSdk = 37
    // NDK r28+ defaults arm64/x86_64 shared libs to 16 KiB ELF alignment.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.i2pchat.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 150
        versionName = "1.5.0"
        ndk {
            // Phones are arm64; Windows/macOS emulators are almost always x86_64.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DI2PCHAT_ANDROID=ON",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    // Release APKs must be signed or PackageManager rejects them
    // (INSTALL_PARSE_FAILED_NO_CERTIFICATES). Prefer a real upload keystore via
    // env when cutting Play/production builds; otherwise fall back to the
    // Android debug keystore so GitHub Release APKs remain sideloadable.
    val releaseStoreFile = System.getenv("I2PCHAT_ANDROID_KEYSTORE")
    val releaseStorePassword = System.getenv("I2PCHAT_ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("I2PCHAT_ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("I2PCHAT_ANDROID_KEY_PASSWORD")
    val hasReleaseKeystore =
        !releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank() &&
            file(releaseStoreFile).isFile

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                // Same defaults as the Android Studio debug keystore.
                val debugStore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
            // Uncompressed 16 KB-aligned libs. Legacy packaging compresses .so
            // files and makes Android 15 treat the APK as not 16 KB compatible.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
