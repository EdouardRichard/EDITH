package com.sheen.rokid.phone.bridge
import android.app.Activity
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.transport.RokidConnectionConfig

/**
 * The CXR glasses application a session starts.
 *
 * The default is **our own** glasses app (`edith.glasses`), because display acceptance requires the glasses side to
 * render the hint: the vendor demo (`com.rokid.cxr.custom.demo`) only logs payloads, and the T17 audio probe
 * (`edith.glasses.probe`) captures and uplinks audio. Each of those is still reachable through the override below,
 * which is how the T17 runs pointed the session at the probe without editing this pairing.
 *
 * Changing the default is what makes a user-driven session exercise our rendering path at all; the history of what
 * the earlier audio measurements were taken against is preserved in the evidence records, not by freezing this value.
 */
object CxrGlassesTarget {
    const val GLASSES_PACKAGE_NAME = "com.sheen.rokid.glasses"
    const val GLASSES_ACTIVITY_NAME = "com.sheen.rokid.glasses.app.GlassesActivity"
    const val GLASSES_FIRMWARE = "1.26.011-20260929-150201"

    /** System-property keys an operator sets to point the session at a different glasses application. */
    const val TARGET_PACKAGE_PROPERTY = "edith.cxr.glassesPackage"
    const val TARGET_ACTIVITY_PROPERTY = "edith.cxr.glassesActivity"
    const val TARGET_APK_PROPERTY = "edith.cxr.glassesApk"

    fun config(): RokidConnectionConfig = configFrom(
        readProperty = { key -> System.getProperty(key) },
    )

    /**
     * Builds the pairing from an injected property reader.
     *
     * The target is overridable because the T17 audio uplink needs the session pointed at **our own** glasses
     * application, while the frozen P2 acceptance pairing must stay the default and must stay unchanged. An override
     * is explicit, visible in the recorded configuration, and reverts by unsetting a property - unlike editing the
     * constants, which would silently change what the acceptance evidence refers to.
     *
     * A partial override is refused rather than half-applied: a package with the wrong activity would make the SDK
     * launch the wrong screen, and that failure looks like a connection problem rather than a configuration mistake.
     */
    fun configFrom(readProperty: (String) -> String?): RokidConnectionConfig {
        val packageName = readProperty(TARGET_PACKAGE_PROPERTY)?.takeIf { it.isNotBlank() }
        val activityName = readProperty(TARGET_ACTIVITY_PROPERTY)?.takeIf { it.isNotBlank() }
        val apkPath = readProperty(TARGET_APK_PROPERTY)?.takeIf { it.isNotBlank() }
        if (packageName == null) return default()
        require(activityName != null) { "CXR_TARGET_ACTIVITY_REQUIRED_WITH_PACKAGE" }
        return RokidConnectionConfig(
            glassesPackageName = packageName,
            glassesActivityName = activityName,
            glassesApkPath = apkPath,
            firmware = GLASSES_FIRMWARE)
    }

    private fun default(): RokidConnectionConfig = RokidConnectionConfig(
        glassesPackageName = GLASSES_PACKAGE_NAME,
        glassesActivityName = GLASSES_ACTIVITY_NAME,
        glassesApkPath = null,
        firmware = GLASSES_FIRMWARE)
}

/**
 * What the phone's uplink receiver counted. Counters and the declared geometry only - never the samples, because a
 * device test needs to know whether frames arrived and were lost, not to read the audio.
 */
data class UplinkReport(
    val framesAccepted: Long,
    val framesLost: Long,
    val duplicates: Long,
    val malformed: Long,
    val gaps: Long,
    val overflowed: Boolean,
    val declaredSampleRateHz: Int?,
    val declaredChannelCount: Int?,
    val endedWith: String?,
) {
    /** True once any frame has been accepted, which is the only proof that the uplink reaches the phone. */
    val receivedAnything: Boolean get() = framesAccepted > 0

    /** One line for the device report, so the numbers can be compared with the glasses side. */
    fun summary(): String = "accepted=$framesAccepted lost=$framesLost duplicates=$duplicates " +
        "malformed=$malformed gaps=$gaps overflowed=$overflowed " +
        "sampleRate=${declaredSampleRateHz ?: "none"} channels=${declaredChannelCount ?: "none"} " +
        "endedWith=${endedWith ?: "none"}"
}

/** Narrow seam the composition root needs from a live CXR connection. */
interface CxrConnection : AutoCloseable {
    fun connect(activity: Any)

    /**
     * True once the SDK has reported a live session for this connection. The App must never read
     * readiness from its own capability map: connecting is asynchronous, so only the transport knows.
     */
    val ready: Boolean

    /**
     * Delivers the AI-App authorization result the SDK asked the activity for, and reports the **verdict the
     * transport's own parse of that result produced**.
     *
     * `null` means the result was not this connection's to answer: the authorization callback armed by `connect` is no
     * longer pending (review I-3). `onActivityResult` fires for every result this activity starts for - including the
     * App's own `permissions.launch(...)` dialogs - so a caller that reported all of them as "the SDK's authorization
     * answer" invented a fact.
     *
     * A non-null value is the transport's [CxrAuthorizationOutcome], derived from the vendor SDK's own
     * `AuthResult` rather than from the bare result code (review Minor 6): the vendor's parser reports a failure for a
     * `RESULT_OK` result whose `auth_result` extra it does not recognise, or whose token is missing/blank, and the page
     * must repeat that instead of announcing "眼镜授权确认已完成". The transport knows what the SDK parsed; the App may
     * only repeat what it says.
     */
    fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome?

    /**
     * Sends a hint command that expects no correlated reply; answers are matched by their own payload instead.
     *
     * Returns false when there is no live session, which the caller must treat as a refusal rather than a queue.
     */
    fun sendCommand(json: String): Boolean

    /**
     * Registers the consumer for inbound hint traffic on the shared reply channel.
     *
     * The channel is shared with the audio uplink, so the [RokidConnection] implementation discriminates by content
     * (its framing is self-identifying) rather than assuming the channel is exclusively one or the other.
     */
    fun onInboundCommand(listener: (String) -> Unit)

    /**
     * The live transport's own [com.sheen.rokid.plugin.InputPort], or null when this connection is not a real transport.
     *
     * The composition arms the CXR audio epoch through this port as well as through its own poll loop (Task 15c D1):
     * opening the ingress and asking the glasses for the stream happens only inside `RokidSessionTransport.start`,
     * so a composition that started just the loop polled an ingress nothing had opened and no PCM ever arrived.
     *
     * A member with a default rather than an extension function, so a test double can answer it; the default is the
     * cast the previous extension performed, which keeps every existing `CxrConnection` double answering null.
     */
    fun inputPortOrNull(): com.sheen.rokid.plugin.InputPort? = (this as? RokidConnection)?.input
}

/** Thrown when a wiring caller passes something that is not an [Activity] where one is required. */
class CxrActivityRequired : IllegalStateException("CXR_CONNECT_REQUIRES_ACTIVITY")

internal fun Any.asCxrActivity(): Activity = this as? Activity ?: throw CxrActivityRequired()
