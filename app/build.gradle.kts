plugins {
    id("com.android.application")
}

android {
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    namespace = "com.raj.rajplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.raj.rajplayer"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "1.4-media3"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "rajplayer123"
            keyAlias = "rajplayer"
            keyPassword = "rajplayer123"
        }
    }


    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // implementation("com.google.android.gms:play-services-ads:23.6.0")
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-decoder-av1:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // LGPL LAME 3.100 Prefab package; built for Android native ABIs.
    implementation("com.fpliu.ndk.pkg.prefab.android.21:lame:3.100")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.4") {
        exclude(group = "org.jsoup", module = "jsoup")
        exclude(group = "org.mozilla", module = "rhino")
    }
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
