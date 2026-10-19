package com.sheen.rokid.device

/**
 * Which channel capability [DeviceKernel.ensureReady] must verify.
 *
 * [OBSERVE] and [SCREENSHOT] need the accessibility service bound; [ACT] is the accessibility write
 * path; [SHELL] needs a shell port. The device-level checks (screen on, not locked) run before the
 * kind-specific channel check for every kind, because this version never unlocks anything
 * (spec §4.3).
 */
enum class ReadyKind { OBSERVE, ACT, SCREENSHOT, SHELL }

/**
 * The app the user is looking at, as reported by the accessibility channel.
 *
 * [packageName] is null when the channel cannot tell (no window, service race); [activity] is the
 * activity class as the platform reports it (often `.MainActivity`) or null when unknown.
 */
data class Foreground(val packageName: String?, val activity: String?)

/**
 * One raw screenshot taken by the accessibility service.
 *
 * [bytes] never leave this module's envelope as bytes: the kernel reports an `ImageRef` and the
 * artifact store that Task 10/13 wires in is what keeps the payload (spec §4.6/§5.3). [mime] is the
 * encoder output (`image/jpeg` for the platform's `takeScreenshot`), and [width]/[height] are the
 * pixel dimensions of the encoded image.
 */
data class Screenshot(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

/** One touch point in screen pixels. */
data class Point(val x: Int, val y: Int)

/**
 * One stroke of a synthetic gesture.
 *
 * [points] are screen pixels; a single point is a tap/long press. [startTimeMillis] is relative to
 * the start of the whole gesture (0 for the first stroke), which is the shape
 * `GestureDescription.StrokeDescription` wants. [durationMillis] is how long the finger stays on
 * the screen: a tap is a short stroke, a long press is one long stroke, and a double tap is two
 * short strokes at the same point with the second starting later.
 */
data class Stroke(val points: List<Point>, val startTimeMillis: Long, val durationMillis: Long)

/** A synthetic gesture: one or more [Stroke]s dispatched as one platform gesture. */
data class Gesture(val strokes: List<Stroke>)

/**
 * The accessibility channel, as the kernel needs it.
 *
 * The Android implementation lives in `phone-app/app` (Task 9); this module only ever sees this
 * interface, so it stays a pure JVM library. Implementations are called from the kernel's single
 * worker thread. The kernel bounds each operation with a timeout and interrupts the worker on
 * cancellation, but it cannot force a stuck implementation to return: the adapter owns making
 * platform calls interruptible/bounded.
 *
 * [observe] returns a producer snapshot; [Observation.revision] is ignored on input because the
 * kernel stamps its own revision. The filter is the producer's collection hint.
 *
 * [performNodeAction] takes one semantic action token from [DeviceActions]. Tokens are `click`,
 * `long_click`, `double_click`, `set_text`, `scroll_forward` and `scroll_backward`; an action that
 * needs an argument is encoded by [DeviceActions.encode], e.g. `set_text\u0000text=<text>`, and is
 * read back with [DeviceActions.decode]. `double_click` has no platform node action, so the adapter
 * must synthesize a real double tap at the node centre. `false` means the platform refused the
 * action for that node (not clickable, stale, disabled) — not that the channel is down.
 *
 * [dispatchGesture] returns false when the service cannot perform gestures (or the platform
 * rejected the gesture). [screenshot] returns null when the capability is unavailable.
 * [foreground] may report null fields when the channel has no answer.
 */
interface AccessibilityPort {

    fun isBound(): Boolean

    fun isInteractive(): Boolean

    fun isKeyguardLocked(): Boolean

    fun observe(filter: ScreenFilter): Observation

    fun performNodeAction(ref: Ref, action: String): Boolean

    fun dispatchGesture(gesture: Gesture): Boolean

    fun screenshot(): Screenshot?

    fun foreground(): Foreground
}

/**
 * The outcome of one shell command.
 *
 * [failure] is null when the command ran (even with a non-zero [exitCode]); otherwise it is the
 * adapter's error code (for example `SHELL_NOT_BOUND`), and the kernel reports it verbatim so the
 * recovery advice survives. [truncated] means the reply was cut at the caller's `maxBytes`.
 */
data class ShellOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val failure: String?,
)

/**
 * The Shizuku-style shell channel.
 *
 * [run] must return within [timeoutMillis] (the adapter enforces it), must read at most [maxBytes]
 * of output and must not throw for a failing command: failures go into [ShellOutcome.failure]
 * (and the exit code). `stdin` is not supported by this version.
 */
interface ShellPort {
    fun run(command: String, timeoutMillis: Long, maxBytes: Int): ShellOutcome
}

/**
 * The one vocabulary of semantic node actions the kernel sends through
 * [AccessibilityPort.performNodeAction].
 *
 * The platform exposes `click`, `long_click`, `set_text` and `scroll_forward/backward`; `double_click`
 * is this module's synthesis request (the adapter must dispatch a real double tap at the node
 * centre, because two platform clicks are not a double tap). Arguments travel in the same string,
 * because the port keeps a single `(ref, action)` signature: `key=value` pairs separated from the
 * action and from each other by [ARGUMENT_SEPARATOR]. Only [SET_TEXT] carries one today
 * (`text=<text>`); the value is passed through verbatim (it may contain `=`). Text cannot contain
 * [ARGUMENT_SEPARATOR] on Android, so the encoding is unambiguous.
 */
object DeviceActions {

    const val CLICK = "click"
    const val LONG_CLICK = "long_click"
    const val DOUBLE_CLICK = "double_click"
    const val SET_TEXT = "set_text"
    const val SCROLL_FORWARD = "scroll_forward"
    const val SCROLL_BACKWARD = "scroll_backward"

    /** Separates the action name from each `key=value` argument in the port's action string. */
    const val ARGUMENT_SEPARATOR = '\u0000'

    /** Builds the port action string: `action` when there are no arguments. */
    fun encode(action: String, arguments: Map<String, String>): String {
        if (arguments.isEmpty()) return action
        return buildString {
            append(action)
            arguments.forEach { (key, value) ->
                append(ARGUMENT_SEPARATOR).append(key).append('=').append(value)
            }
        }
    }

    /** Splits a port action string into its action token and its arguments. */
    fun decode(portAction: String): Pair<String, Map<String, String>> {
        val parts = portAction.split(ARGUMENT_SEPARATOR)
        val arguments = LinkedHashMap<String, String>()
        parts.drop(1).forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) {
                arguments[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
        return parts.first() to arguments
    }
}
