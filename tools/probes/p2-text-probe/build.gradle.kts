plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

application { mainClass.set("edith.probe.MainKt") }
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":phone-app:assistant:model-runtime"))
    implementation(project(":phone-app:persistence:storage-runtime"))
    implementation(project(":common:contracts:plugin-api"))
    implementation(libs.okhttp)
}
