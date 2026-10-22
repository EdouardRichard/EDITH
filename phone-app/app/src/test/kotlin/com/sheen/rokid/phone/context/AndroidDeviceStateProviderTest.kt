package com.sheen.rokid.phone.context

import com.sheen.rokid.device.Foreground
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.phone.app.HostCompositionRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2: the provider that turns platform facts into the plugin-facing `DeviceProfile`/`DeviceState`.
 *
 * Everything here is a pure-JVM fake: the point of [PlatformFacts] is that the mapping rules ("null stays null",
 * "the injected clock decides the time", "a broken fact never takes the provider down") can be pinned without an
 * emulator. The production Android half is exercised by compilation and by the composition-root test at the bottom.
 *
 * `fixedEpochMillis` is 2026-10-20T10:23:00Z, which is 18:23 on Tuesday 2026-10-20 in Asia/Shanghai - the values the
 * state test asserts, so an accidental second clock or a system-default zone cannot pass it.
 */
class AndroidDeviceStateProviderTest {

    private val fixedEpochMillis = 1_792_491_780_000L

    // ------------------------------------------------------------------ profile

    @Test
    fun `profile maps platform facts and keeps unavailable glasses fields null`() {
        val provider = AndroidDeviceStateProvider(
            FakeFacts(),
            clock = { 0L },
            timeZone = { "Asia/Shanghai" },
            locale = { "zh-CN" },
            wakeWords = { listOf("伊迪丝", "edith") },
        )

        val profile = provider.profile()

        assertEquals("Xiaomi", profile.manufacturer)
        assertEquals("M2012K11AC", profile.model)
        assertEquals("Xiaomi 11", profile.deviceName)
        assertEquals("13", profile.androidRelease)
        assertEquals(33, profile.apiLevel)
        assertEquals(1080, profile.screenWidthPx)
        assertEquals(2400, profile.screenHeightPx)
        assertEquals(440, profile.densityDpi)
        assertEquals("0.3", profile.appVersion)
        assertEquals("zh-CN", profile.locale)
        assertEquals("Asia/Shanghai", profile.timeZone)
        assertEquals(listOf("伊迪丝", "edith"), profile.wakeWords)
        assertEquals(setOf("a11y", "calendar", "usage_access"), profile.capabilities)
        assertNull("no transport fact means no glasses model, not a guessed one", profile.glassesModel)
        assertNull(profile.glassesFirmware)
        assertNull(profile.glassesWidthPx)
        assertNull(profile.glassesHeightPx)
    }

    @Test
    fun `profile carries glasses facts when the transport supplies them`() {
        val provider = AndroidDeviceStateProvider(
            FakeFacts(glassesModel = "Glasses-X", glassesFirmware = "1.2.3", glassesWidthPx = 640, glassesHeightPx = 480),
            clock = { 0L },
        )

        val profile = provider.profile()

        assertEquals("Glasses-X", profile.glassesModel)
        assertEquals("1.2.3", profile.glassesFirmware)
        assertEquals(640, profile.glassesWidthPx)
        assertEquals(480, profile.glassesHeightPx)
    }

    // -------------------------------------------------------------------- state

    @Test
    fun `state renders local date weekday and time from the injected clock and timezone`() {
        val provider = AndroidDeviceStateProvider(
            FakeFacts(batteryPercent = 76, network = "wifi"),
            clock = { fixedEpochMillis },
            timeZone = { "Asia/Shanghai" },
        )

        val state = provider.state()

        assertEquals(fixedEpochMillis, state.capturedAtEpochMillis)
        assertEquals("Asia/Shanghai", state.timeZone)
        assertEquals("2026-10-20", state.localDate)
        assertEquals("星期二", state.weekday)
        assertEquals("18:23", state.localTime)
        assertEquals(76, state.batteryPercent)
        assertEquals("wifi", state.network)
    }

    @Test
    fun `state maps every readable device fact`() {
        val provider = AndroidDeviceStateProvider(FakeFacts(), clock = { fixedEpochMillis })

        val state = provider.state()

        assertEquals(true, state.charging)
        assertEquals(true, state.screenOn)
        assertEquals(false, state.locked)
        assertEquals(120, state.brightness)
        assertEquals(40, state.volumePercent)
        assertEquals(true, state.imeVisible)
        assertEquals("com.example.app", state.foregroundPackage)
        assertEquals(".MainActivity", state.foregroundActivity)
        assertEquals(
            MediaState(
                state = "PLAYING",
                title = "Synthetic",
                artist = "Synthetic Artist",
                album = "Synthetic Album",
                positionMs = 42_000L,
                durationMs = 180_000L,
            ),
            state.media,
        )
        assertEquals(true, state.panelVisible)
        assertEquals(true, state.glassesConnected)
        assertEquals(true, state.glassesWearing)
    }

    @Test
    fun `state keeps a fact the platform cannot supply null instead of inventing one`() {
        val provider = AndroidDeviceStateProvider(
            FakeFacts(
                batteryPercent = null,
                charging = null,
                network = null,
                screenOn = null,
                locked = null,
                brightness = null,
                volumePercent = null,
                imeVisible = null,
                foregroundPackage = null,
                foregroundActivity = null,
                media = null,
                panelVisible = null,
                glassesConnected = null,
                glassesWearing = null,
            ),
            clock = { fixedEpochMillis },
        )

        val state = provider.state()

        assertNull(state.batteryPercent)
        assertNull(state.charging)
        assertNull(state.network)
        assertNull(state.screenOn)
        assertNull(state.locked)
        assertNull(state.brightness)
        assertNull(state.volumePercent)
        assertNull("no IME fact is not a hidden IME", state.imeVisible)
        assertNull("no foreground fact is not a guessed app", state.foregroundPackage)
        assertNull(state.foregroundActivity)
        assertNull("no media session is not a stopped or unknown one", state.media)
        assertNull(state.panelVisible)
        assertNull("an unestablished link is not a disconnection inferred here", state.glassesConnected)
        assertNull("no wearing event is not 'not wearing'", state.glassesWearing)
    }

    // ------------------------------------------------------------- degradation

    @Test
    fun `a throwing fact cannot take the provider down`() {
        val provider = AndroidDeviceStateProvider(
            object : FakeFacts() {
                override fun glassesModel(): String? = error("no glasses transport")
                override fun batteryPercent(): Int? = error("battery service died")
                override fun charging(): Boolean? = error("battery service died")
                override fun network(): String? = error("connectivity service died")
                override fun capabilities(): Set<String> = error("capability probe died")
                override fun imeVisible(): Boolean? = error("ime probe died")
                override fun foregroundPackage(): String? = error("accessibility service died")
                override fun foregroundActivity(): String? = error("accessibility service died")
                override fun media(): MediaState? = error("media session manager died")
                override fun panelVisible(): Boolean? = error("glasses panel died")
                override fun glassesConnected(): Boolean? = error("glasses link died")
                override fun glassesWearing(): Boolean? = error("wearing probe died")
            },
            clock = { fixedEpochMillis },
            timeZone = { "Asia/Shanghai" },
            wakeWords = { error("wake registry unavailable") },
        )

        val profile = provider.profile()
        val state = provider.state()

        assertNull(profile.glassesModel)
        assertTrue("an unreadable capability probe is no advertised capability", profile.capabilities.isEmpty())
        assertTrue("an unreadable word registry is no word list", profile.wakeWords.isEmpty())
        assertNull(state.batteryPercent)
        assertNull(state.charging)
        assertNull(state.network)
        assertNull(state.imeVisible)
        assertNull(state.foregroundPackage)
        assertNull(state.foregroundActivity)
        assertNull("a dead media binder is no media fact", state.media)
        assertNull(state.panelVisible)
        assertNull(state.glassesConnected)
        assertNull(state.glassesWearing)
        // The facts that still answer are still rendered, so one broken probe degrades one field, not the request.
        assertEquals("Xiaomi", profile.manufacturer)
        assertEquals("2026-10-20", state.localDate)
        assertEquals("星期二", state.weekday)
        assertEquals("18:23", state.localTime)
    }

    @Test
    fun `an unreadable required fact becomes an absence marker instead of a guess`() {
        val provider = AndroidDeviceStateProvider(
            object : FakeFacts() {
                override fun model(): String = error("Build.MODEL unavailable")
                override fun apiLevel(): Int = error("SDK_INT unavailable")
                override fun screenWidthPx(): Int = error("window metrics unavailable")
                override fun appVersion(): String = error("version unavailable")
            },
            clock = { 0L },
        )

        val profile = provider.profile()

        // The contract's required fields are non-null by type, so a failed read can only be signalled in-band. These
        // markers are absences, not device facts: no model, API level, width or version is invented.
        assertEquals("", profile.model)
        assertEquals(0, profile.apiLevel)
        assertEquals(0, profile.screenWidthPx)
        assertEquals("", profile.appVersion)
        assertEquals("the other facts are unaffected", "Xiaomi", profile.manufacturer)
    }

    // ------------------------------------------------------------------ factory

    @Test
    fun `the production factory reads the host lambdas into state`() {
        val provider = androidDeviceStateProvider(
            android.content.ContextWrapper(null),
            capabilities = { setOf("a11y") },
            wakeWords = { listOf("edith") },
            foreground = { Foreground("com.example.app", ".MainActivity") },
            imeVisible = { true },
            media = { MediaState("PAUSED", "Synthetic") },
            panelVisible = { true },
            glassesConnected = { false },
            glassesWearing = { true },
        )

        val state = provider.state()
        val profile = provider.profile()

        assertEquals("com.example.app", state.foregroundPackage)
        assertEquals(".MainActivity", state.foregroundActivity)
        assertEquals(true, state.imeVisible)
        assertEquals(MediaState("PAUSED", "Synthetic"), state.media)
        assertEquals(true, state.panelVisible)
        assertEquals("an established link can be false, which is still a fact", false, state.glassesConnected)
        assertEquals(true, state.glassesWearing)
        assertEquals("the live capability lambda is forwarded, not replaced", setOf("a11y"), profile.capabilities)
        assertEquals(listOf("edith"), profile.wakeWords)
    }

    // ------------------------------------------------------------------ wiring

    @Test
    fun `the production composition binds a device state provider`() {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            // The capability panel names only capabilities the environment cannot answer. A missing
            // `Capability.DeviceState` here means `create` never put the provider in the environment's map.
            assertFalse(
                "the composition must answer Capability.DeviceState",
                root.state.value.capabilityReasons.containsKey(Capability.DeviceState),
            )
        } finally {
            root.close()
        }
    }
}

/**
 * Pure-JVM [PlatformFacts] with the K40-shaped defaults.
 *
 * It is `open` so a single test can make one fact throw and prove the provider degrades instead of propagating; the
 * mappings are the production ones, only the platform half is replaced.
 */
private open class FakeFacts(
    model: String = "M2012K11AC",
    glassesModel: String? = null,
    glassesFirmware: String? = null,
    glassesWidthPx: Int? = null,
    glassesHeightPx: Int? = null,
    batteryPercent: Int? = 76,
    charging: Boolean? = true,
    network: String? = "wifi",
    screenOn: Boolean? = true,
    locked: Boolean? = false,
    brightness: Int? = 120,
    volumePercent: Int? = 40,
    imeVisible: Boolean? = true,
    foregroundPackage: String? = "com.example.app",
    foregroundActivity: String? = ".MainActivity",
    media: MediaState? = MediaState(
        state = "PLAYING",
        title = "Synthetic",
        artist = "Synthetic Artist",
        album = "Synthetic Album",
        positionMs = 42_000L,
        durationMs = 180_000L,
    ),
    panelVisible: Boolean? = true,
    glassesConnected: Boolean? = true,
    glassesWearing: Boolean? = true,
) : PlatformFacts {

    private val modelFact = model
    private val glassesModelFact = glassesModel
    private val glassesFirmwareFact = glassesFirmware
    private val glassesWidthFact = glassesWidthPx
    private val glassesHeightFact = glassesHeightPx
    private val batteryPercentFact = batteryPercent
    private val chargingFact = charging
    private val networkFact = network
    private val screenOnFact = screenOn
    private val lockedFact = locked
    private val brightnessFact = brightness
    private val volumePercentFact = volumePercent
    private val imeVisibleFact = imeVisible
    private val foregroundPackageFact = foregroundPackage
    private val foregroundActivityFact = foregroundActivity
    private val mediaFact = media
    private val panelVisibleFact = panelVisible
    private val glassesConnectedFact = glassesConnected
    private val glassesWearingFact = glassesWearing

    override fun manufacturer(): String = "Xiaomi"
    override fun model(): String = modelFact
    override fun deviceName(): String? = "Xiaomi 11"
    override fun androidRelease(): String = "13"
    override fun apiLevel(): Int = 33
    override fun screenWidthPx(): Int = 1080
    override fun screenHeightPx(): Int = 2400
    override fun densityDpi(): Int = 440
    override fun glassesModel(): String? = glassesModelFact
    override fun glassesFirmware(): String? = glassesFirmwareFact
    override fun glassesWidthPx(): Int? = glassesWidthFact
    override fun glassesHeightPx(): Int? = glassesHeightFact
    override fun appVersion(): String = "0.3"
    override fun capabilities(): Set<String> = setOf("a11y", "calendar", "usage_access")
    override fun batteryPercent(): Int? = batteryPercentFact
    override fun charging(): Boolean? = chargingFact
    override fun network(): String? = networkFact
    override fun screenOn(): Boolean? = screenOnFact
    override fun locked(): Boolean? = lockedFact
    override fun brightness(): Int? = brightnessFact
    override fun volumePercent(): Int? = volumePercentFact
    override fun imeVisible(): Boolean? = imeVisibleFact
    override fun foregroundPackage(): String? = foregroundPackageFact
    override fun foregroundActivity(): String? = foregroundActivityFact
    override fun media(): MediaState? = mediaFact
    override fun panelVisible(): Boolean? = panelVisibleFact
    override fun glassesConnected(): Boolean? = glassesConnectedFact
    override fun glassesWearing(): Boolean? = glassesWearingFact
}
