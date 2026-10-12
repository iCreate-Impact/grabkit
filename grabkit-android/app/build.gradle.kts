plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
}

val abis = (project.findProperty("grabkit.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }
val ffmpegCoordinate = project.findProperty("grabkit.ffmpeg") as String? ?: "dev.ffmpegkit-maintained:ffmpeg:6.0.3"

android {
    namespace = "com.grabkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.grabkit"
        minSdk = 24  // bumped from 21; ffmpegkit-maintained:ffmpeg:6.0.3 requires API 24+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis } // Chaquopy requires explicit ABIs
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" } // matches Kotlin 1.9.24
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Extract .so files to nativeLibraryDir so a bundled JS runtime (libqjs.so) can be executed.
        jniLibs.useLegacyPackaging = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // buildPython("/path/to/python3") // set if the default python3 on the build machine is < 3.8
        pip {
            install("yt-dlp")
            // YouTube needs yt-dlp's JS-challenge scripts. Each yt-dlp release pins one exact
            // yt-dlp-ejs version; if pip resolves a mismatch, yt-dlp rejects it. Pin to match:
            install("yt-dlp-ejs")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:4.12.0")

    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation(ffmpegCoordinate)
}
