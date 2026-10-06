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
    ":audio-runtime",
    ":storage-runtime",
    ":model-runtime",
    ":output-runtime",
    ":plugins-diagnostic",
    ":tools:p2-text-probe",
)
