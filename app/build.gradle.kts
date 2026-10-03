plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stevestudy.angrybirdsauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stevestudy.angrybirdsauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Force 64-bit only — REDMAGIC 11 Pro dropped armeabi-v7a support entirely.
        // This strips all 32-bit .so files at build time; only libopencv_java4.so
        // for arm64-v8a will be packaged.
        ndk {
            abiFilters("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.0")

    // OpenCV Android SDK (from JitPack) — v4.12.0 (>= 4.5+ requirement met).
    // The ndk { abiFilters("arm64-v8a") } above ensures only the 64-bit
    // libopencv_java4.so is packaged; 32-bit variants are stripped.
    implementation("com.github.steve1316:opencv-android-sdk:4.12.0")

    // Shizuku API — for appops-based SYSTEM_ALERT_WINDOW bypass on
    // Red Magic OS (Android 15+), where the standard settings toggle
    // is permanently greyed out for sideloaded apps.
    implementation("dev.rikka.shizuku:api:13.1.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}