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
}

// Emits a structurally valid EngineMetrics document so tools/p2 can verify the reporter contract
// without a device run. Output is generated fixture data, never T16 evidence.
tasks.register<JavaExec>("metricsDocument") {
    group = "verification"
    description = "Write a generated EngineMetrics document for reporter-contract checks"
    mainClass.set("edith.testsupport.MetricsDocumentFixture")
    classpath = sourceSets["main"].runtimeClasspath
    val outPath = providers.gradleProperty("outPath").orNull ?: "build/metrics-document.json"
    args("--out", layout.projectDirectory.file(outPath).asFile.absolutePath)
}

