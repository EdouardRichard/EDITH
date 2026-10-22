plugins { id("com.android.application") }

android {
    namespace = "edith.glasses.coexistence"
    compileSdk = 35

    defaultConfig {
        applicationId = "edith.glasses.coexistence"
        // The glasses run Android 12 (YodaOS-Sprite); the CXR-S bridge requires minSdk >= 28.
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
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
    // CXR-S: the glasses-side bridge. The same public bytes the phone side and the vendor's own glasses sample use.
    implementation("edith.vendor.cxr:cxr-service-bridge:1.0-20260715.121510-107@aar")
    // The Glasses-side open SDK. This is the *second* SDK line in this app and the whole point of the probe: the
    // offline wake-word service (`GlassSdk.getGlassOfflineCmdService()`) belongs to it, not to the CXR-S bridge.
    implementation("com.rokid.security:glass3.open.sdk:2.2.0-E") { exclude(group = "org.slf4j") }
    testImplementation("junit:junit:4.13.2")
}
