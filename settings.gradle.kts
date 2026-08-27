pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RoastCurve"

include(":shared")
include(":design-system")
include(":composeApp")
include(":androidApp")