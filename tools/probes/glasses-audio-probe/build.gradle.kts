plugins {
    id("com.android.application")
}

android {
    namespace = "edith.glasses.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "edith.glasses.probe"
        // The glasses run Android 12 (RG-glasses). The CXR-S bridge requires minSdk >= 28.
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    // The uplink frame codec lives in audio-runtime so the glasses sender and the phone receiver share exactly one
    // implementation; two copies would eventually disagree on the wire format.
    implementation(project(":phone-app:perception:audio-runtime"))
    // CXR-S: the glasses-side bridge carrying the uplink. Pinned and hash-verified in the root build.
    implementation("edith.vendor.cxr:cxr-service-bridge:1.0-20260715.121510-107@aar")
    testImplementation("junit:junit:4.13.2")
}
