plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":plugin-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":test-support"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}
