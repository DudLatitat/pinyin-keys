plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pinyinkeys.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pinyinkeys.ime"
        minSdk = 26
        targetSdk = 35
        // versionCode must go up for every install-over-update: CI uses its run number.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "1.0.9"
    }

    // Release signing. The keystore and passwords come from GitHub Actions secrets via
    // environment variables; nothing secret is ever in this file. If they're missing
    // (e.g. a local build), the release build is simply left unsigned.
    val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    // Installs the ahead-of-time compile list that Compose ships, so the keyboard runs
    // compiled code from the start (Play Store does this automatically; sideloads don't).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // JSON parsing for the translation API (official Kotlin library, no tracking).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Background work (network calls) without freezing the keyboard.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // EncryptedSharedPreferences: stores the API key encrypted with the Android Keystore.
    implementation("androidx.security:security-crypto:1.0.0")
    // Room: the on-device database for saved sentences (AndroidX, no network, no tracking).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
