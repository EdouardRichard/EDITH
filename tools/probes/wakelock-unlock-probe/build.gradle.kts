plugins { id("com.android.application") }

android {
    namespace = "edith.phone.wakelockprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "edith.phone.wakelockprobe"
        // Throwaway D5 spike on the Redmi K40 / Android 13 (the acceptance target, D46).
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
    // Platform APIs only: this probe measures what the app itself can do, not what a library can.
}
