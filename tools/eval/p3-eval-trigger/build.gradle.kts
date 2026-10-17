plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

application { mainClass.set("edith.probe.eval.MainKt") }
dependencyLocking { lockAllConfigurations() }

dependencies {
    // P3.5 W3: the offline admission calibration runs the plugin's **own** `admitByFeatures` over the frozen corpus,
    // so the number the report prints and the rule the plugin applies are the same code. Re-implementing the rule
    // here is the failure mode this dependency exists to prevent. `plugins-smart` is a plain JVM module and pulls no
    // Android artifact, so the CLI stays device-free.
    implementation(project(":phone-app:plugins:plugin-smart-helper"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
