// Throwaway S1 device probe (0.24). Standalone by design: it must build without the product module graph,
// because it proves platform/runtime behaviour (overlay focus + app-op + screen wake) rather than product code.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glasses-overlay-spike"
include(":app")
