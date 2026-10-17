plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    implementation(project(":phone-app:assistant:agent-runtime"))
    implementation(project(":phone-app:persistence:storage-runtime"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
