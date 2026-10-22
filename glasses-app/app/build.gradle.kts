import java.security.MessageDigest

plugins { id("com.android.application") }

android {
    namespace = "com.sheen.rokid.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sheen.rokid.glasses"
        // The glasses run Android 12 (YodaOS-Sprite); the CXR-S bridge requires minSdk >= 28.
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 0.24 S2 Task 9: the debug-only Activity test host seam is gated on BuildConfig.DEBUG. AGP 9 no longer generates
    // BuildConfig unless asked, so the seam (and its release-build guard) would otherwise not compile.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // The hint protocol, the clock mapping and the render/expiry rules live in `output-runtime` so the phone and the
    // glasses share exactly one implementation of the wire format and of the ordering rules. A second copy here would
    // eventually disagree with the phone about the same bytes, which is the failure this structure exists to prevent.
    implementation(project(":common:presentation:output-runtime"))
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    // 0.24 S4 Task 18 (D159/D166): the official PAG voice orb. The nine `.pag` assets are the decoded official
    // resources; libpag is the renderer those assets were authored for. Glasses-only: the phone never draws them.
    implementation("com.tencent.tav:libpag:4.5.27")
    // 0.24 S4 Task 19: the official answer row renders Markdown with Markwon core (the same `setMarkdown` call the
    // decompiled `AiAdapter` makes). Glasses-only: the phone's Compose assistant tab renders its own text.
    implementation("io.noties.markwon:core:4.6.2")
    // Markwon's POM pins androidx.annotation 1.1.0; the androidTest classpath already requires 1.8.1 (its explicit
    // dependency below). Gradle's consistent resolution then refuses the test manifest unless the production classpath
    // resolves the same version, so pin it explicitly here rather than letting Markwon's floor win.
    implementation("androidx.annotation:annotation:1.8.1")
    // CXR-S: the glasses-side bridge. The same public bytes the phone side and the vendor's own glasses sample use.
    implementation("edith.vendor.cxr:cxr-service-bridge:1.0-20260715.121510-107@aar")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // `androidx.test:core` (via rules) would otherwise resolve uncached transitive versions in offline builds; these
    // are the versions the rest of the project builds and caches against (same set used by `transport-rokid`).
    androidTestImplementation("androidx.lifecycle:lifecycle-runtime:2.8.6")
    androidTestImplementation("androidx.annotation:annotation:1.8.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    androidTestImplementation(project(":common:testkit:test-support"))
}

/**
 * The bridge is a public binary; the build refuses to proceed if the indexed bytes changed.
 *
 * The signatures this module calls were read out of *this* artifact with `javap` rather than from memory:
 * `subscribe(String, MsgReplyCallback)`, `MsgReplyCallback.onReceive(String, Caps, byte[], Reply)`,
 * `Caps.write(String)`, `Caps.at(int): Caps.Value`, `Caps.Value.getString()` and `sendMessage(String, Caps): int`.
 */
val verifiedBridge = "cxr-service-bridge-1.0-20260715.121510-107.aar" to
    "0A76387E106A51578F82169AEB2DA904372A9569B26AF1E6C87CEBE7A76B94FC"
run {
    val (name, expected) = verifiedBridge
    // Same two-layout resolution as transport-rokid: a plain checkout keeps the AARs at <root>/third_party/cxr/vendor, a
    // worktree two levels below the root reaches them via ../../probes. See that module for why this must not be pinned.
    val artifact = listOf(
        rootProject.file("third_party/cxr/vendor/$name"),
        rootProject.file("../../third_party/cxr/vendor/$name"),
    ).firstOrNull { it.isFile } ?: rootProject.file("third_party/cxr/vendor/$name")
    check(artifact.isFile) { "Missing indexed public CXR artifact: $name (looked in third_party/cxr/vendor and ../../third_party/cxr/vendor of $rootDir)" }
    val digest = MessageDigest.getInstance("SHA-256")
    artifact.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    check(digest.digest().joinToString("") { "%02X".format(it.toInt() and 255) } == expected) {
        "CXR artifact differs from indexed public binary: $name"
    }
}
