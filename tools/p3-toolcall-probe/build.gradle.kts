plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

application { mainClass.set("edith.probe.toolcall.MainKt") }
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":model-runtime"))
    implementation(project(":storage-runtime"))
    implementation(project(":plugin-api"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
