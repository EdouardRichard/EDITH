plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(project(":common:testkit:test-support"))
    testImplementation(project(":phone-app:plugins:plugin-smart-helper"))
    testImplementation(project(":phone-app:plugins:plugin-interview"))
    testImplementation(project(":phone-app:plugins:plugin-diagnostic"))
    testImplementation(project(":common:presentation:output-runtime"))
}
