package com.sheen.rokid.phone.actions.deeplink

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.phone.actions.device.parseObject
import com.sheen.rokid.phone.actions.device.stringArg
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 0.5.4 Task 14 (spec §6.4): the one Android touch point of `deep_link`.
 *
 * The action is fixed at `ACTION_VIEW` by this port's signature - a caller cannot smuggle extras, a component, flags
 * or a different action through it. Implementations own the platform policy:
 * - pre-check with `resolveActivity` when a package is requested: no resolution is [DeepLinkLaunchResult.NoHandler],
 *   a resolution to another package is [DeepLinkLaunchResult.PackageMismatch];
 * - `FLAG_ACTIVITY_NEW_TASK` is always set, because the App launches from its application context;
 * - `ActivityNotFoundException` becomes [DeepLinkLaunchResult.NoHandler] and `SecurityException` becomes
 *   [DeepLinkLaunchResult.Refused]; neither escapes as a throw.
 */
interface DeepLinkLauncher {

    /** Launches one `ACTION_VIEW` intent for [uri], optionally bound to [packageName]. */
    fun launch(uri: String, packageName: String?): DeepLinkLaunchResult
}

/**
 * What the platform said about one deep-link launch.
 *
 * [Accepted.handlerPackage] is the package that will handle the URI when the platform could resolve one; it may be
 * `null` only when a launch with no requested package was accepted after an unresolved pre-check. The tool then has no
 * package to wait for and requires the foreground to change from its pre-launch value instead.
 */
sealed interface DeepLinkLaunchResult {
    data class Accepted(val handlerPackage: String?) : DeepLinkLaunchResult
    data class PackageMismatch(val actual: String) : DeepLinkLaunchResult
    data object NoHandler : DeepLinkLaunchResult
    data object Refused : DeepLinkLaunchResult
}

/** The production [DeepLinkLauncher]: resolves (when a package is requested) and starts one `ACTION_VIEW` intent. */
class ContextDeepLinkLauncher(private val context: Context) : DeepLinkLauncher {

    override fun launch(uri: String, packageName: String?): DeepLinkLaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        val resolvedPackage = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (packageName != null) {
            when {
                resolvedPackage == null -> return DeepLinkLaunchResult.NoHandler
                resolvedPackage != packageName -> return DeepLinkLaunchResult.PackageMismatch(resolvedPackage)
            }
        }
        return try {
            context.startActivity(intent)
            DeepLinkLaunchResult.Accepted(resolvedPackage ?: packageName)
        } catch (_: ActivityNotFoundException) {
            DeepLinkLaunchResult.NoHandler
        } catch (_: SecurityException) {
            DeepLinkLaunchResult.Refused
        }
    }
}

/**
 * 0.5.4 Task 14 (spec §6.4): `deep_link` - `ACTION_VIEW` for an http(s) App Link or a package-bound custom scheme.
 *
 * The launcher's `Accepted` is the **shell's** answer, not success. The tool reuses `OpenTool`'s post-launch pipeline:
 * wait up to 2 s for the requested package, then require the observed **foreground** and the accessibility **root** to
 * agree before it reports `Done`. Consequences, all of them deliberate:
 * - a package that never comes to front is `STATE_UNCHANGED` (the command was accepted; the state is what it is);
 * - a no-package launch has no requested package to wait for: a resolved handler must still reach the foreground inside
 *   the 2 s budget, and without a resolved handler the foreground must differ from the pre-launch read. Foreground and
 *   root agreeing is necessary but **not** sufficient - a BAL-silent launch leaves the pre-existing screen in front and
 *   would otherwise be reported as success;
 * - an http(s) link taken by another package is `BROWSER_FALLBACK` with the actual foreground package - the one honest
 *   answer when an App Link is not verified by its app, and never a silent component retry;
 * - an unreadable root (accessibility off or no root) is `UNVERIFIED`, never success, and `applied` stays unknown;
 * - `NO_HANDLER`/`PACKAGE_MISMATCH`/`LAUNCH_REFUSED` are refusals: nothing was dispatched.
 *
 * Audit/log carry **only** scheme, host and the requested package. The URI and its query are user data, so
 * `auditTargetArgument` is `null` and no log line formats the full URI.
 */
class DeepLinkTool(
    private val launcher: DeepLinkLauncher,
    /** The live foreground package, normally the accessibility port's answer. */
    private val foreground: () -> String? = { null },
    /** The accessibility root's package - the second witness a launch must reach. */
    private val rootPackage: () -> String? = { null },
    /**
     * Waits until [foreground] names the expected package, polling every 100 ms up to the caller's budget (2 s in
     * production). Tests inject this so no JVM test sleeps; the tool still re-reads [foreground] afterwards.
     */
    private val waitForForeground: (String, Long) -> Boolean = { pkg, timeoutMillis ->
        var remaining = timeoutMillis
        while (runCatching { foreground() }.getOrNull() != pkg && remaining > 0L) {
            Thread.sleep(FOREGROUND_POLL_MILLIS)
            remaining -= FOREGROUND_POLL_MILLIS
        }
        runCatching { foreground() }.getOrNull() == pkg
    },
) : ExecutionBackend {

    override val id = ID

    /** The intent channel has no unavailable state (the App can always build an `ACTION_VIEW`), so it is always up. */
    override fun available(): Boolean = true

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    /** The tool's own call shape (one argument object); the `ActionExecutor` contract adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) {
            return refusal(UNKNOWN_ACTION, "the deep_link tool only answers deep_link", recoverable = false)
        }
        val args = parseObject(argsJson) ?: return refusal(
            ARGUMENTS_INVALID,
            "deep_link needs a uri",
            recoverable = false,
        )
        val uri = args.stringArg(URI) ?: return refusal(
            ARGUMENTS_INVALID,
            "deep_link needs a uri string",
            recoverable = false,
        )
        // An optional member that is present but not a string is a malformed call, never a silently dropped package.
        if (args.containsKey(PACKAGE) && args.stringArg(PACKAGE) == null) {
            return refusal(ARGUMENTS_INVALID, "package must be a string", recoverable = false)
        }
        val requestedPackage = args.stringArg(PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
        return when (val request = DeepLinkPolicy.validate(uri, requestedPackage)) {
            is DeepLinkRequest.Invalid -> refusal(
                request.code,
                messageFor(request.code),
                recoverable = request.code == DeepLinkPolicy.PACKAGE_REQUIRED,
            )
            is DeepLinkRequest.Valid -> {
                audit(request)
                launchVerified(request)
            }
        }
    }

    /** Every refusal here happened before an intent was dispatched: `accepted=false`, `applied=false`. */
    private fun refusal(code: String, message: String, recoverable: Boolean): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = code,
            message = message,
            recoverable = recoverable,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

    /** Runs the policy-valid request and maps the launcher's answer to the tool's verified result. */
    private fun launchVerified(request: DeepLinkRequest.Valid): ActionResult {
        // Read the screen *before* dispatching: when the launcher cannot resolve a handler there is no package to wait
        // for, so a foreground that moved is the only evidence the ACTION_VIEW did anything at all.
        val foregroundBeforeLaunch = probeForeground()
        val launched = runCatching { launcher.launch(request.uri, request.packageName) }
            .getOrElse {
                // A port that throws must not become TOOL_THREW; nothing proves the intent reached Android.
                return refusal(LAUNCH_REFUSED, "the deep-link launch was refused", recoverable = true)
            }
        return when (launched) {
            DeepLinkLaunchResult.NoHandler -> refusal(
                NO_HANDLER,
                "no activity handles this URI" + (request.packageName?.let { " for $it" } ?: ""),
                recoverable = true,
            )
            is DeepLinkLaunchResult.PackageMismatch -> ActionResult(
                ActionOutcome.Refused,
                data(request, launched.actual, launched = false).toString(),
                stateChanging = true,
                execution = ToolExecution(
                    outcome = ToolOutcome.Refused,
                    code = PACKAGE_MISMATCH,
                    message = "the URI resolves to ${launched.actual}, not ${request.packageName}",
                    recoverable = true,
                    accepted = false,
                    applied = false,
                    verified = null,
                    changed = null,
                ),
            )
            DeepLinkLaunchResult.Refused -> refusal(
                LAUNCH_REFUSED,
                "the system refused the deep-link launch",
                recoverable = true,
            )
            is DeepLinkLaunchResult.Accepted -> verify(request, launched.handlerPackage, foregroundBeforeLaunch)
        }
    }

    /**
     * The 2 s verification pipeline, mirroring `OpenTool.launchEnvelope`.
     *
     * Root is checked first: without it there is no second witness, so the honest answer is [UNVERIFIED] whatever the
     * foreground reads. Only then is a package launch verified by `foreground == root == requested`, and an http(s)
     * link that landed on a different foreground package is [BROWSER_FALLBACK] rather than a fabricated success.
     * A no-package launch additionally needs evidence that something moved: the resolved handler must have been reached
     * by the wait, or - when nothing resolved - the foreground must differ from [foregroundBeforeLaunch].
     */
    private fun verify(
        request: DeepLinkRequest.Valid,
        handlerPackage: String?,
        foregroundBeforeLaunch: String?,
    ): ActionResult {
        val requestedPackage = request.packageName
        val expected = requestedPackage ?: handlerPackage
        val reached = expected != null &&
            runCatching { waitForForeground(expected, FOREGROUND_TIMEOUT_MILLIS) }.getOrDefault(false)
        val observedForeground = probeForeground()
        val observedRoot = probeRootPackage()
        if (observedRoot == null) {
            return failure(
                code = UNVERIFIED,
                message = "the accessibility root is unreadable, so the launch cannot be verified",
                data = data(request, handlerPackage, launched = true),
                applied = null,
                verified = null,
            )
        }
        if (requestedPackage == null) {
            // `foreground == root` is necessary but not sufficient: the pre-existing screen (home, keyguard,
            // another app) satisfies it too. Without a requested package, success also needs the resolved
            // handler to have come front, or - if nothing resolved - a foreground that moved since before launch.
            val grounded = observedForeground != null && observedForeground == observedRoot
            val launchProved = if (handlerPackage != null) {
                reached
            } else {
                foregroundBeforeLaunch != null && foregroundBeforeLaunch != observedForeground
            }
            return if (grounded && launchProved) {
                done(request, handlerPackage)
            } else {
                failure(
                    code = STATE_UNCHANGED,
                    message = "the launch command succeeded but the screen did not change in a verifiable way",
                    data = data(request, handlerPackage, launched = true),
                    applied = null,
                    verified = false,
                )
            }
        }
        val verified = reached && observedForeground == requestedPackage && observedRoot == requestedPackage
        if (verified) return done(request, handlerPackage)
        if (DeepLinkPolicy.isHttp(DeepLinkPolicy.auditTarget(request.uri).scheme) &&
            observedForeground != null &&
            observedForeground != requestedPackage
        ) {
            return failure(
                code = BROWSER_FALLBACK,
                message = "the http(s) link did not reach $requestedPackage; foreground was $observedForeground",
                data = data(request, handlerPackage, launched = true).with(
                    "foregroundPackage",
                    observedForeground,
                ),
                applied = null,
                verified = false,
            )
        }
        return failure(
            code = STATE_UNCHANGED,
            message = "the launch command succeeded but $requestedPackage is not verified in front",
            data = data(request, handlerPackage, launched = true),
            applied = null,
            verified = false,
        )
    }

    /** A verified launch is the only path with evidence for all four execution facts. */
    private fun done(request: DeepLinkRequest.Valid, handlerPackage: String?): ActionResult = ActionResult(
        ActionOutcome.Done,
        data(request, handlerPackage, launched = true).toString(),
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Done,
            accepted = true,
            applied = true,
            verified = true,
            changed = true,
        ),
    )

    /** A dispatched launch that failed post-verification: accepted, but `applied` stays unknown unless stated. */
    private fun failure(
        code: String,
        message: String,
        data: JsonObject,
        applied: Boolean?,
        verified: Boolean?,
    ): ActionResult = ActionResult(
        ActionOutcome.Failed,
        data.toString(),
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Failed,
            code = code,
            message = message,
            recoverable = true,
            accepted = true,
            applied = applied,
            verified = verified,
            changed = null,
        ),
    )

    /**
     * The model-facing tool-specific payload: exactly the spec's success fields (plus [foregroundPackage] on a browser
     * fallback). No screen text, no URI and no query.
     */
    private fun data(request: DeepLinkRequest.Valid, handlerPackage: String?, launched: Boolean): JsonObject {
        val target = DeepLinkPolicy.auditTarget(request.uri)
        return buildJsonObject {
            put("launched", launched)
            put("handlerPackage", if (handlerPackage != null) JsonPrimitive(handlerPackage) else JsonNull)
            put("scheme", target.scheme)
        }
    }

    /** Adds one count-free, content-free audit line; the URI/query/path never reach it. */
    private fun audit(request: DeepLinkRequest.Valid) {
        val target = DeepLinkPolicy.auditTarget(request.uri)
        P3Log.line(
            "P3_DEEPLINK scheme=${target.scheme} host=${target.host ?: "-"} " +
                "package=${request.packageName ?: "-"}",
        )
    }

    /** A throwing foreground port degrades the verification, never turns the call into TOOL_THREW. */
    private fun probeForeground(): String? = runCatching { foreground() }.getOrNull()

    private fun probeRootPackage(): String? = runCatching { rootPackage() }.getOrNull()

    private fun messageFor(code: String): String = when (code) {
        DeepLinkPolicy.INVALID_URI -> "the uri is not an absolute http(s) or package-bound custom-scheme URI"
        DeepLinkPolicy.SCHEME_NOT_ALLOWED ->
            "this scheme is not launchable; file/content/javascript/data/intent/android-app/tel/sms/smsto/mailto/package are refused"
        DeepLinkPolicy.PACKAGE_REQUIRED -> "a custom scheme needs a package"
        else -> "the deep link was refused"
    }

    private fun JsonObject.with(key: String, value: String?): JsonObject = buildJsonObject {
        this@with.forEach { (name, element) -> put(name, element) }
        put(key, if (value != null) JsonPrimitive(value) else JsonNull)
    }

    companion object {
        const val NAME = "deep_link"

        /** The stable execution-channel id; the intent channel never invents an unavailable state. */
        const val ID = "deeplink"

        private const val URI = "uri"
        private const val PACKAGE = "package"

        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
        private const val PACKAGE_MISMATCH = "PACKAGE_MISMATCH"
        private const val NO_HANDLER = "NO_HANDLER"
        private const val LAUNCH_REFUSED = "LAUNCH_REFUSED"
        private const val STATE_UNCHANGED = "STATE_UNCHANGED"
        private const val BROWSER_FALLBACK = "BROWSER_FALLBACK"
        private const val UNVERIFIED = "UNVERIFIED"

        /** How long a launch may take to put its package in front before the observation is treated as a failure. */
        private const val FOREGROUND_TIMEOUT_MILLIS = 2_000L

        /** The default `waitForForeground` poll interval. */
        private const val FOREGROUND_POLL_MILLIS = 100L

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "打开深链",
            description = "用 ACTION_VIEW 打开一个绝对 URI 深链：uri 必须是 http(s)，或带 package 的自定义 scheme。" +
                "长度超过 2048、含控制字符、非法或带 http(s) userinfo 返回 INVALID_URI；" +
                "file/content/javascript/data/intent/android-app/tel/sms/smsto/mailto/package 等伪 scheme 返回 SCHEME_NOT_ALLOWED；" +
                "自定义 scheme 缺少 package 返回 PACKAGE_REQUIRED。只做打开，不支持 extras/component/多 URI。" +
                "有 package 时先校验 handler：没有 handler 返回 NO_HANDLER，解析到别的包返回 PACKAGE_MISMATCH；" +
                "启动被系统拒绝返回 LAUNCH_REFUSED。启动后必须前台包与无障碍 root 都等于请求包才算成功；" +
                "http(s) 被浏览器接管返回 BROWSER_FALLBACK 并附实际前台包；前台不符返回 STATE_UNCHANGED；" +
                "无障碍关闭或 root 读不到返回 UNVERIFIED（不报成功）。只有用户明确要打开某个 App 内的深链或 App Link 时用它；" +
                "普通网页用 open_url，按应用名打开用 open。",
            parametersJson = """{"type":"object","properties":{""" +
                """"uri":{"type":"string","description":"绝对 URI 深链（http(s) 或带 package 的自定义 scheme）"},""" +
                """"package":{"type":"string","description":"目标 App 包名；自定义 scheme 必填"}},""" +
                """"required":["uri"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "device",
            // A URI is user data (tokens, query); only scheme/host/package may reach the audit/log line.
            auditTargetArgument = null,
            // Opening a deep link changes the screen, so a failed call still deserves the host's post-call observation.
            screenObservation = true,
        )
    }
}
