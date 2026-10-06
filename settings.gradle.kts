pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri("https://maven.rokid.com/repository/maven-public/") } }
            filter { includeGroup("com.rokid.cxr") }
        }
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
    ":phone-app",
)
