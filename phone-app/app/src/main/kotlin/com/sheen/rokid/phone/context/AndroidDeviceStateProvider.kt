package com.sheen.rokid.phone.context

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.MediaState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The platform half of [AndroidDeviceStateProvider] (0.3 spec §3.2, §4.2.1).
 *
 * Every method answers one device fact and nothing else. A fact the platform cannot supply is `null`; the
 * non-null profile fields (the contract's required strings/ints) cannot be null by type, so the Android
 * implementation returns an **absence marker** (`""`/`0`) on a failed read - never a made-up model, locale or
 * version. Everything Android-specific lives behind this seam so the mapping rules stay testable on the JVM.
 */
internal interface PlatformFacts {
    fun manufacturer(): String
    fun model(): String
    fun deviceName(): String?
    fun androidRelease(): String
    fun apiLevel(): Int
    fun screenWidthPx(): Int
    fun screenHeightPx(): Int
    fun densityDpi(): Int
    fun glassesModel(): String?
    fun glassesFirmware(): String?
    fun glassesWidthPx(): Int?
    fun glassesHeightPx(): Int?
    fun appVersion(): String
    fun capabilities(): Set<String>
    fun batteryPercent(): Int?
    fun charging(): Boolean?
    fun network(): String?
    fun screenOn(): Boolean?
    fun locked(): Boolean?
    fun brightness(): Int?
    fun volumePercent(): Int?
    fun imeVisible(): Boolean?
    fun foregroundPackage(): String?
    fun foregroundActivity(): String?
    fun media(): MediaState?
    fun panelVisible(): Boolean?
    fun glassesConnected(): Boolean?
    fun glassesWearing(): Boolean?
}

/**
 * `DeviceStateProvider` over platform facts (Task 2).
 *
 * Three rules are the reason this class exists rather than reading Android inline at the call site:
 *
 * 1. **No invented facts.** A null platform fact stays null in [DeviceState]: an unreadable probe, an absent media
 *    session and a wear sensor that has not reported yet are all `null`, never replaced with `unknown` or a default
 *    (spec §4.2.1 rule 2).
 * 2. **One clock.** [profile] and [state] read time exclusively through the injected [clock]/[timeZone]. [state] does
 *    not call `System.currentTimeMillis()` a second time, so `capturedAtEpochMillis`, the local date and the local
 *    time all describe one instant - the request's.
 * 3. **Degrade per fact.** Every fact read is wrapped in `runCatching`: a broken battery service, a throwing
 *    accessibility probe or a dead wake-word registry costs that one field, never the request or the turn.
 *
 * `profile()` is read once at session activation (stable for the session); `state()` is read per request and is
 * request-scoped (spec §3.2, §4.2.1).
 *
 * @param clock epoch millis, injected so a test can pin the rendered time.
 * @param timeZone IANA zone id of the rendered local time.
 * @param locale BCP-47 language tag.
 * @param wakeWords the host wake-word registry's current words.
 */
internal class AndroidDeviceStateProvider(
    private val facts: PlatformFacts,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeZone: () -> String = { java.util.TimeZone.getDefault().id },
    private val locale: () -> String = { Locale.getDefault().toLanguageTag() },
    private val wakeWords: () -> List<String> = { emptyList() },
) : DeviceStateProvider {

    override fun profile(): DeviceProfile {
        // A failed zone read falls back to the process zone; a valid injected zone is always used as given. The raw
        // string is normalised through ZoneId so the profile and the state name the same zone id.
        val zone = runCatching { ZoneId.of(timeZone()) }.getOrElse { ZoneId.systemDefault() }
        return DeviceProfile(
            manufacturer = stringFact { facts.manufacturer() },
            model = stringFact { facts.model() },
            deviceName = nullableFact { facts.deviceName() },
            androidRelease = stringFact { facts.androidRelease() },
            apiLevel = intFact { facts.apiLevel() },
            screenWidthPx = intFact { facts.screenWidthPx() },
            screenHeightPx = intFact { facts.screenHeightPx() },
            densityDpi = intFact { facts.densityDpi() },
            glassesModel = nullableFact { facts.glassesModel() },
            glassesFirmware = nullableFact { facts.glassesFirmware() },
            glassesWidthPx = nullableFact { facts.glassesWidthPx() },
            glassesHeightPx = nullableFact { facts.glassesHeightPx() },
            appVersion = stringFact { facts.appVersion() },
            locale = stringFact { locale() },
            timeZone = zone.id,
            wakeWords = runCatching { wakeWords() }.getOrElse { emptyList() },
            capabilities = runCatching { facts.capabilities() }.getOrElse { emptySet() },
        )
    }

    override fun state(): DeviceState {
        val capturedAt = clock()
        val zone = runCatching { ZoneId.of(timeZone()) }.getOrElse { ZoneId.systemDefault() }
        val local = Instant.ofEpochMilli(capturedAt).atZone(zone)
        return DeviceState(
            capturedAtEpochMillis = capturedAt,
            timeZone = zone.id,
            localDate = local.format(LOCAL_DATE),
            weekday = WEEKDAYS[local.dayOfWeek.value - 1],
            localTime = local.format(LOCAL_TIME),
            batteryPercent = nullableFact { facts.batteryPercent() },
            charging = nullableFact { facts.charging() },
            network = nullableFact { facts.network() },
            screenOn = nullableFact { facts.screenOn() },
            locked = nullableFact { facts.locked() },
            brightness = nullableFact { facts.brightness() },
            volumePercent = nullableFact { facts.volumePercent() },
            imeVisible = nullableFact { facts.imeVisible() },
            foregroundPackage = nullableFact { facts.foregroundPackage() },
            foregroundActivity = nullableFact { facts.foregroundActivity() },
            // 0.3 Phase 2: the perception facts a plugin's device view needs. Each one is a live host read like every
            // other fact here; a probe that cannot answer leaves the field null rather than guessing.
            media = nullableFact { facts.media() },
            panelVisible = nullableFact { facts.panelVisible() },
            glassesConnected = nullableFact { facts.glassesConnected() },
            glassesWearing = nullableFact { facts.glassesWearing() },
        )
    }

    /** A required string fact; a failed read is the empty absence marker, not a guessed value. */
    private fun stringFact(read: () -> String): String = runCatching(read).getOrNull().orEmpty()

    /** A required int fact; a failed read is 0, which the renderer can treat as unmeasured. */
    private fun intFact(read: () -> Int): Int = runCatching(read).getOrDefault(0)

    private fun <T : Any> nullableFact(read: () -> T?): T? = runCatching(read).getOrNull()

    private companion object {
        val LOCAL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        /** `DayOfWeek.value` is 1 (Monday) through 7 (Sunday); the prompt is Chinese, so the label is too. */
        val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    }
}

/**
 * The production provider: real Android platform facts plus the host's own perception probes.
 *
 * The composition passes [capabilities], [wakeWords] and the six perception facts as lambdas because each is a live read
 * of an object that only the composition owns (channel readiness, the wake-word registry, the accessibility port, the
 * CXR link, the media-session manager). They are evaluated on every `profile()`/`state()` call, so a channel that came
 * up after assembly is reported by the next request and never cached into a stale value.
 *
 * Every perception lambda defaults to `{ null }`: a provider assembled without one (the JVM assembly tests) reports
 * that fact as unknown instead of pretending it read something. The production factory in `HostCompositionRoot` is the
 * only caller that wires all six.
 *
 * @param capabilities platform capability names the host can answer right now (`a11y`, `shizuku`, ...).
 * @param wakeWords the host wake-word registry's current word list.
 * @param foreground the accessibility port's current foreground app, or `null` while the service is off.
 * @param imeVisible whether an input-method window is up, or `null` while that cannot be read.
 * @param media the current media session, or `null` when there is none/notification access is missing.
 * @param panelVisible whether the glasses panel is visible right now.
 * @param glassesConnected whether the CXR session is established.
 * @param glassesWearing the wear sensor's last fact, or `null` before any wearing event.
 */
internal fun androidDeviceStateProvider(
    context: Context,
    capabilities: () -> Set<String> = { emptySet() },
    wakeWords: () -> List<String> = { emptyList() },
    foreground: () -> Foreground? = { null },
    imeVisible: () -> Boolean? = { null },
    media: () -> MediaState? = { null },
    panelVisible: () -> Boolean? = { null },
    glassesConnected: () -> Boolean? = { null },
    glassesWearing: () -> Boolean? = { null },
): DeviceStateProvider = AndroidDeviceStateProvider(
    // The application context outlives the activity and owns no window; a null base (JVM assembly tests) is kept as
    // given so construction itself never throws.
    facts = AndroidPlatformFacts(
        context.applicationContext ?: context,
        capabilities,
        foregroundProbe = foreground,
        imeProbe = imeVisible,
        mediaProbe = media,
        panelProbe = panelVisible,
        connectedProbe = glassesConnected,
        wearingProbe = glassesWearing,
    ),
    wakeWords = wakeWords,
)

/**
 * [PlatformFacts] over the real Android APIs (spec §4.2.1's "数据来源" column).
 *
 * Each method wraps its platform call so the interface's "unavailable means null" rule holds even when a vendor
 * service throws. Nothing here reads a serial number, an IMEI or any other identifier: the profile is built from
 * manufacturer/model/release/screen values only.
 *
 * The perception facts (0.3 Phase 2, Task 2) are not Android calls of their own: the composition owns their live
 * sources - the accessibility port for the foreground app and the IME window list, the CXR link for the glasses
 * session and panel, the media-session source, and the host's wear sensor - because only the composition can see
 * them. This class forwards those probes and keeps the "a probe that throws costs that one fact" rule, so a disabled
 * accessibility service or a revoked notification-listener grant cannot take the whole state down.
 */
internal class AndroidPlatformFacts(
    private val context: Context,
    // Deliberately not named `capabilities`: that would shadow the `capabilities()` override below, and the read would
    // resolve to the function itself. The recursion was swallowed by `read`'s runCatching and the profile advertised an
    // empty capability set - pinned now by `the production factory reads the host lambdas into state`.
    private val capabilitiesProbe: () -> Set<String>,
    private val foregroundProbe: () -> Foreground?,
    private val imeProbe: () -> Boolean?,
    private val mediaProbe: () -> MediaState?,
    private val panelProbe: () -> Boolean?,
    private val connectedProbe: () -> Boolean?,
    private val wearingProbe: () -> Boolean?,
) : PlatformFacts {

    override fun manufacturer(): String = read { Build.MANUFACTURER }.orEmpty()

    override fun model(): String = read { Build.MODEL }.orEmpty()

    override fun deviceName(): String? = read {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }?.trim()?.takeIf { it.isNotEmpty() }

    override fun androidRelease(): String = read { Build.VERSION.RELEASE }.orEmpty()

    override fun apiLevel(): Int = read { Build.VERSION.SDK_INT } ?: 0

    override fun screenWidthPx(): Int = read { windowSize().first } ?: 0

    override fun screenHeightPx(): Int = read { windowSize().second } ?: 0

    override fun densityDpi(): Int = read { context.resources.displayMetrics.densityDpi } ?: 0

    /** Phase 1: the CXR transport exposes no handshake model yet, so there is no fact to report. */
    override fun glassesModel(): String? = null

    override fun glassesFirmware(): String? = null

    override fun glassesWidthPx(): Int? = null

    override fun glassesHeightPx(): Int? = null

    override fun appVersion(): String = read { BuildConfig.VERSION_NAME }.orEmpty()

    override fun capabilities(): Set<String> = read { capabilitiesProbe() }.orEmpty()

    override fun batteryPercent(): Int? = read {
        batteryManager()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // `BATTERY_PROPERTY_CAPACITY` is documented to return `Int.MIN_VALUE` when unsupported.
            ?.takeIf { it in 0..100 }
    }

    override fun charging(): Boolean? = read { batteryManager()?.isCharging }

    override fun network(): String? = read { networkName() }

    override fun screenOn(): Boolean? = read {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
    }

    override fun locked(): Boolean? = read {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked
    }

    override fun brightness(): Int? = read {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }

    override fun volumePercent(): Int? = read { musicVolumePercent() }

    /** The window list belongs to the accessibility channel; a service that is off answers `null` upstream. */
    override fun imeVisible(): Boolean? = read { imeProbe() }

    /** The foreground app is the accessibility port's fact; a disconnected service answers `null` upstream. */
    override fun foregroundPackage(): String? = read { foregroundProbe()?.packageName }

    override fun foregroundActivity(): String? = read { foregroundProbe()?.activity }

    /** The media-session source answers only while notification access lets it read sessions. */
    override fun media(): MediaState? = read { mediaProbe() }

    /** The CXR link owns whether the resident panel is up. */
    override fun panelVisible(): Boolean? = read { panelProbe() }

    /** Whether a session has been introduced to the glasses (the CXR link's own established state). */
    override fun glassesConnected(): Boolean? = read { connectedProbe() }

    /** The wear sensor's last reported value; `null` until the glasses report one. */
    override fun glassesWearing(): Boolean? = read { wearingProbe() }

    /**
     * The current window bounds on API 30+, the display metrics below it. `currentWindowMetrics` from a non-activity
     * context can throw on some vendor builds, so the older, always-present display metrics are the fallback.
     */
    private fun windowSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.currentWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun networkName(): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = manager.activeNetwork ?: return "offline"
        val network = manager.getNetworkCapabilities(active) ?: return "offline"
        return when {
            network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            // A transport outside the contract's vocabulary is not guessed into one of the four names.
            else -> null
        }
    }

    private fun musicVolumePercent(): Int? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current < 0) return null
        return (current * 100 / max).coerceIn(0, 100)
    }

    private fun batteryManager(): BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private fun <T> read(fact: () -> T?): T? = runCatching(fact).getOrNull()
}
