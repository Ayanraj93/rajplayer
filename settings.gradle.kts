import androidx.media3.buildlogic.includeMedia3

pluginManagement {
    includeBuild("androidx-media/build-logic-settings")
    repositories {
        google()
        mavenCentral()
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
    }
}
rootProject.name = "RajPlayer"
include(":app")
includeMedia3(file("androidx-media"))
