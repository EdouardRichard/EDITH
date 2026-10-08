import java.security.MessageDigest

plugins {
    id("com.android.library")
}

android {
    namespace = "edith.audio.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.kotlinx.serialization.json)
    implementation("edith.vendor.sherpa:sherpa-onnx:1.13.8@aar")
    implementation(project(":audio-runtime"))
    implementation(project(":core-contracts"))
    implementation(project(":plugin-api"))
    testImplementation("junit:junit:4.13.2")
    // The measurement runner consumes the versioned EngineMetrics record and the bounded RAM fixture;
    // both live in test-support so they are never part of a production artifact.
    testImplementation(project(":test-support"))
    androidTestImplementation(project(":test-support"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.lifecycle:lifecycle-runtime:2.8.6")
    androidTestImplementation("androidx.annotation:annotation:1.8.1")
}

val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
check(sherpaAar.isFile) { "Import the indexed public sherpa-onnx-1.13.8 AAR into audio-android/libs first" }
val sherpaDigest = MessageDigest.getInstance("SHA-256")
sherpaAar.inputStream().use { input ->
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        sherpaDigest.update(buffer, 0, count)
    }
}
check(sherpaDigest.digest().joinToString("") { "%02X".format(it.toInt() and 255) } ==
    "633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96") {
    "Sherpa AAR hash does not match the indexed public runtime"
}

dependencyLocking { lockAllConfigurations() }

abstract class TrustedManifestAssets : DefaultTask() {
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:InputDirectory abstract val licenseDirectory: DirectoryProperty
    @get:InputFile abstract val noticeFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        manifestFile.get().asFile.copyTo(directory.resolve("engine-manifest.json"), overwrite = true)
        licenseDirectory.get().asFile.copyRecursively(directory.resolve("engine-licenses"), overwrite = true)
        noticeFile.get().asFile.copyTo(directory.resolve("engine-licenses/NOTICE.md"), overwrite = true)
    }
}
val trustedManifestAssets = tasks.register<TrustedManifestAssets>("generateTrustedManifestAssets") {
    manifestFile.set(rootProject.layout.projectDirectory.file("tools/p2/engine-manifest.json"))
    licenseDirectory.set(rootProject.layout.projectDirectory.dir("tools/p2/licenses"))
    noticeFile.set(rootProject.layout.projectDirectory.file("tools/p2/NOTICE.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/trustedManifestAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(trustedManifestAssets, TrustedManifestAssets::outputDirectory)
}
