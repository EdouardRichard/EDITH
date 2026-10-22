package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.plugin.DeviceProfile

/**
 * The synthetic device profile every prompt test shares (spec §4.2.1 rule 5: test fixtures are the
 * one place device-shaped literals are allowed; production prompt resources are not).
 *
 * Every value is deliberately generic and stable. `PromptSnapshotTest`'s golden hashes are computed
 * from exactly this profile, so changing a default here is a deliberate golden-hash change, not an
 * incidental test edit.
 *
 * The defaults are also the "fully populated" profile: every placeholder in
 * `context/device-profile.md` has a value, so the fidelity tests can null one field at a time and
 * observe the line disappear.
 */
internal fun profileFixture(
    manufacturer: String = "FixtureCorp",
    model: String = "FixturePhone",
    deviceName: String? = "FixtureDevice",
    androidRelease: String = "FixtureRelease",
    apiLevel: Int = 34,
    screenWidthPx: Int = 1000,
    screenHeightPx: Int = 2000,
    densityDpi: Int = 300,
    glassesModel: String? = "FixtureGlasses",
    glassesFirmware: String? = "FixtureFirmware",
    glassesWidthPx: Int? = 600,
    glassesHeightPx: Int? = 400,
    appVersion: String = "0.3.0-fixture",
    locale: String = "zh-CN",
    timeZone: String = "Asia/Shanghai",
    wakeWords: List<String> = listOf("fixture-wake"),
    capabilities: Set<String> = setOf("a11y", "shizuku", "tts"),
): DeviceProfile = DeviceProfile(
    manufacturer = manufacturer,
    model = model,
    deviceName = deviceName,
    androidRelease = androidRelease,
    apiLevel = apiLevel,
    screenWidthPx = screenWidthPx,
    screenHeightPx = screenHeightPx,
    densityDpi = densityDpi,
    glassesModel = glassesModel,
    glassesFirmware = glassesFirmware,
    glassesWidthPx = glassesWidthPx,
    glassesHeightPx = glassesHeightPx,
    appVersion = appVersion,
    locale = locale,
    timeZone = timeZone,
    wakeWords = wakeWords,
    capabilities = capabilities,
)

/**
 * Loads one prompt resource the way the catalog consumes it: UTF-8 with LF line endings.
 *
 * The checkout uses `core.autocrlf=true`, so the classpath text on a Windows working tree can be
 * CRLF while the catalog normalises to LF before hashing. Tests that compare authored text with a
 * plan must normalise too, otherwise the assertion depends on the checkout rather than the prompt.
 */
internal fun promptText(file: String): String =
    PromptDocument.load("prompts/$file").replace("\r\n", "\n").replace('\r', '\n').trim()

