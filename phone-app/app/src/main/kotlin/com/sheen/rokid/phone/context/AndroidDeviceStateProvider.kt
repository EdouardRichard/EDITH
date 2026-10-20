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
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
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
}

/**
 * `DeviceStateProvider` over platform facts (Task 2).
 *
 * Three rules are the reason this class exists rather than reading Android inline at the call site:
 *
 * 1. **No invented facts.** A null platform fact stays null in [DeviceState]; the glasses fields stay null until
 *    the transport exposes handshake data. Missing values are omitted by the renderer, never replaced with
 *    `unknown` or a default (spec §4.2.1 rule 2).
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
 * The production provider: real Android platform facts plus the host's own capability probes.
 *
 * The composition passes [capabilities] and [wakeWords] as lambdas because both are live reads of objects that only
 * the composition owns (channel readiness, the wake-word registry). They are evaluated on every `profile()` call, so
 * a capability that came up after assembly is reported by the next session and never cached into a stale list.
 *
 * @param capabilities platform capability names the host can answer right now (`a11y`, `shizuku`, ...).
 * @param wakeWords the host wake-word registry's current word list.
 */
internal fun androidDeviceStateProvider(
    context: Context,
    capabilities: () -> Set<String> = { emptySet() },
    wakeWords: () -> List<String> = { emptyList() },
): DeviceStateProvider = AndroidDeviceStateProvider(
    // The application context outlives the activity and owns no window; a null base (JVM assembly tests) is kept as
    // given so construction itself never throws.
    facts = AndroidPlatformFacts(context.applicationContext ?: context, capabilities),
    wakeWords = wakeWords,
)

/**
 * [PlatformFacts] over the real Android APIs (spec §4.2.1's "数据来源" column).
 *
 * Each method wraps its platform call so the interface's "unavailable means null" rule holds even when a vendor
 * service throws. Nothing here reads a serial number, an IMEI or any other identifier: the profile is built from
 * manufacturer/model/release/screen values only.
 *
 * Phase 1 deliberately leaves `imeVisible`, `foregroundPackage` and `foregroundActivity` null: the window-backed IME
 * state and the foreground app belong to the Phase 2 perception provider, and guessing them here would put a made-up
 * fact into the prompt. The glasses fields stay null until the transport exposes its handshake.
 */
internal class AndroidPlatformFacts(
    private val context: Context,
    private val capabilities: () -> Set<String>,
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

    override fun capabilities(): Set<String> = read { capabilities() }.orEmpty()

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

    /** Phase 1: no window token is owned by the host, so the IME's visibility is not a fact it can report. */
    override fun imeVisible(): Boolean? = null

    /** Phase 1: the foreground app is the perception provider's fact (Phase 2), not a profile guess. */
    override fun foregroundPackage(): String? = null

    override fun foregroundActivity(): String? = null

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
