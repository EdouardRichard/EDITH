# EDITH P0 Throwaway Probe Assets

These assets belong to the approved P0-PROBE-r1 feasibility spike. They are not EDITH production modules.

- `vendor/CxrlSample-1.1.2.zip`: official downloaded sample archive.
- `vendor/cxrl-1.1.2/CxrlSample`: extracted official source, unchanged from the archive; generated Gradle/build files are local outputs.
- `vendor/client-l-1.1.2.aar`: public CXR-L artifact checked against its published module SHA-256.
- `vendor/cxr-service-bridge-1.0-20260715.121510-107.aar`: the timestamped bridge version required by CXR-L 1.1.2.
- `vendor/*-classes.jar`: extracted public API signatures for local inspection.

The source sample builds with Gradle 9.4.1, AGP 9.2.1, Java 21 for the daemon and JVM target 17. Its existing 48 JVM tests passed. No APK has been installed or run in this checkpoint.

The sample authorizes through Rokid AI App and keeps the returned token in memory. Do not add AK/SK, tokens, raw audio, biometric data or personal resumes to this directory.

Build logs are in `D:\Project_new\EDITH\probes\logs`. The authoritative approval, exact commands, hashes, limitations and next device step are in `D:\Project_new\EDITH\docs\project\P0_PROGRESS.md` and `D:\Project_new\EDITH\docs\project\evidence\2026-10-05-p0-sdk-sample-audit.md`.
