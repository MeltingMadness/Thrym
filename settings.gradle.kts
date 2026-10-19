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

rootProject.name = "Thrym"

include(":app")
include(":shared")

include(":core:model")
include(":core:common")
include(":core:database")
include(":core:designsystem")

include(":data")

include(":feature:dashboard")
include(":feature:workout")
include(":feature:exercises")
include(":feature:history")
include(":feature:plans")
include(":feature:statistics")
include(":feature:settings")
include(":feature:progression")
include(":feature:onboarding")
