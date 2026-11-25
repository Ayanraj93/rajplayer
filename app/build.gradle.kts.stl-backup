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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raj.rajplayer"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "1.4-media3"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // LGPL LAME 3.100 Prefab package; built for Android native ABIs.
    implementation("com.fpliu.ndk.pkg.prefab.android.21:lame:3.100")
}
