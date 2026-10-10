pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Xposed / LSPosed API lives in JitPack for some versions.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BA4DKeepAlive"
include(":app")
