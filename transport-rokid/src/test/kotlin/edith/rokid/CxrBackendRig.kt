package edith.rokid

import android.app.Activity
import com.rokid.cxr.session.*
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Stand-in for the vendor CXR SDK, shared by every test that drives [CxrBackend].
 *
 * The SDK types are Android AAR classes, so they are stood in for with dynamic proxies rather than fakes: a proxy
 * fails loudly if the backend starts calling a method the tests do not model, which is what keeps the tests honest
 * about the real callback signatures. Extracted to one place because a second copy would drift from them.
 */
internal class CxrBackendRig {
    val worker = CxrBackendTestWorker()
    val ui = ArrayDeque<() -> Unit>()
    val sends = mutableListOf<String>()
    /** Command name plus payload, for tests that care what was actually written to the wire. */
    val sentJson = mutableListOf<Pair<String, String>>()
    var sendSuccess = true
    var connects = 0; var closes = 0; var authorizationRequests = 0
    var auth: ((AuthResult) -> Unit)? = null
    var commandCallback: ICustomCmdSessionCallback? = null
    /** Recorded so a test can report session readiness the way the SDK does; nothing else fires the lifecycle. */
    var lifecycleCallback: ISessionLifecycleCbk? = null
    var duringCreate: () -> Unit = {}
    var duringAuthorization: () -> Unit = {}

    /**
     * The installer the backend must use for audio.
     *
     * It is recorded rather than faked away because the device evidence is precisely about *where* the callback gets
     * installed: the vendor's working implementation installs on the glasses client via `setCXRAudioCbk`, while
     * installing on the session wrapper measured zero bytes. A test that let the backend pick its own target could not
     * tell those apart.
     */
    val installer = RecordingAudioInstaller()

    /**
     * The client-level inbound custom-command subscription.
     *
     * Recorded for the same reason as [installer]: the device measurement is precisely about *which layer* serves the
     * inbound direction - the client callback is the one the vendor's reference uses, and the session registration
     * delivered nothing.
     */
    val commandInstaller = RecordingCustomCmdInstaller()

    /** What the session reports for `startAudioStream`. Defaults to success; tests override it to model a refusal. */
    var audioStreamStarts = true
    var audioStreamStartCalls = 0
    var audioStreamStopCalls = 0

    private val session = proxy<CxrSession> { method, args ->
        when (method) {
            "connect" -> { connects++; null }
            "close" -> { closes++; null }
            "addCustomCmdCallback" -> { commandCallback = args!![0] as ICustomCmdSessionCallback; null }
            "addLifecycleCallback" -> { lifecycleCallback = args!![0] as ISessionLifecycleCbk; null }
            // Modelled because the audio path depends on it: before this the proxy returned null and the backend
            // threw, which is why no test had ever driven audio start to completion.
            "startAudioStream" -> {
                audioStreamStartCalls++
                SessionResult<Unit>(if (audioStreamStarts) SessionErrorCode.OK else SessionErrorCode.LINK_NOT_READY, Unit, null)
            }
            "stopAudioStream" -> {
                audioStreamStopCalls++
                SessionResult<Unit>(SessionErrorCode.OK, Unit, null)
            }
            else -> null
        }
    }
    private val manager = proxy<CxrSessionManager> { method, args ->
        when (method) {
            "create" -> { duringCreate(); session }
            "requestAuthorization" -> {
                authorizationRequests++
                @Suppress("UNCHECKED_CAST")
                val callback = args!![2] as (AuthResult) -> Unit
                auth = callback; duringAuthorization(); null
            }
            // The SDK's own parser is what turns an activity result into an answer. Modelled only since review I-3 gave
            // the result path something to assert: before that nothing ever called it, because no test drove a result
            // through the activity.
            "parseAuthorizationResult" -> AuthResult(
                (args!![0] as Int) == Activity.RESULT_OK, "memory-only-test-token", null, null,
            )
            else -> null
        }
    }
    val backend = CxrBackend(RokidConnectionConfig("edith.glasses", "edith.glasses.Main", null, "test"),
        worker, { manager }, { _, action -> ui.add(action) }, { _, command, json -> sends.add(command); sentJson.add(command to json); sendSuccess },
        { installer }, { commandInstaller })

    fun authorize() {
        backend.connect(Activity(), CxrBackendTestEvents())
        ui.removeFirst().invoke()
        auth!!.invoke(AuthResult(true, "memory-only-test-token", null, null))
    }

    /**
     * Delivers one inbound payload the way the SDK would, through whichever layer the backend registered.
     *
     * The backend prefers the **client** callback (that is the layer the vendor's own reference uses and the one the
     * device measurement showed is served), and falls back to the session registration only when the client could not
     * be reached. Tests must therefore not assume which one is active - they deliver and let the rig route.
     */
    fun deliver(channel: String, payload: ByteArray?): Boolean {
        if (commandInstaller.deliver(channel, payload)) return true
        val session = commandCallback ?: return false
        session.onCustomCmdResult(channel, payload)
        return true
    }

    fun reply(channel: String, text: String) { deliver(channel, text.toByteArray()) }

    /**
     * The callback the SDK would be holding right now, whichever layer the backend chose.
     *
     * Returned as a plain function so a test can keep it across a teardown and prove a late vendor callback is inert -
     * that property must hold for the layer actually in use, not for a hard-coded one.
     */
    fun activeInboundCallback(): (String, ByteArray?) -> Unit {
        if (commandInstaller.installed) return { channel, payload -> commandInstaller.deliver(channel, payload) }
        val session = requireNotNull(commandCallback) { "no inbound callback was registered at all" }
        return { channel, payload -> session.onCustomCmdResult(channel, payload) }
    }

    /** Reports the session as open, the way the SDK does; without this nothing is ever ready. */
    fun ready() { lifecycleCallback!!.onSessionStarted() }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "controlled SDK boundary"
                else -> call(method.name, args)
            }
        } as T
}

/** Executor that runs inline and refuses work once closed, matching the backend's shutdown expectations. */
internal class CxrBackendTestWorker : Executor {
    var closed = false
    override fun execute(command: Runnable) {
        if (closed) throw RejectedExecutionException("synthetic closed executor")
        command.run()
    }
}

/**
 * Records the audio installation instead of performing it.
 *
 * Holding the events lets a test deliver audio the way the SDK would, so the backend's own routing - including that a
 * stale epoch is silenced - is exercised rather than assumed.
 */
internal class RecordingAudioInstaller : AudioInstaller {
    var installs = 0
    var releases = 0
    var installResult = true
    var events: CxrAudioAdapter.Events? = null

    override fun install(events: CxrAudioAdapter.Events): Boolean {
        installs++
        this.events = events
        return installResult
    }

    override fun release() {
        releases++
        events = null
    }

    /** Delivers one chunk as the SDK would, through the declared byte range. */
    fun deliver(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        events?.received(buffer.copyOfRange(offset, offset + length))
    }
}

internal class CxrBackendTestEvents : RokidBackend.Events {
    val readyCount = AtomicReference(0)
    override fun ready() { readyCount.set(readyCount.get() + 1) }
    override fun interrupted() = Unit
    override fun error(code: String) = Unit
}

/**
 * Records the inbound custom-command installation instead of performing it.
 *
 * Holding the sink lets a test deliver a reply exactly the way the client callback would, so the routing behind it is
 * exercised rather than assumed - and [installs] shows which layer the backend actually chose.
 */
internal class RecordingCustomCmdInstaller : CustomCmdInstaller {
    var installs = 0
    var releases = 0
    var installResult = true
    private var sink: ((String?, ByteArray?) -> Unit)? = null

    /** True while the backend is holding this layer, i.e. while its sink is installed. */
    val installed: Boolean get() = sink != null

    override fun install(sink: (String?, ByteArray?) -> Unit): Boolean {
        installs++
        this.sink = if (installResult) sink else null
        return installResult
    }

    override fun release() {
        releases++
        sink = null
    }

    /** Delivers one inbound payload as the client callback would. Returns false when nothing is installed. */
    fun deliver(key: String, payload: ByteArray?): Boolean {
        val target = sink ?: return false
        target(key, payload)
        return true
    }
}
