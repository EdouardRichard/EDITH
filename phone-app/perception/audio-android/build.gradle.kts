import java.security.MessageDigest

plugins {
    id("com.android.library")
}

android {
    namespace = "com.sheen.rokid.audio.android"
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
    implementation(project(":phone-app:perception:audio-runtime"))
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    testImplementation("junit:junit:4.13.2")
    // The measurement runner consumes the versioned EngineMetrics record and the bounded RAM fixture;
    // both live in test-support so they are never part of a production artifact.
    testImplementation(project(":common:testkit:test-support"))
    androidTestImplementation(project(":common:testkit:test-support"))
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
    manifestFile.set(rootProject.layout.projectDirectory.file("tools/scripts/p2/engine-manifest.json"))
    licenseDirectory.set(rootProject.layout.projectDirectory.dir("tools/scripts/p2/licenses"))
    noticeFile.set(rootProject.layout.projectDirectory.file("tools/scripts/p2/NOTICE.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/trustedManifestAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(trustedManifestAssets, TrustedManifestAssets::outputDirectory)
}

// --- KWS model assets: optional but verified ---------------------------------------------------------
// `javap -p` on the pinned AAR shows the only public constructor is
// `KeywordSpotter(AssetManager, KeywordSpotterConfig)` and that the file-based `newFromFile` is private
// native, so the KWS model has to be read from `assets/`. The user-approved download lives OUTSIDE version
// control under the git-ignored `.local/`; when it is absent this task packages nothing and a fresh clone
// still builds and tests (the app then reports `KWS_MODEL_MISSING`).
val kwsModelDirectory = rootProject.layout.projectDirectory
    .dir(".local/p3-kws-model/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20")

/** The five files to ship and their measured SHA-256 (2026-10-09); any mismatch fails the build. */
val kwsPinnedFiles = mapOf(
    "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx" to
        "2ca84d6bfe73e1ea3c9c49f600f7cad1c9ddd423c53c906b8bfe802444dd78d5",
    "decoder-epoch-13-avg-2-chunk-8-left-64.onnx" to
        "63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21",
    "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx" to
        "190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1",
    "tokens.txt" to
        "2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0",
    "en.phone" to
        "f7000ec3a90544c0c7c16090d8951779c2b322e14dad5006290f498567d439ea",
)

abstract class KwsModelAssets : DefaultTask() {
    /** The five pinned model files; all missing means "no local bundle", some missing means "incomplete". */
    @get:InputFiles abstract val modelFiles: ConfigurableFileCollection
    /** The tracked keyword lines (`kws/keywords.txt`), copied only together with the model. */
    @get:InputFile abstract val keywordsTemplate: RegularFileProperty
    @get:Input abstract val pinnedDigests: MapProperty<String, String>
    /** Set by the app's complete build: KWS is part of the 0.4 delivery bundle, not optional there. */
    @get:Input abstract val requireBundledModels: Property<Boolean>
    /** Human-readable source directory, used only in the failure message. */
    @get:Input abstract val expectedDirectory: Property<String>
    /** Guard so the stale-output cleanup below can never walk outside the build directory. */
    @get:Internal abstract val buildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val directory = outputDirectory.get().asFile
        val build = buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val target = directory.toPath().toAbsolutePath().normalize()
        check(target.startsWith(build)) { "refusing to clear $target: it is outside $build" }
        directory.deleteRecursively()
        val files = modelFiles.files
        val missing = files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            if (requireBundledModels.get()) {
                throw GradleException(
                    "the KWS model bundle is required for this build but ${expectedDirectory.get()} is " +
                        "missing ${missing.map { it.name }.sorted()}. Provision the five pinned files there, " +
                        "or use -PwithoutBundledModels=true for a development/CI build without bundled models",
                )
            }
            check(missing.size == files.size) {
                "the local KWS model bundle is incomplete; missing ${missing.map { it.name }.sorted()}"
            }
            logger.lifecycle(
                "KWS model bundle absent under .local; packaging no kws/ assets " +
                    "(the app reports KWS_MODEL_MISSING)")
            return
        }
        val assets = directory.resolve("kws")
        assets.mkdirs()
        files.sortedBy { it.name }.forEach { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val expected = pinnedDigests.get().getValue(file.name)
            check(actual == expected) { "KWS model file ${file.name} has SHA-256 $actual, expected $expected" }
            file.copyTo(assets.resolve(file.name), overwrite = true)
        }
        val keywords = keywordsTemplate.get().asFile
        check(!keywords.readBytes().any { it == '\r'.code.toByte() }) {
            "${keywords.name} must use LF line endings"
        }
        keywords.copyTo(assets.resolve("keywords.txt"), overwrite = true)
    }
}

val kwsModelAssets = tasks.register<KwsModelAssets>("generateKwsModelAssets") {
    modelFiles.from(kwsPinnedFiles.keys.map { kwsModelDirectory.file(it) })
    keywordsTemplate.set(layout.projectDirectory.file("kws/keywords.txt"))
    pinnedDigests.set(kwsPinnedFiles)
    // The app's complete build sets this root property; a standalone audio-module build keeps the old
    // optional behaviour. Evaluated lazily so it is read after every project (including the app) is configured.
    requireBundledModels.set(project.provider {
        project.rootProject.findProperty("edithRequireBundledModels")?.toString()?.toBoolean() ?: false
    })
    expectedDirectory.set(kwsModelDirectory.asFile.absolutePath)
    buildDirectory.set(layout.buildDirectory)
    outputDirectory.set(layout.buildDirectory.dir("generated/kwsAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(kwsModelAssets, KwsModelAssets::outputDirectory)
}
