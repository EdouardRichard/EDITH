plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":plugin-api"))
    implementation(project(":output-runtime"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(project(":test-support"))
    testImplementation(project(":plugins-smart"))
    testImplementation(project(":plugins-interview"))
    testImplementation(project(":plugins-diagnostic"))
    testImplementation(project(":output-runtime"))
}
