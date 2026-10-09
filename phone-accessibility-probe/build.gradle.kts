plugins { id("com.android.application") }

android {
    namespace = "edith.phone.a11yprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "edith.phone.a11yprobe"
        // The product target is K70 / Android 16; the debug phone is a K40 / API 33. Both are >= 28.
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
    // Platform APIs only: this probe must measure what an AccessibilityService can do, not what a library can.
    testImplementation("junit:junit:4.13.2")
}
