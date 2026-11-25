import androidx.media3.buildlogic.includeMedia3

pluginManagement {
    includeBuild("androidx-media/build-logic-settings")
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}
plugins {
    id("gradlebuild.media3-settings-logic")
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "RajPlayer"
include(":app")
includeMedia3(file("androidx-media"))
