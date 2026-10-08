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
            forRepository {
                ivy {
                    name = "verifiedCxrRuntime"
                    url = uri("../../probes/p0/vendor")
                    patternLayout { artifact("[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("edith.vendor.cxr") }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "verifiedSherpaRuntime"
                    url = uri("audio-android/libs")
                    patternLayout { artifact("[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("edith.vendor.sherpa", "sherpa-onnx") }
        }
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
    ":audio-android",
    ":transport-rokid",
    ":storage-runtime",
    ":model-runtime",
    ":output-runtime",
    ":plugins-diagnostic",
    ":tools:p2-text-probe",
    ":phone-app",
    // Glasses-side audio capture probe: runs on the glasses (YodaOS-Sprite / Android 12), not on the phone.
    ":glasses-audio-probe",
    // Glasses-side CustomApp: renders the phone's hints through the CXR-S bridge (T18).
    ":glasses-app",
)
