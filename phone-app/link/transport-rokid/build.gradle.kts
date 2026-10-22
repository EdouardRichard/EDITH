import java.security.MessageDigest

plugins { id("com.android.library") }

android {
    namespace = "com.sheen.rokid.transport"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("edith.vendor.cxr:client-l:1.1.2@aar")
    implementation("edith.vendor.cxr:cxr-service-bridge:1.0-20260715.121510-107@aar")
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.10.1")
    api(project(":common:contracts:core-contracts"))
    api(project(":common:contracts:plugin-api"))
    api(project(":phone-app:perception:audio-runtime"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.lifecycle:lifecycle-runtime:2.8.6")
    androidTestImplementation("androidx.annotation:annotation:1.8.1")
    androidTestImplementation(project(":phone-app:assistant:host-runtime"))
    androidTestImplementation(project(":common:testkit:test-support"))
}

val verifiedCxrArtifacts = mapOf(
    "client-l-1.1.2.aar" to "4E79963FF5AD84E018783C74A17C6BEDBEBD3C6076BAD3C00BCE15250A4DAF25",
    "cxr-service-bridge-1.0-20260715.121510-107.aar" to "0A76387E106A51578F82169AEB2DA904372A9569B26AF1E6C87CEBE7A76B94FC",
)
verifiedCxrArtifacts.forEach { (name, expected) ->
    // Resolved for either layout: a plain checkout puts the vendored AARs at <root>/third_party/cxr/vendor, while a git
    // worktree two levels below the root (…/.worktrees/<name>) reaches them through ../../probes. Pinning only the
    // worktree path made the module unbuildable from the repository root, which is where the merged main tree lives.
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
dependencyLocking { lockAllConfigurations() }
