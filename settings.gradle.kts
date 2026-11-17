pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // XposedBridge API (compileOnly)
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "PaiGuard"
include(":app")
