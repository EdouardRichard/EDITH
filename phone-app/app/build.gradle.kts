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
        versionCode = 3
        versionName = "0.3.0-preview"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":common:testkit:test-support"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
