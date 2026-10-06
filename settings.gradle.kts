pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "edith"

include(
    ":core-contracts",
    ":plugin-api",
    ":host-runtime",
    ":test-support",
    ":plugins-smart",
    ":plugins-interview",
)
