plugins { id("com.android.application") }

// TEMPORARY throwaway probe (survey only, 2026-10-16). It exists to answer one question with a measurement instead of
// an assumption: what does a command actually look like when it runs *inside* Shizuku's UserService process, as opposed
// to `adb shell` on the PC? Not a delivery module; it is uninstalled and the module directory removed after the survey.
android {
    namespace = "edith.survey.shizukuprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "edith.survey.shizukuprobe"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // Same as phone-app: `aidl` is what compiles `IProbeShell`, the interface the UserService is reached through.
    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // The same pinned artifacts the delivery app uses (`gradle/libs.versions.toml`: shizuku = "13.1.5"), so the probe
    // measures the API surface the project actually ships rather than a newer one.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
