plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    implementation(project(":common:contracts:core-contracts"))
    implementation(project(":common:contracts:plugin-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":common:testkit:test-support"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
dependencyLocking { lockAllConfigurations() }
