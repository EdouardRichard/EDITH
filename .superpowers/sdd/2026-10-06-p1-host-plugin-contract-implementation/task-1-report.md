# Task 1 Report: Kotlin Module Baseline

Date: 2026-10-06 (Asia/Shanghai)
Plan: `docs/superpowers/plans/2026-10-06-p1-host-plugin-contract-implementation.md`
Task: 1, establish the Kotlin/JVM multi-module baseline and smoke test.

## Delivered

- Added the Gradle Kotlin DSL root project and module settings.
- Added `gradle/libs.versions.toml` with Kotlin 2.2.20 and kotlinx-coroutines 1.10.2 coordinates.
- Added six JVM modules: `core-contracts`, `plugin-api`, `host-runtime`, `test-support`, `plugins-smart`, and `plugins-interview`.
- Added shared JUnit-platform test task configuration at the root.
- Added `core-contracts/src/test/kotlin/edith/ModuleSmokeTest.kt`, which instantiates a synthetic contract value.
- Kept all modules platform-neutral: no Android, Rokid, network, ASR, VAD, speaker, model, TTS, or storage provider dependencies.

## Verification

The required red-test attempt was run before the Gradle baseline existed. It failed because `JAVA_HOME` and `java` were not available on PATH in the shell. After selecting the installed Android Studio JBR explicitly, the first project build exposed that a JDK 17 toolchain is not installed locally. The baseline therefore runs Gradle with the verified installed JBR 21.0.10 and emits JVM 17-compatible bytecode for Java and Kotlin tasks.

Command:

```powershell
$env:JAVA_HOME='D:\JetBrains\Android Studio\jbr'
& 'C:\Users\Richard\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' test --stacktrace
```

Result: `BUILD SUCCESSFUL in 9s`; `ModuleSmokeTest > testRuntimeCanInstantiateAContractValue() PASSED`. The five modules without tests correctly report `NO-SOURCE`.

Verified toolchain:

- Gradle 9.4.1
- Launcher/daemon JVM: JetBrains JBR 21.0.10
- Kotlin Gradle plugin declared in the catalog: 2.2.20
- Java/Kotlin bytecode target: JVM 17

## Scope and concerns

This task establishes build structure only. It does not implement the C01-C05 contracts, host state machine, test fakes, or built-in plugin behavior; those remain Tasks 2-5. The local machine has JBR 21 but no separately discoverable JDK 17, so a future environment with only JDK 17 should be checked before changing the toolchain declaration.

Commit: recorded by the parent agent after review.
