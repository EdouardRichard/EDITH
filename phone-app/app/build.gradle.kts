import com.android.build.api.variant.BuildConfigField
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

/**
 * Whether the MediaTek NeuroPilot NPU runtime ships in this build.
 *
 * **Opt-in, and the reason is a crash rather than taste.** D64 made the GPU the runtime backend and left the NPU
 * diagnostic-only. Shipping its runtime unconditionally also breaks every non-MediaTek device: LiteRT loads the
 * bundled `libLiteRtCompilerPlugin_MediaTek.so` on a Qualcomm SoC and the process dies inside
 * `LiteRtCreateCompiledModel` for **every** graph — GPU and CPU alike — so neither Laya nor the embedding engine can
 * start. Measured on the K40 (Snapdragon 870) on 2026-10-15; the same combination is recorded as D64 finding 3.
 * Enable it for NPU work on the MediaTek delivery target:
 * `gradlew -PwithMediaTekNpu=true :phone-app:assembleDebug`.
 *
 * Declared at file scope because both the `android` block (the adapter source set) and the `dependencies` block (the
 * two LiteRT `.so` files) need it.
 */
val withMediaTekNpu = providers.gradleProperty("withMediaTekNpu").orNull?.toBoolean() ?: false

android {
    namespace = "com.sheen.rokid.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sheen.rokid.phone"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0-preview"
        testInstrumentationRunner = "com.sheen.rokid.phone.PhoneTestRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".local/signing/p2-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    // `aidl` is what compiles the `IRemoteShell` interface the UserService is reached through (P3.3 Task 8).
    buildFeatures { compose = true; buildConfig = true; aidl = true }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "debug-proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // MainActivity is a ComponentActivity: the ActivityResult API is backed by activity 1.9.2 and the app has no
        // Fragment/FragmentActivity. The transitive fragment 1.1.0 is unused, so this release-only fatal is a false
        // positive; keep it visible in debug/lint reports but do not block the formal APK on it.
        disable += "InvalidFragmentVersionForActivityResult"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // The MediaTek NeuroPilot **adapter** (`libneuronusdk_adapter.mtk.so`) is not on Maven — it comes from the
    // NeuroPilot SDK's `usdk/lib64`. It is provisioned under `.local/` (like the model files and the signing
    // key) rather than committed, and this source set points at it. Leaving the directory absent is not an
    // error: the APK then simply has no bundled adapter, and the plugin resolves the device's own
    // (`libneuron_adapter_mgvi.so` / its system `libneuronusdk_adapter.mtk.so`) through the
    // `<uses-native-library>` declarations that `litert-api` merges in.
    //
    // Bundling one matters because it is the version-matched pair the plugin was built against, and because a
    // library in the app's own native directory is what `dlopen` finds first.
    //
    // Gated by [withMediaTekNpu] (file scope) for the crash reason documented there.
    if (withMediaTekNpu) {
        sourceSets.getByName("main").jniLibs.srcDir(rootProject.file(".local/npu-runtime"))
    }
    // edith-0.2 NPU measurement: LiteRT resolves the NPU dispatch/compiler plugins by **path** (it is handed
    // `Environment.Option.DispatchLibraryDir`), so the vendor `.so` files have to exist as real files on disk.
    // With the AGP default (`extractNativeLibs=false`) they live only inside the APK and the directory LiteRT is
    // given does not exist. This is the same requirement the publisher documents for the Qualcomm runtime
    // ("packaging { jniLibs { useLegacyPackaging = true } } // Needed for ... NPU runtime libraries"), and it is
    // kept unconditional because the **GPU** dispatch library is resolved the same way.
    packaging { jniLibs { useLegacyPackaging = true } }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    implementation(project(":phone-app:assistant:host-runtime"))
    // 0.25 Task 9: the pure-JVM device-control kernel model (Observation/DeviceNode/RefRegistry).
    // This is the only module the accessibility adapter is allowed to share a ref registry with:
    // Task 10 constructs `DeviceKernel(..., registry = service.registry)`.
    implementation(project(":phone-app:assistant:device-control"))
    implementation(project(":phone-app:persistence:storage-runtime"))
    implementation(project(":phone-app:assistant:model-runtime"))
    implementation(project(":phone-app:perception:audio-runtime"))
    implementation(project(":phone-app:perception:audio-android"))
    implementation(project(":phone-app:link:transport-rokid"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":common:presentation:output-runtime"))
    implementation(project(":phone-app:plugins:plugin-smart-helper"))
    // P3.3 Task 1: the shell-UID execution channel (spec §17.2). `provider` is what the `api` binds to at runtime.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // edith-0.2 Phase A: LiteRT for on-device Laya + EmbeddingGemma inference. 2.3.0 as of 2026-10-14, because
    // the delivery target's NPU is a MediaTek APU and its NeuroPilot dispatch/compiler plugin is published only
    // from 2.3.0. That move is why Kotlin is 2.4.0 (2.3.0's `litert-api` carries mv=[2,4,0] metadata) and why
    // `litert-gpu` is now a separate artifact: 2.3.0 split the OpenCL accelerator out of `litert`.
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    // The MediaTek NeuroPilot NPU runtime: dispatch library + JIT compiler plugin. `litert-api` 2.3.0 already
    // declares the `<uses-native-library>` entries the linker needs before the plugin may dlopen the device's
    // `libneuron*` vendor libraries; this artifact supplies the two `.so` files themselves.
    //
    // **Opt-in**, for the crash reason documented at `withMediaTekNpu` above: on a non-MediaTek device these two
    // `.so` files are loaded anyway and kill the process inside `LiteRtCreateCompiledModel`.
    if (withMediaTekNpu) implementation(libs.litert.npu.mediatek)
    implementation(project(":phone-app:plugins:plugin-interview"))
    debugImplementation(project(":phone-app:plugins:plugin-diagnostic"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 0.5.1 (C14): the phone-only overlay hosts Compose in a Service, whose custom owner needs the lifecycle/savedstate
    // ViewTree classes at runtime. These explicit declarations keep the versions the rest of the graph then resolves at
    // 2.10.0 / 1.4.0 instead of the older Compose/Activity defaults; DECISIONS §21.1 records why they are kept.
    //
    // They do NOT put `ViewTreeLifecycleOwner` on Kotlin's compile classpath in this KMP variant graph (measured
    // 2026-10-29: a direct import is an unresolved reference), so the service still installs both ViewTree owners
    // reflectively and surfaces an install failure as a visible start failure.
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    testImplementation(libs.junit)
    testImplementation(project(":common:testkit:test-support"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":common:testkit:test-support"))
}
// LiteRT pulls in Play Asset Delivery (its NPU-accelerator download path) and, through it,
// `androidx.work:work-runtime`. This app's models ship on the device rather than through Play, and on the
// minified debug build the work-runtime androidx.startup initializer **crashes the process**:
// Room's generated `WorkDatabase_Impl` is stripped by R8, so `Room.getGeneratedImplementation` throws inside
// `ContentProvider.attachInfo` (measured on the K70, 2026-10-14: "Process crashed before executing the
// test(s)"). Excluding on the `litert` declaration is no longer enough on 2.3.0 — the same dependency also
// arrives through `litert-api`, which `litert-gpu` reaches on its own path — so the exclusion is
// configuration-wide. Re-check this on the next LiteRT bump: if Play's AI-Pack delivery is ever wanted, these
// two are exactly what has to come back, together with the R8 keep rule for Room's generated database.
configurations.configureEach {
    exclude(group = "com.google.android.play", module = "asset-delivery")
    exclude(group = "androidx.work", module = "work-runtime")
}
dependencyLocking { lockAllConfigurations() }

// Compose mapping is optional metadata and its unpublished producer is unavailable offline.
tasks.matching {
    it.name == "produceDebugComposeMapping" ||
        it.name == "reportDebugComposeMappingErrors" ||
        it.name == "mergeDebugComposeMapping"
}.configureEach { enabled = false }

// --- 0.4 bundled model assets: verified, fail-closed, development-light switch ------------------------------
//
// The 0.4 delivery APK ships the four on-device model groups: ASR, Laya and EmbeddingGemma under
// `assets/models/**`, plus the KWS files under `assets/kws/**` (the latter are generated by
// :phone-app:perception:audio-android from the same pinned manifest). The model binaries are not in git;
// this build reads the tracked manifest `tools/scripts/p2/model-bundles.json` and resolves every entry from
// `-PbundledModelsDir` (default `<root>/../EDITH_model/k40`) or, for the KWS/tracked entries, from the
// repository root. Each artifact is checked for bytes and SHA-256 before it is copied, so a missing,
// truncated or tampered model fails the build instead of shipping an incomplete bundle.
//
// `-PwithoutBundledModels=true` is a development/CI-only switch: it omits `assets/models/**` and sets
// `BuildConfig.BUNDLED_MODELS=false`. It is forbidden for release variants (the variant task fails).

/** One model artifact declared by `tools/scripts/p2/model-bundles.json`. */
data class BundledModelSpec(
    val group: String,
    val role: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
    val source: File,
) {
    /** Tab-separated transport into [BundleModelAssets]; every field is a declared task input. */
    fun encode(): String = listOf(group, role, path, bytes.toString(), sha256, source.absolutePath)
        .joinToString("\t")
}

/** One license/declaration text copied to `assets/models/licenses/`. */
data class BundledLicenseSpec(val file: String, val source: File) {
    fun encode(): String = listOf(file, source.absolutePath).joinToString("\t")
}

private fun readBundledModelsJson(manifest: File): Map<*, *> {
    if (!manifest.isFile) throw GradleException("bundled model manifest is missing: ${manifest.absolutePath}")
    return try {
        JsonSlurper().parse(manifest) as? Map<*, *>
            ?: throw GradleException("bundled model manifest is not a JSON object: ${manifest.absolutePath}")
    } catch (error: GradleException) {
        throw error
    } catch (error: Exception) {
        throw GradleException(
            "cannot parse bundled model manifest ${manifest.absolutePath}: ${error.message}",
            error,
        )
    }
}

/**
 * Resolves `sourceRoot`/`sourcePath` from the manifest.
 *
 * `<bundledModelsDir>` is substituted with the build's model directory; every other root is relative to the
 * repository root. `sourcePath` is the logical `group/file` path, while `path` is the file name within that
 * group's directory, so the physical file is `sourceRoot/file`.
 */
private fun resolveBundledSource(
    repositoryRoot: File,
    bundledModelsDir: File,
    group: String,
    path: String,
    sourceRoot: String,
    sourcePath: String,
): File {
    val root = if (sourceRoot.startsWith("<bundledModelsDir>")) {
        val suffix = sourceRoot.removePrefix("<bundledModelsDir>").trimStart('/', '\\')
        if (suffix.isEmpty()) bundledModelsDir else bundledModelsDir.resolve(suffix)
    } else {
        repositoryRoot.resolve(sourceRoot)
    }
    val relative = sourcePath.removePrefix("$group/")
    if (relative != path) {
        throw GradleException(
            "bundled model manifest entry $group/$path has sourcePath=$sourcePath under sourceRoot=$sourceRoot; " +
                "expected sourcePath=$group/$path",
        )
    }
    return root.resolve(relative)
}

/**
 * The canonical 0.4 bundle id: SHA-256 over `group/path:sha256` for every entry, sorted by path and joined with
 * `\n` (no trailing newline). The committed manifest reproduces `f06c6a5b...5565`; a deliberate model/path change
 * must update `bundleId` to the newly derived value so Laya/Embedding receipts stop matching and re-materialize.
 */
private fun canonicalBundleId(specs: List<BundledModelSpec>): String {
    val canonical = specs
        .map { "${it.group}/${it.path}:${it.sha256}" }
        .sorted()
        .joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun readBundledModelSpecs(
    manifest: File,
    repositoryRoot: File,
    bundledModelsDir: File,
): Pair<String, List<BundledModelSpec>> {
    val json = readBundledModelsJson(manifest)
    val bundleId = (json["bundleId"] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("bundled model manifest has no bundleId: ${manifest.absolutePath}")
    val rawFiles = json["files"] as? List<*>
        ?: throw GradleException("bundled model manifest has no files array: ${manifest.absolutePath}")
    val specs = rawFiles.mapIndexed { index, raw ->
        val entry = raw as? Map<*, *>
            ?: throw GradleException(
                "bundled model manifest files[$index] is not an object: ${manifest.absolutePath}",
            )
        fun text(key: String): String = (entry[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "bundled model manifest files[$index].$key is missing: ${manifest.absolutePath}",
            )
        val group = text("group")
        val role = text("role")
        val path = text("path")
        val bytes = (entry["bytes"] as? Number)?.toLong()
            ?: throw GradleException(
                "bundled model manifest files[$index].bytes is missing: ${manifest.absolutePath}",
            )
        if (bytes < 0) {
            throw GradleException(
                "bundled model manifest files[$index].bytes is negative: ${manifest.absolutePath}",
            )
        }
        val sha256 = text("sha256").lowercase()
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) {
            throw GradleException(
                "bundled model manifest files[$index].sha256 is not a SHA-256 hex digest: ${manifest.absolutePath}",
            )
        }
        val sourceRoot = text("sourceRoot")
        val sourcePath = text("sourcePath")
        BundledModelSpec(
            group = group,
            role = role,
            path = path,
            bytes = bytes,
            sha256 = sha256,
            source = resolveBundledSource(
                repositoryRoot = repositoryRoot,
                bundledModelsDir = bundledModelsDir,
                group = group,
                path = path,
                sourceRoot = sourceRoot,
                sourcePath = sourcePath,
            ),
        )
    }
    val duplicates = specs.groupBy { "${it.group}/${it.path}" }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw GradleException(
            "bundled model manifest has duplicate entries: ${duplicates.sorted()}: ${manifest.absolutePath}",
        )
    }
    // The id is not just a label: Laya/Embedding receipts record it, and a digest-only file change must not reuse
    // the same id. Verify the pinned value against the canonical digest so a stale id fails the build.
    val derivedBundleId = canonicalBundleId(specs)
    if (derivedBundleId != bundleId) {
        throw GradleException(
            "bundled model manifest bundleId does not match its ${specs.size} entries: declared=$bundleId, " +
                "derived=$derivedBundleId. The id must change whenever a pinned path or SHA-256 changes, so " +
                "Laya/Embedding receipts re-materialize; restore the entry or set bundleId to the derived value.",
        )
    }
    return bundleId to specs
}

private fun readBundledLicenseSpecs(manifest: File, repositoryRoot: File): List<BundledLicenseSpec> {
    val json = readBundledModelsJson(manifest)
    val rawLicenses = json["licenses"] as? List<*> ?: return emptyList()
    val licenses = rawLicenses.mapIndexed { index, raw ->
        val entry = raw as? Map<*, *>
            ?: throw GradleException(
                "bundled model manifest licenses[$index] is not an object: ${manifest.absolutePath}",
            )
        val file = (entry["file"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "bundled model manifest licenses[$index].file is missing: ${manifest.absolutePath}",
            )
        if (file != File(file).name) {
            throw GradleException(
                "bundled model manifest licenses[$index].file must be a plain file name: $file",
            )
        }
        val source = (entry["source"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "bundled model manifest licenses[$index].source is missing: ${manifest.absolutePath}",
            )
        BundledLicenseSpec(file = file, source = repositoryRoot.resolve(source))
    }
    val duplicates = licenses.groupBy { it.file }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw GradleException("bundled model manifest has duplicate license files: ${duplicates.sorted()}")
    }
    return licenses
}

/** Development/CI-only escape hatch; release variants refuse it in [BundleModelAssets]. */
val withoutBundledModels = providers.gradleProperty("withoutBundledModels").orNull?.toBoolean() ?: false

val bundledModelsDir: File = providers.gradleProperty("bundledModelsDir")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.layout.projectDirectory.dir("../EDITH_model/k40").asFile)

val bundledModelsManifest: File = providers.gradleProperty("bundledModelsManifest")
    .map { rootProject.file(it) }
    .getOrElse(
        rootProject.layout.projectDirectory.file("tools/scripts/p2/model-bundles.json").asFile,
    )

val (bundledBundleId, bundledModelFiles) = readBundledModelSpecs(
    manifest = bundledModelsManifest,
    repositoryRoot = rootProject.layout.projectDirectory.asFile,
    bundledModelsDir = bundledModelsDir,
)

val bundledLicenseFiles = readBundledLicenseSpecs(
    manifest = bundledModelsManifest,
    repositoryRoot = rootProject.layout.projectDirectory.asFile,
)

/**
 * Every text that must be packaged under `assets/models/licenses/` (spec §6.5): the ten Task 1 texts plus the five
 * ASR/VAD/speaker declarations that had been retained in `tools/scripts/p2/licenses/` but never copied into the APK.
 * The set is fail-closed: dropping or adding a manifest entry fails [BundleModelAssets] and
 * [verifyBundledModelManifest] until this contract is updated.
 */
val requiredBundledLicenseFiles = setOf(
    "3D-Speaker-current-project-Apache-2.0.txt",
    "Apache-2.0-standard.txt",
    "asr-model-card.md",
    "asr-upstream-model-card.md",
    "gemma-prohibited-use-policy.txt",
    "gemma-terms-of-use.txt",
    "kws-model-card.md",
    "laya-model-card.md",
    "laya-sample-NOTICE.txt",
    "onnxruntime-v1.28.2-MIT.txt",
    "onnxruntime-v1.28.2-ThirdPartyNotices.txt",
    "sherpa-v1.13.8-Apache-2.0.txt",
    "silero-v5-MIT.txt",
    "silero-v5-model-card.md",
    "speaker-v1.0.1-model-card.md",
)

// The audio module generates `assets/kws/**`. A complete app build must not silently package none when the
// KWS source set is missing, so the app declares the requirement on the root project. The audio module reads
// this lazily at task execution, after all projects (including this one) have been configured.
rootProject.extensions.extraProperties.set("edithRequireBundledModels", !withoutBundledModels)

abstract class BundleModelAssets : DefaultTask() {
    @get:Input abstract val bundleId: Property<String>

    /** [BundledModelSpec.encode] strings, in manifest order. */
    @get:Input abstract val entries: ListProperty<String>

    /** [BundledLicenseSpec.encode] strings. */
    @get:Input abstract val licenseEntries: ListProperty<String>

    /** The exact spec §6.5 license/declaration set this task must package. */
    @get:Input abstract val requiredLicenseFiles: SetProperty<String>

    /** Every source file the entries point at, so content changes invalidate this task. */
    @get:InputFiles abstract val trackedSources: ConfigurableFileCollection

    @get:Input abstract val bundlingEnabled: Property<Boolean>
    @get:Input abstract val releaseVariant: Property<Boolean>
    @get:Input abstract val variantName: Property<String>
    @get:Internal abstract val buildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        val buildRoot = buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        check(output.toPath().toAbsolutePath().normalize().startsWith(buildRoot)) {
            "refusing to clear ${output.absolutePath}: it is outside ${buildRoot.toFile().absolutePath}"
        }

        if (!bundlingEnabled.get()) {
            if (releaseVariant.get()) {
                throw GradleException(
                    "-PwithoutBundledModels=true is forbidden for release builds (variant ${variantName.get()}). " +
                        "It is a development/CI-only switch and must never produce a shipped APK.",
                )
            }
            output.deleteRecursively()
            output.mkdirs()
            // AGP's incremental packager edits the existing APK in place. When the previous build packaged
            // the ~1.1 GB model bundle and this development build removes it, the deleted bytes stay in the
            // file as unreachable holes: measured 2026-10-26, the debug APK kept its 1,110,174,064-byte size
            // while containing zero `assets/models/**` entries, versus 90,579,232 bytes after deleting the
            // stale APK first. Remove this variant's stale APK before packaging so the development build is
            // genuinely light (this task always runs before the packaging task it feeds).
            val staleApkDirectory = buildDirectory.get().asFile.resolve("outputs/apk/${variantName.get()}")
            staleApkDirectory.listFiles { file ->
                file.isFile && file.extension.equals("apk", ignoreCase = true)
            }?.forEach { staleApk ->
                if (staleApk.delete()) {
                    logger.info("removed stale ${staleApk.name}; the development build packages no models")
                }
            }
            logger.lifecycle(
                "BUNDLED_MODELS=false: -PwithoutBundledModels=true omits assets/models/** for " +
                    "${variantName.get()} (development/CI build)",
            )
            return
        }

        output.deleteRecursively()
        val models = output.resolve("models")
        models.mkdirs()

        val manifestFiles = mutableListOf<Map<String, Any>>()
        entries.get().forEach { encoded ->
            val parts = encoded.split('\t')
            if (parts.size != 6) throw GradleException("internal bundled model entry is malformed: $encoded")
            val group = parts[0]
            val role = parts[1]
            val path = parts[2]
            val bytes = parts[3].toLongOrNull()
                ?: throw GradleException("internal bundled model entry has invalid bytes: $encoded")
            val expectedSha = parts[4]
            val source = File(parts[5])
            val assetPath = when (group) {
                "kws" -> "assets/kws/$path"
                "asr", "laya", "embeddinggemma" -> "assets/models/$group/$path"
                else -> throw GradleException("bundled model manifest declares unknown group '$group' for $path")
            }
            if (!source.isFile) {
                val kind = if (group == "kws") "KWS model file" else "bundled model file"
                throw GradleException(
                    "$kind is missing: ${source.absolutePath} (expected as $assetPath). Complete builds require " +
                        "every artifact declared in tools/scripts/p2/model-bundles.json; use " +
                        "-PwithoutBundledModels=true only for a development/CI build without bundled models.",
                )
            }
            val actualBytes = source.length()
            if (actualBytes != bytes) {
                throw GradleException(
                    "bundled model file ${source.absolutePath} has $actualBytes bytes, expected $bytes " +
                        "(declared in tools/scripts/p2/model-bundles.json)",
                )
            }
            val actualSha = sha256(source)
            if (actualSha != expectedSha) {
                throw GradleException(
                    "bundled model file ${source.absolutePath} has SHA-256 $actualSha, expected $expectedSha " +
                        "(declared in tools/scripts/p2/model-bundles.json)",
                )
            }
            if (group == "kws") return@forEach // audio-android packages assets/kws from these same files
            val destination = models.resolve(group).resolve(path)
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
            manifestFiles += linkedMapOf(
                "group" to group,
                "role" to role,
                "path" to "$group/$path",
                "bytes" to bytes,
                "sha256" to expectedSha,
            )
        }

        val licenseNames = mutableSetOf<String>()
        licenseEntries.get().forEach { encoded ->
            val parts = encoded.split('\t')
            if (parts.size != 2) throw GradleException("internal bundled license entry is malformed: $encoded")
            val file = parts[0]
            val source = File(parts[1])
            if (!licenseNames.add(file)) throw GradleException("duplicate bundled license file: $file")
            if (!source.isFile) {
                throw GradleException(
                    "bundled model license text is missing: ${source.absolutePath} (expected in " +
                        "assets/models/licenses/$file)",
                )
            }
            val destination = models.resolve("licenses").resolve(file)
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }

        // Fail-closed delivery contract: the APK must carry exactly the spec §6.5 texts, not just whatever the
        // tracked manifest happens to list. A silently removed entry would otherwise shrink the APK without error.
        val missingLicenses = requiredLicenseFiles.get() - licenseNames
        val unexpectedLicenses = licenseNames - requiredLicenseFiles.get()
        if (missingLicenses.isNotEmpty() || unexpectedLicenses.isNotEmpty()) {
            throw GradleException(
                "bundled model license set does not match spec §6.5: missing=${missingLicenses.sorted()}, " +
                    "unexpected=${unexpectedLicenses.sorted()}",
            )
        }
        if (manifestFiles.size != 15) {
            throw GradleException(
                "bundled model asset set must contain 15 emitted model files (KWS is packaged separately under " +
                    "assets/kws), was ${manifestFiles.size}",
            )
        }

        val manifest = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "bundleId" to bundleId.get(),
            "files" to manifestFiles,
        )
        models.resolve("models-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        logger.lifecycle(
            "BUNDLED_MODELS=true: packaged ${manifestFiles.size} model files and ${licenseNames.size} license " +
                "texts for ${variantName.get()} (bundle=${bundleId.get()})",
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

androidComponents.onVariants { variant ->
    val taskName = "bundle${variant.name.replaceFirstChar { it.uppercase() }}ModelAssets"
    val bundleTask = tasks.register<BundleModelAssets>(taskName) {
        group = "build"
        description = "Verifies and packages the pinned 0.4 on-device models into assets/models/**."
        bundleId.set(bundledBundleId)
        entries.set(bundledModelFiles.map { it.encode() })
        licenseEntries.set(bundledLicenseFiles.map { it.encode() })
        requiredLicenseFiles.set(requiredBundledLicenseFiles)
        trackedSources.from(
            bundledModelFiles.map { it.source },
            bundledLicenseFiles.map { it.source },
        )
        bundlingEnabled.set(!withoutBundledModels)
        releaseVariant.set(variant.buildType == "release")
        variantName.set(variant.name)
        buildDirectory.set(layout.buildDirectory)
        outputDirectory.set(layout.buildDirectory.dir("generated/bundledModelAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundleTask, BundleModelAssets::outputDirectory)
    variant.buildConfigFields?.put(
        "BUNDLED_MODELS",
        BuildConfigField<Boolean>(
            "boolean",
            !withoutBundledModels,
            "Whether this APK packages the verified 0.4 on-device model bundle (assets/models/**).",
        ),
    )
}

/**
 * Fast manifest audit: no model binaries are read and no 1.1 GB asset tree is generated. It checks the canonical
 * bundle id, the 21 pinned entries, the 15 emitted model files, and the exact 15-text spec §6.5 license set.
 *
 * `-PbundledModelsManifest=<path>` audits another manifest (used for the negative mutation checks) while applying
 * the same rules. The normal build already fails at configuration when the id does not match the canonical digest;
 * this task exists so the license/count contract can be verified and re-run cheaply.
 */
tasks.register("verifyBundledModelManifest") {
    group = "verification"
    description = "Verifies the pinned bundled-model manifest id and license contract without packaging assets."
    doLast {
        val derived = canonicalBundleId(bundledModelFiles)
        val emitted = bundledModelFiles.count { it.group != "kws" }
        val licenseNames = bundledLicenseFiles.map { it.file }.toSet()
        val missingLicenses = requiredBundledLicenseFiles - licenseNames
        val unexpectedLicenses = licenseNames - requiredBundledLicenseFiles
        val missingSources = bundledLicenseFiles.filterNot { it.source.isFile }.map { it.source.absolutePath }
        if (bundledBundleId != derived || bundledModelFiles.size != 21 || emitted != 15 ||
            missingLicenses.isNotEmpty() || unexpectedLicenses.isNotEmpty() || missingSources.isNotEmpty()
        ) {
            throw GradleException(
                "bundled model manifest audit failed: bundleId=$bundledBundleId derived=$derived " +
                    "entries=${bundledModelFiles.size} emitted=$emitted licenses=${licenseNames.size} " +
                    "missingLicenses=${missingLicenses.sorted()} unexpectedLicenses=${unexpectedLicenses.sorted()} " +
                    "missingSources=$missingSources",
            )
        }
        logger.lifecycle(
            "BUNDLED_MODEL_MANIFEST entries=${bundledModelFiles.size} emitted=$emitted " +
                "licenses=${licenseNames.size} bundleId=$bundledBundleId",
        )
        logger.lifecycle("BUNDLED_MODEL_MANIFEST_RESULT=PASS")
    }
}

// Run the cheap manifest/license audit as part of the normal `check` lifecycle.
tasks.matching { it.name == "check" }.configureEach {
    dependsOn("verifyBundledModelManifest")
}

// Fail before a release graph starts executing, not only when the release asset task happens to run: earlier
// release tasks (dependency resolution, native merge) can fail for unrelated reasons and would mask the
// forbidden-switch error. Only a release [BundleModelAssets] task in the graph triggers this, so a debug
// build with the development switch still configures and runs normally.
gradle.taskGraph.whenReady {
    if (!withoutBundledModels) return@whenReady
    val releaseBundle = allTasks.filterIsInstance<BundleModelAssets>().firstOrNull { it.releaseVariant.get() }
    if (releaseBundle != null) {
        throw GradleException(
            "-PwithoutBundledModels=true is forbidden for release builds (variant ${releaseBundle.variantName.get()}). " +
                "It is a development/CI-only switch and must never produce a shipped APK.",
        )
    }
}
