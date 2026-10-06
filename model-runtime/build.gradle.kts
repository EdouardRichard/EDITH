plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":plugin-api"))
    implementation(project(":storage-runtime"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
