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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
