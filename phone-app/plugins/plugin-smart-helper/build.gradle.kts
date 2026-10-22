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
    // The passive loop (Task 13) lives in agent-runtime, and the hint assembly in output-runtime. Both are already on
    // phone-app's runtime classpath, so this adds no new external artifact and does not disturb any lockfile.
    //
    // `api` for `agent-runtime`: Phase 5 put `ConversationStore` into `SmartAssistantPlugin`'s **constructor**, so an
    // agent-runtime type is now part of this module's public API and every consumer that builds the plugin (phone-app's
    // `SmartWiring`) must be able to resolve it. `implementation` would hide it and fail that compilation.
    api(project(":phone-app:assistant:agent-runtime"))
    implementation(project(":common:presentation:output-runtime"))
    // The proactive turn (Task 8) parses the one tool call it accepts (`propose_hint`'s arguments) with the same
    // parser the loop uses. Already on this module's runtime classpath through `:agent-runtime`, so no new artifact.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    // Phase 7 F4 JVM gate: `MemoryMeasurementRequestShapeTest` hands the request the real `ModelClientGateway` builds
    // to model-runtime's `ContextBudgeter` (the send-time acceptance check), so the A/B forms are proven sendable
    // before any device run. Test-only: `:model-runtime` depends on `core-contracts`, `plugin-api`, `agent-runtime` and
    // `storage-runtime` only — there is no cycle — and this edge adds nothing to phone-app's runtime classpath and
    // touches no lockfile (plugins-smart has none; model-runtime's own dependencies are unchanged).
    testImplementation(project(":phone-app:assistant:model-runtime"))
}

