pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "Stash"

include(":app")

include(":core:ui")
include(":core:model")
include(":core:common")
include(":core:data")
include(":core:media")
include(":core:auth")
include(":core:network")
include(":core:addons")

include(":data:spotify")
include(":data:ytmusic")
include(":data:download")
include(":data:lyrics")
include(":data:cloud")

// The YouTube Music extension: radio, and later Home's shelves. Detachable — it only
// implements hooks in :core, so this include plus one dependency line in
// app/build.gradle.kts is the whole of its footprint on the project.
include(":ext:ytmusic")

include(":feature:home")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:sync")
include(":feature:settings")
include(":feature:search")
include(":feature:community")
include(":feature:listening")
include(":feature:record")

include(":data:soundcloud")
