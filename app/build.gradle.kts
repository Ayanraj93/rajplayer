plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    namespace = "com.raj.rajplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raj.rajplayer"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "1.2"
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "rajplayer123"
            keyAlias = "rajplayer"
            keyPassword = "rajplayer123"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
