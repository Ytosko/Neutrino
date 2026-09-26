pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Neutrino"
include(":app")
include(":glucose-ble")
include(":metersim")
include(":wear-protocol")
// The Wear OS app. F-Droid deletes this folder before building (it uses Google Play services), so
// only include it when it is there.
if (file("wear").isDirectory) include(":wear")
