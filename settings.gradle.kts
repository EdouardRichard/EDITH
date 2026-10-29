pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // The vendored CXR AARs sit at <root>/third_party/cxr/vendor in a plain checkout. A git worktree created two levels
    // below the root (…/.worktrees/<name>) reaches the same files through ../../probes, and pinning that one layout
    // made the repository resolve to a directory outside the repository (D:/probes) whenever the project was built
    // from the root checkout - the merged main tree therefore could not configure transport-rokid or glasses-app.
    val verifiedCxrDir = listOf(rootDir.resolve("third_party/cxr/vendor"), rootDir.resolve("../../third_party/cxr/vendor"))
        .firstOrNull { it.isDirectory } ?: rootDir.resolve("third_party/cxr/vendor")
    repositories {
        google()
        mavenCentral()
        // Standard Android flat local repositories for the two vendored AAR sets (no Maven metadata is published):
        // CXR in `third_party/cxr/vendor`, sherpa-onnx in the audio-android module's `libs/`. Exclusive content pins
        // these groups to the flat directories so the remote Maven repositories are never asked for them.
        exclusiveContent {
            forRepository { flatDir { dirs(verifiedCxrDir) } }
            filter { includeGroup("edith.vendor.cxr") }
        }
        exclusiveContent {
            forRepository { flatDir { dirs(rootDir.resolve("phone-app/perception/audio-android/libs")) } }
            filter { includeModule("edith.vendor.sherpa", "sherpa-onnx") }
        }
        exclusiveContent {
            forRepository { maven { url = uri("https://maven.rokid.com/repository/maven-public/") } }
            filter {
                includeGroup("com.rokid.cxr")
                // P3.0 T0/T1 probe: `com.rokid.security:glass3.open.sdk:2.2.0-E` (the Glasses-side open SDK that owns
                // the offline wake-word service). Its POM was read before this line was written; it pulls two further
                // Rokid groups of its own, so all three are routed here rather than only `com.rokid.security`.
                includeGroup("com.rokid.security")
                includeGroup("com.rokid.utils")
                includeGroup("com.rokid.logger")
            }
        }
        // P3.3 Task 1: `dev.rikka.shizuku:api|provider` lives on Maven Central only. Without this filter Gradle asks
        // `google()` first and a blocked `dl.google.com` (measured 2026-10-10: TCP 443 timeout on this host) aborts the
        // whole resolution for the module instead of falling through to the next repository. `exclusiveContent` pins the
        // group to the one repository that actually serves it; every other module keeps its existing order.
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("dev.rikka.shizuku") }
        }
    }
}

rootProject.name = "edith"

include(
    ":common:contracts:core-contracts",
    ":common:contracts:plugin-api",
    ":phone-app:assistant:host-runtime",
    ":common:testkit:test-support",
    ":phone-app:plugins:plugin-smart-helper",
    ":phone-app:plugins:plugin-interview",
    ":phone-app:perception:audio-runtime",
    ":phone-app:perception:audio-android",
    ":phone-app:link:transport-rokid",
    ":phone-app:persistence:storage-runtime",
    ":phone-app:assistant:model-runtime",
    // P3.1 agent loop: platform-independent tool protocol, registry and loop (Tasks 4-8).
    ":phone-app:assistant:agent-runtime",
    // 0.25 DeviceControl kernel: pure-JVM observation/ref/diff/render model (Task 7).
    ":phone-app:assistant:device-control",
    ":common:presentation:output-runtime",
    ":phone-app:plugins:plugin-diagnostic",
    ":tools:probes:p2-text-probe",
    // P3.0 throwaway probe (T4): can a `tools` array round-trip through the Responses endpoint, and does the reply
    // carry a `function_call` item? Not a delivery tool.
    ":tools:probes:p3-toolcall-probe",
    // P3.0 agent probe: an LLM-driven loop that chooses tools and has them executed on a real device through adb.
    // This is the end-to-end verification of the Agent harness, not a hand-run matrix. Not a delivery tool.
    ":tools:probes:p3-agent-probe",
    ":phone-app:app",
    // Glasses-side audio capture probe: runs on the glasses (YodaOS-Sprite / Android 12), not on the phone.
    ":tools:probes:glasses-audio-probe",
    // Glasses-side CustomApp: renders the phone's hints through the CXR-S bridge (T18).
    ":glasses-app:app",
    // P3.0 throwaway probe (T0/T1): can the Glasses-side open SDK and the CXR-S bridge live in one glasses app, and
    // can the offline wake-word lexicon be customised without stealing the microphone? Not a delivery module.
    ":tools:probes:glasses-sdk-coexistence-probe",
    // P3.0 throwaway probe (T3b): how often does a semantic (accessibility-node) cross-app click actually land,
    // compared with a coordinate tap at the same node's bounds? Not a delivery module.
    ":tools:probes:phone-accessibility-probe",
    // P3.4 evaluation harness: scores the frozen corpus against what the real trigger path actually did (precision /
    // recall per scenario, the chat mis-trigger window). Reads logs; never touches the device itself. Not a delivery
    // module.
    ":tools:eval:p3-eval-trigger",
)
