package com.sheen.rokid.plugin

/**
 * Static device profile: stable for a session, rendered into the system prompt (spec §3.2, §4.2.1).
 *
 * Every value is a platform fact read by the host. A fact the platform cannot supply stays `null` in the profile and
 * is omitted from the rendered prompt — it must never be filled with a default model, `unknown` or a guess.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val deviceName: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val glassesModel: String?,
    val glassesFirmware: String?,
    val glassesWidthPx: Int?,
    val glassesHeightPx: Int?,
    val appVersion: String,
    val locale: String,
    val timeZone: String,
    val wakeWords: List<String>,
    val capabilities: Set<String>,
)

/**
 * The media session the device is playing (edith-0.3 Phase 2, spec §3.2/§7.2).
 *
 * [state] is one of `PLAYING`, `PAUSED`, `STOPPED` or `UNKNOWN`; metadata the platform does not report stays `null`.
 * The same type rides on [DeviceState] and on [PerceptionSnapshot] so the two views cannot disagree about its shape.
 */
data class MediaState(
    val state: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

/**
 * Dynamic device state: refreshed per request, rendered into the request tail only (spec §3.2, §4.2.1).
 *
 * Unavailable fields stay `null`; nothing here is guessed or defaulted.
 */
data class DeviceState(
    val capturedAtEpochMillis: Long,
    val timeZone: String,
    val localDate: String,
    val weekday: String,
    val localTime: String,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val screenOn: Boolean? = null,
    val locked: Boolean? = null,
    val brightness: Int? = null,
    val volumePercent: Int? = null,
    val imeVisible: Boolean? = null,
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
    /** edith-0.3 Phase 2: the current media session, when the host can read one. */
    val media: MediaState? = null,
    /** Whether the glasses panel is currently visible; `null` when the host cannot tell. */
    val panelVisible: Boolean? = null,
    /** Whether the glasses link is established; `null` when the host cannot tell. */
    val glassesConnected: Boolean? = null,
    /** Whether the wearer is wearing the glasses; `null` when the host cannot tell. */
    val glassesWearing: Boolean? = null,
)

/** Read-only source of platform device facts, reached through [Capability.DeviceState]. */
interface DeviceStateProvider {
    fun profile(): DeviceProfile
    fun state(): DeviceState
}
