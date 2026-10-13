plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}


android {
    namespace = "edith.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "edith.phone"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "edith.phone.PhoneTestRunner"
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
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":plugin-api"))
    implementation(project(":host-runtime"))
    implementation(project(":storage-runtime"))
    implementation(project(":model-runtime"))
    implementation(project(":audio-runtime"))
    implementation(project(":audio-android"))
    implementation(project(":transport-rokid"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":output-runtime"))
    implementation(project(":plugins-smart"))
    // P3.3 Task 1: the shell-UID execution channel (spec §17.2). `provider` is what the `api` binds to at runtime.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // edith-0.2 Phase A: LiteRT for on-device Laya + EmbeddingGemma inference
    implementation(libs.litert)
    implementation(project(":plugins-interview"))
    debugImplementation(project(":plugins-diagnostic"))
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
    testImplementation(project(":test-support"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(project(":test-support"))
}
dependencyLocking { lockAllConfigurations() }

// Compose mapping is optional metadata and its unpublished producer is unavailable offline.
tasks.matching {
    it.name == "produceDebugComposeMapping" ||
        it.name == "reportDebugComposeMappingErrors" ||
        it.name == "mergeDebugComposeMapping"
}.configureEach { enabled = false }
