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
    // The passive loop (Task 13) lives in agent-runtime, and the hint assembly in output-runtime. Both are already on
    // phone-app's runtime classpath, so this adds no new external artifact and does not disturb any lockfile.
    implementation(project(":agent-runtime"))
    implementation(project(":output-runtime"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}

