// Throwaway S1 device probe (0.24). Same AGP/Gradle generation as the repository so the device behaviour is the
// platform's, not a second toolchain's. No external dependencies: every API used here is in the Android platform.
plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "com.sheen.rokid.glasses.overlayspike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sheen.rokid.glasses.overlayspike"
        // The glasses run Android 12 (YodaOS-Sprite); the family is >= 28 like the other glasses modules.
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "1.0-s1"
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
