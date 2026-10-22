package com.sheen.rokid.phone.actions.device

import android.provider.Settings
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.LaunchResolution
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R43/spec §4.11: `open` — a name, package, URL or settings page, launched through the shell.
 *
 * The result is projected by the registry onto the unified envelope:
 *  - ambiguity is `Refused` with `execution.code = APP_AMBIGUOUS` and
 *    `data.candidates = [{"label":...,"packageName":...}]` — never a `Done`, and never parsed out of
 *    a `CODE:payload` detail string (R20);
 *  - an unknown name is `APP_NOT_FOUND`; a package-shaped token counts as a package when the catalog knows it, and
 *    after name resolution fails it is still opened by package when the shell confirms it installed (R105: the
 *    catalog is a `PackageManager` view, so a system app can be launchable and absent from it). An uninstalled one is
 *    `APP_NOT_FOUND`;
 *  - `list=true` enumerates the **launchable** set from the shell's launcher activity query (R104: user and system
 *    apps), falling back to every package when that query is unusable - never to the old third-party-only filter;
 *  - a package launch resolves its launcher component first
 *    (`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`), then
 *    `am start -W -n <component>`; no component falls back to the launcher intent;
 *  - `relaunch=false` with the target already in front does not start it again;
 *  - `am start`'s `Status: ok` alone is **not** success (0.3 Phase 2 Task 8): after the shell accepts a launch, the
 *    resolved package must be the foreground app **and** the accessibility root, or the call is a recoverable
 *    `STATE_UNCHANGED` whose observed values are `observation`/`execution` facts (Task 8 moved them off `data`). A
 *    URL/settings page knows no target package, so its observed foreground app must equal the accessibility root
 *    instead.
 *
 * A URL is restricted to `http(s)` and single-quote-escaped before it enters the command, so a URL
 * containing a quote or a shell metacharacter stays one literal argument. The settings-page table is
 * the same ten-page allowlist `IntentActionExecutor.open_settings` uses; it is duplicated here
 * (rather than launching through the intent channel) because R43 routes `open`'s launches through
 * the shell, and the brief explicitly permits an equivalent table.
 */
class OpenTool(
    private val shell: PrivilegedShell?,
    catalog: AppCatalog,
    private val foreground: () -> String? = { null },
    /** The foreground activity class, reported beside [foreground] for the model's post-launch view. */
    private val foregroundActivity: () -> String? = { null },
    /** The accessibility root's package - the second witness a package launch must reach. */
    private val rootPackage: () -> String? = { null },
    /**
     * Waits until [foreground] names [pkg], polling every 100 ms up to the caller's budget (2 s in production).
     *
     * A launch is asynchronous: `am start -W` returning is not the same as the new activity owning the foreground. The
     * default poll, and the injected tests' fake, only synchronize the observation; [launchEnvelope] still requires the
     * poll to have reached the package **and** the observed foreground + accessibility root to agree. Tests inject this
     * so no JVM test sleeps for two seconds.
     */
    private val waitForForeground: (String, Long) -> Boolean = { pkg, timeoutMillis ->
        var remaining = timeoutMillis
        while (foreground() != pkg && remaining > 0L) {
            Thread.sleep(FOREGROUND_POLL_MILLIS)
            remaining -= FOREGROUND_POLL_MILLIS
        }
        foreground() == pkg
    },
    /**
     * 0.3 Phase 2.1 Task 4 / Phase 2.2 Task 5: the automatic capture beside a post-call observation, or `Disabled`
     * when this composition has no perception port.
     *
     * Task 5 asks every screen path for one best-effort picture: a verified success, `alreadyForeground`, an ambiguous
     * name, an unknown app, an unusable shell, an invalid URL and a `STATE_UNCHANGED` failure all attach at most one
     * image. `list` and an unknown action are not screen operations and never capture; a pure argument-validation
     * refusal observes text only. The user's vision switch and the selected profile's `IMAGE` capability are gates
     * inside [com.sheen.rokid.plugin.PerceptionProvider.screenshot] (Task 2), so this tool does not duplicate them:
     * `Disabled` and `Unavailable` are answers that attach no image and never fail the launch. A capture that throws is
     * mapped to `Unavailable("SCREENSHOT_FAILED")` here (Task 4 fix), so a broken port cannot turn the launch into
     * `TOOL_THREW`. This is a separate read from [observe]; the two are not one atomic snapshot.
     */
    private val capture: () -> PerceptionScreenshotResult = { PerceptionScreenshotResult.Disabled },
    /**
     * edith-0.3 Phase 2.2 Task 5: the testable post-call observation source, or `null` until Task 6 wires the
     * production perception port in.
     *
     * The tool calls it after determining an outcome on every screen path, and attaches the answer beside the
     * execution facts. It is a separate read from [capture], so the tool cannot prove the two describe the same frame;
     * Task 6 is responsible for making the production source cheap and consistent.
     *
     * A missing (or throwing) source is not a silent `null`. Without a picture the result carries
     * `ToolObservation(vision = unavailable, visionReason = "no_source")`. With a picture the observation is built from
     * the tool's own [foreground], [foregroundActivity] and [rootPackage] probes, `screen`/`coverage` stay `null` as
     * the marker that no source answered, and `vision` is `ok` because the picture is attached.
     */
    private val observe: () -> ToolObservation? = { null },
) : ActionExecutor {

    private val resolver = AppLaunchResolver(catalog)
    private val catalog = catalog

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = Unit

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) return unknownAction()
        val args = parseObject(argsJson) ?: return invalidArguments()
        if (args.booleanArg("list") == true) return listApps()
        if (shell == null) return channelUnavailable()
        val target = args.stringArg("target")?.trim()?.takeIf { it.isNotEmpty() } ?: return invalidArguments()
        val relaunch = args.booleanArg("relaunch") ?: false
        val scheme = SCHEME.find(target)?.groupValues?.get(1)?.lowercase()
        if (scheme != null) {
            if (!isHttpUrl(target)) {
                return failure(
                    code = INVALID_URL,
                    message = "only http(s) URLs are accepted",
                    recoverable = false,
                    applied = false,
                )
            }
            return launch(command = "am start -W -a android.intent.action.VIEW -d '${singleQuote(target)}'")
        }
        SETTINGS_PAGES[target.lowercase()]?.let { action ->
            return launch(command = "am start -W -a $action")
        }
        return launchApp(target, relaunch)
    }

    // ── list ─────────────────────────────────────────────────────────────────

    private fun listApps(): ActionResult {
        val target = shell ?: return listChannelUnavailable()
        // R104 (user report 2026-10-20): the wearer asked for the file manager / gallery / camera / settings / music,
        // and `pm list packages -3` hid every one of them (K40: 20 third-party packages vs 346 installed). The launcher
        // activity query is the **launchable** set - 31 components on the K40, system apps included. A failed, empty or
        // unparseable reply falls back to every package, never to the third-party filter that caused the defect.
        val launcherReply = runSafely(target, LAUNCHER_QUERY)
        val launcherPackages = launcherReply
            ?.takeIf { it.failure == null && it.exitCode == 0 }
            ?.let { parseLauncherPackages(it.stdout) }
            .orEmpty()
        val packages: List<String>
        val replyTruncated: Boolean
        val catalogVia: String
        if (launcherPackages.isNotEmpty()) {
            packages = launcherPackages
            replyTruncated = launcherReply?.truncated == true
            catalogVia = "launcher"
        } else {
            val all = runSafely(target, PACKAGE_LIST_COMMAND) ?: return listChannelUnavailable()
            if (all.failure != null || all.exitCode != 0) return listChannelUnavailable()
            packages = parsePackageLines(all.stdout)
            replyTruncated = all.truncated
            catalogVia = "packages"
        }
        val labels = catalog.visibleApps().associate { entry -> entry.packageName to entry.label }
        val rows = packages.map { packageName ->
            val label = labels[packageName]?.takeIf { it.isNotBlank() } ?: packageName
            AppRow(label = label, packageName = packageName)
        }
        val truncated = replyTruncated || rows.size > MAX_LIST_ROWS
        val visible = rows.take(MAX_LIST_ROWS)
        val hasPlayer = rows.any { row ->
            PLAYER_PACKAGE.containsMatchIn(row.packageName) || PLAYER_LABEL.containsMatchIn(row.label)
        }
        val hasSettings = rows.any { row ->
            row.packageName == SETTINGS_PACKAGE || row.label.contains("设置") ||
                row.label.contains("settings", ignoreCase = true)
        }
        P3Log.line(
            "P3_TOOLCATALOG via=$catalogVia count=${packages.size} hasPlayer=$hasPlayer " +
                "hasSettings=$hasSettings truncated=$truncated",
        )
        val data = buildJsonObject {
            put(
                "apps",
                buildJsonArray {
                    visible.forEach { row ->
                        add(
                            buildJsonObject {
                                put("label", row.label)
                                put("packageName", row.packageName)
                            },
                        )
                    }
                },
            )
            put("text", visible.joinToString("\n") { "${it.label} (${it.packageName})" })
            put("truncated", truncated)
            // P5/T4 frozen fields: a summary the controller can judge without reading the catalogue body.
            put("catalogVia", catalogVia)
            put("catalogCount", packages.size)
            put("hasPlayer", hasPlayer)
            put("hasSettings", hasSettings)
        }
        // Task 5: `list` is a read, not a screen operation - execution facts only, no observation and no image.
        return done(data, execution = listDoneExecution())
    }
    /**
     * R104: one package per launchable component line (`com.pkg/.Activity` or `com.pkg/com.pkg.Activity`), deduplicated
     * in the shell's order. Headers (`priority=0 preferredOrder=0`), blanks and anything else are not components.
     */
    private fun parseLauncherPackages(stdout: String): List<String> = stdout.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash == trimmed.lastIndex) return@mapNotNull null
            trimmed.substring(0, slash).takeIf { PACKAGE_NAME.matches(it) }
        }
        .distinct()
        .toList()

    /** R104 fallback: every installed package (`package:<name>` lines), deduplicated in the shell's order. */
    private fun parsePackageLines(stdout: String): List<String> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_PREFIX) }
        .map { it.removePrefix(PACKAGE_PREFIX).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    // ── app launch ───────────────────────────────────────────────────────────

    private fun launchApp(target: String, relaunch: Boolean): ActionResult {
        // A package-shaped token is a package when the catalog actually knows that package (R43 fix): "youtube" is a
        // label, so it must fall through to name resolution. R105: when the catalog misses an installed package (the
        // system apps `pm list packages -3` already hid are the motivating case), the shell's `pm list packages
        // <target>` is the installed check before refusing - the catalog is only one view of what is installed.
        val packageShaped = PACKAGE_NAME.matches(target)
        val known = if (packageShaped) {
            catalog.visibleApps().firstOrNull { it.packageName == target }
        } else {
            null
        }
        val resolved = if (known != null) {
            LaunchTarget(known.packageName, known.label)
        } else {
            when (val resolution = resolver.resolve(target)) {
                is LaunchResolution.Found -> LaunchTarget(
                    resolution.target.packageName,
                    resolution.target.label,
                )
                is LaunchResolution.Ambiguous -> return ambiguous(resolution.candidates)
                LaunchResolution.NotFound -> {
                    if (!packageShaped) return appNotFound()
                    when (shellInstalled(target)) {
                        true -> LaunchTarget(target, target)
                        null -> return channelUnavailable()
                        false -> return appNotFound()
                    }
                }
            }
        }
        if (!relaunch && foreground() == resolved.packageName) {
            // edith-0.3 Phase 2.2 Task 5: `alreadyForeground` stays Done, but the model still needs to see the screen
            // it is already on - one best-effort observation and at most one picture.
            val attempt = screenAttempt()
            return done(
                buildJsonObject {
                    put("target", resolved.packageName)
                    put("alreadyForeground", true)
                    put("message", "already foreground")
                },
                images = attempt.images,
                observation = attempt.observation,
                execution = alreadyForegroundExecution(),
            )
        }
        val pkg = resolved.packageName
        val resolvedShell = shell ?: return channelUnavailable()
        val resolve = runSafely(
            resolvedShell,
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg",
        ) ?: return channelUnavailable()
        if (resolve.failure != null || resolve.exitCode != 0) return channelUnavailable()
        val component = COMPONENT_LINE.findAll(resolve.stdout).lastOrNull()?.value?.trim()
        val launchCommand = if (component != null) {
            "am start -W -n $component"
        } else {
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        }
        val started = launchEnvelope(
            resolvedShell,
            launchCommand,
            data = buildJsonObject {
                put("target", pkg)
                put("component", component?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            },
            expectedPackage = pkg,
        )
        return started
    }

    private fun ambiguous(candidates: List<AppLaunchResolver.LaunchTarget>): ActionResult = failure(
        code = APP_AMBIGUOUS,
        message = "multiple apps match the target; pick one of data.candidates",
        recoverable = true,
        // Ambiguity refuses before any launch is dispatched.
        applied = false,
        data = buildJsonObject {
            put(
                "candidates",
                buildJsonArray {
                    candidates.forEach { candidate ->
                        add(
                            buildJsonObject {
                                put("label", candidate.label)
                                put("packageName", candidate.packageName)
                            },
                        )
                    }
                },
            )
        },
    )

    // ── shell helpers ────────────────────────────────────────────────────────

    /** One URL/settings launch: the shell's `Status: ok` starts the verification, it does not end it. */
    private fun launch(command: String): ActionResult {
        val target = shell ?: return channelUnavailable()
        return launchEnvelope(target, command)
    }

    /**
     * Runs one launch command and verifies what is actually in front afterwards (0.3 Phase 2 Task 8).
     *
     * The shell's `Status: ok` is only the precondition. For a package launch ([expectedPackage] set) the tool waits for
     * that package and then requires the **foreground** and the **accessibility root** to both name it; for a
     * URL/settings launch no target package is known, so the observed foreground must be non-null and equal the root
     * package. A mismatch is a recoverable `STATE_UNCHANGED` whose `data` keeps the caller's target/component only:
     * the observed foreground/root and the `verified` answer are the result's `observation` and `execution` facts
     * (Task 8).
     *
     * 0.3 Phase 2.2 Task 5: every outcome observes the screen and attaches at most one best-effort picture. Task 8 moved
     * the `vision` marker off `data` (it is `observation.vision`) and the foreground/rootPackage facts into the
     * observation, where they are canonical.
     */
    private fun launchEnvelope(
        target: PrivilegedShell,
        command: String,
        data: kotlinx.serialization.json.JsonObject = buildJsonObject { },
        expectedPackage: String? = null,
    ): ActionResult {
        val result = runSafely(target, command) ?: return channelUnavailable()
        if (result.failure != null) return channelUnavailable()
        if (result.exitCode != 0) {
            // The only explicit failure fact: the shell itself reports the command did not run successfully.
            return failure(
                STATE_UNCHANGED,
                "the launch command exited ${result.exitCode}",
                recoverable = true,
                outcome = ActionOutcome.Failed,
                applied = false,
                data = data,
            )
        }
        if (!result.stdout.contains(STATUS_OK)) {
            // Exit 0 without `Status: ok` is not proof that the launch failed: `am start -W` can answer
            // `Status: timeout`, and the line can be truncated or localized. The command was accepted and ran, but
            // whether it applied is unknown, so `applied` stays null (Task 5 fix).
            return failure(
                STATE_UNCHANGED,
                "the launch produced no success status",
                recoverable = true,
                outcome = ActionOutcome.Failed,
                applied = null,
                data = data,
            )
        }
        val reachedForeground = expectedPackage == null ||
            waitForForeground(expectedPackage, FOREGROUND_TIMEOUT_MILLIS)
        val observedForeground = foreground()
        val observedActivity = foregroundActivity()
        val observedRoot = rootPackage()
        val verified = if (expectedPackage != null) {
            reachedForeground && observedForeground == expectedPackage && observedRoot == expectedPackage
        } else {
            observedForeground != null && observedForeground == observedRoot
        }
        if (!verified) {
            // Task 5 / T7: a verification mismatch keeps its Failed/STATE_UNCHANGED outcome and its target/component
            // data, but it now observes the screen it actually left behind (and attaches one best-effort picture).
            // `applied` stays null: the mismatch proves the request is not in front, not that the command never applied.
            return failure(
                code = STATE_UNCHANGED,
                message = "the launch command succeeded but the request is not verified in front",
                recoverable = true,
                outcome = ActionOutcome.Failed,
                data = data,
                applied = null,
                // The probes above are this call's own foreground/root answer; they ride the observation.
                probe = ObservationProbes(observedForeground, observedActivity, observedRoot),
            )
        }
        // 0.3 Phase 2.2 Task 5: a verified success observes and captures after the verification read. The observation
        // source and the capture are two independent reads, so the tool cannot prove the observation text, the image
        // and the foreground/root facts describe the same frame; Task 6's production source must make them consistent.
        // A capture port that throws is still just "no image" (Task 4 fix): the verified launch succeeded, so it must
        // not turn the tool result into TOOL_THREW.
        val attempt = screenAttempt(ObservationProbes(observedForeground, observedActivity, observedRoot))
        return done(
            buildJsonObject {
                data.forEach { (key, value) -> put(key, value) }
                put("launched", true)
            },
            images = attempt.images,
            observation = attempt.observation,
            execution = verifiedExecution(),
        )
    }

    private fun runSafely(target: PrivilegedShell, command: String): ShellResult? =
        runCatching { target.run(command) }.getOrNull()

    /**
     * R105: whether the shell itself reports [packageName] installed, or `null` when the probe itself could not be read.
     *
     * `pm list packages <filter>` matches substrings, so the exact `package:<name>` line is the installed answer: the
     * catalog may still miss a system app that the shell can launch. A probe that could not cross the channel is not
     * "not installed" - it is an unusable channel, and the caller says so instead of claiming the app is absent.
     */
    private fun shellInstalled(packageName: String): Boolean? {
        val target = shell ?: return null
        val result = runSafely(target, "$PACKAGE_LIST_COMMAND $packageName") ?: return null
        if (result.failure != null || result.exitCode != 0) return null
        return result.stdout.lineSequence().any { it.trim() == "$PACKAGE_PREFIX$packageName" }
    }

    /** Task 4: the captured artifact, if the capture produced one; a disabled/unavailable capture adds no image. */
    private fun capturedImage(capture: PerceptionScreenshotResult): List<ImageRef> =
        listOfNotNull((capture as? PerceptionScreenshotResult.Image)?.image)

    // R98: every `open` call is a write, whatever its outcome — launching an app, a page or a URL changes the
    // foreground state; the declaration describes the call class, not whether the launch got to run.
    private fun done(
        data: kotlinx.serialization.json.JsonObject,
        images: List<ImageRef> = emptyList(),
        observation: ToolObservation? = null,
        execution: ToolExecution = ToolExecution(outcome = ToolOutcome.Done),
    ): ActionResult = ActionResult(
        ActionOutcome.Done,
        // Task 8: `data` is the tool-specific payload only. The pre-Task-8 `toolEnvelope` repeated ok/channel/accepted/
        // applied/error and a vision marker in it; execution/observation are the canonical carriers.
        data.toString(),
        stateChanging = true,
        images = images,
        // C2: observation text and an attached image may live only in this run's model request.
        nonPersistent = observation != null || images.isNotEmpty(),
        execution = execution,
        observation = observation,
    )

    /**
     * edith-0.3 Phase 2.2 Task 5: one refusal or failure of a screen operation.
     *
     * [captureImage] and [attachObservation] encode the task's image boundary: the launch paths observe and attach at
     * most one picture, a pure argument refusal observes text only, and `list` does neither. Nothing here can change
     * the outcome or fail the call. Task 8 keeps `data` to the caller's tool-specific `target`/`component`/`candidates`
     * and moves the foreground/root/verified/vision facts to [observation]/[execution].
     *
     * [applied] defaults to `null`: a failure without evidence must not claim the action did not apply. A caller that
     * knows nothing was dispatched (a pure refusal, or a non-zero shell exit) passes `false` explicitly.
     *
     * [probe] is the foreground/root read this call already made for its own verification. Passing it in keeps the
     * observation's foreground/root facts identical to the ones the verdict was based on, instead of reading the screen
     * a second time; a path without probes simply lets [screenAttempt] read them.
     */
    private fun failure(
        code: String,
        message: String,
        recoverable: Boolean,
        outcome: ActionOutcome = ActionOutcome.Refused,
        data: kotlinx.serialization.json.JsonObject? = null,
        applied: Boolean? = null,
        captureImage: Boolean = true,
        attachObservation: Boolean = true,
        probe: ObservationProbes? = null,
    ): ActionResult {
        val observation: ToolObservation?
        val images: List<ImageRef>
        when {
            !attachObservation -> {
                observation = null
                images = emptyList()
            }
            captureImage -> {
                val attempt = screenAttempt(probe)
                observation = attempt.observation
                images = attempt.images
            }
            else -> {
                observation = textObservation(probe)
                images = emptyList()
            }
        }
        val failed = outcome == ActionOutcome.Failed
        return ActionResult(
            outcome,
            // Task 8: tool-specific data only (the tool's stable code lives in `execution.code`).
            (data ?: buildJsonObject { }).toString(),
            stateChanging = true,
            images = images,
            nonPersistent = observation != null || images.isNotEmpty(),
            execution = ToolExecution(
                outcome = if (failed) ToolOutcome.Failed else ToolOutcome.Refused,
                code = code,
                message = message,
                recoverable = recoverable,
                // A Failed/STATE_UNCHANGED launch was accepted by the shell and then failed its verification; a refusal
                // never dispatched the action at all.
                accepted = failed,
                applied = applied,
                // `verified=false` is the evidence that failed the launch; a refusal checked no post-condition.
                verified = if (failed) false else null,
                changed = null,
            ),
            observation = observation,
        )
    }

    private fun appNotFound(): ActionResult = failure(
        code = APP_NOT_FOUND,
        message = "no installed app matches the target",
        recoverable = true,
        // Nothing was dispatched: the refusal happens before any launch command.
        applied = false,
    )

    private fun channelUnavailable(): ActionResult = failure(
        code = CHANNEL_UNAVAILABLE,
        message = "the privileged shell is not usable",
        recoverable = true,
        applied = false,
    )

    /**
     * Task 5: `list` is not a screen operation, so even its channel failure invents no observation or image.
     *
     * `applied=null` is the read convention (nothing was applied, and the action is not applicable), not the launch
     * refusal's `applied=false` - `list` never dispatches a launch at all.
     */
    private fun listChannelUnavailable(): ActionResult = failure(
        code = CHANNEL_UNAVAILABLE,
        message = "the privileged shell is not usable",
        recoverable = true,
        applied = null,
        captureImage = false,
        attachObservation = false,
    )

    private fun invalidArguments(): ActionResult = failure(
        code = INVALID_ARGUMENTS,
        message = "open needs list=true or a target",
        recoverable = false,
        applied = false,
        captureImage = false,
    )

    /**
     * edith-0.3 Phase 2.2 Task 5: an unknown action is not an `open` call at all.
     *
     * Its model-facing `data` is empty and the stable code lives in `execution.code`; it carries the same structured
     * refusal facts a validation refusal does. It is not a screen operation, so it invents no observation and attaches
     * no image.
     */
    private fun unknownAction(): ActionResult = ActionResult(
        ActionOutcome.Refused,
        // Task 8: the stable code is `execution.code`; the bare data copy is gone.
        "{}",
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = UNKNOWN_ACTION,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

    /**
     * edith-0.3 Phase 2.2 Task 5: one screen attempt's observation and picture.
     *
     * [captured] is never null: [screenAttempt] always takes exactly one picture (or records how the capture port
     * answered), which is what keeps every result to **at most one image**.
     */
    private data class ObservationAttempt(
        val observation: ToolObservation,
        val images: List<ImageRef>,
    )

    /** The foreground/root read one launch already made for its verification, reused by the observation. */
    private data class ObservationProbes(
        val foreground: String?,
        val activity: String?,
        val rootPackage: String?,
    )

    /**
     * The one post-call observation + picture builder for a screen attempt.
     *
     * The observation facts normally come from the injected [observe] source (Task 6 wires the production perception
     * port); the picture comes from [capture]. These are **two independent reads**, not one atomic snapshot: the tool
     * cannot prove they describe the same frame, so Task 6's production source must be cheap and consistent.
     *
     * A missing or throwing source does not hide a usable picture. With an image attached, the observation is built
     * from the tool's own [foreground], [foregroundActivity] and [rootPackage] probes with `screen = null` and
     * `coverage = null`; those nulls are the marker that no observation source answered (a real source supplies the
     * perception content), and `vision = ok` is reported because the picture is attached. Without an image the result
     * says `unavailable/no_source`. A capture port that throws is mapped to `unavailable/SCREENSHOT_FAILED`, exactly
     * as Task 4 fixed for the verified path.
     *
     * Task 8: a source observation (or an attached picture) is completed with the tool's own foreground/root facts
     * where the source had none — `rootPackage` is not part of a perception snapshot, and the launch's own verification
     * read is the most honest value available. The foreground/root facts are no longer repeated in `data`.
     *
     * P3-9 (Phase 3 Task 4): every screen attempt captures exactly once, so it emits one count-only
     * `P3_OBSERVE source=tool tool=open observeMs=<n> captureMs=<n> result=<ok|unavailable|disabled>` line. The token
     * is the capture port's own answer even when the observation also reports a picture from the tool: the line measures
     * this attempt, never the payload.
     */
    private fun screenAttempt(probe: ObservationProbes? = null): ObservationAttempt {
        val captureStarted = System.nanoTime()
        val captured = runCatching { capture() }
            .getOrElse { PerceptionScreenshotResult.Unavailable(SCREENSHOT_FAILED) }
        val captureNanos = System.nanoTime() - captureStarted
        val observeStarted = System.nanoTime()
        val observed = runCatching { observe() }.getOrNull()
        val observeNanos = System.nanoTime() - observeStarted
        ToolObserveTelemetry.emit(NAME, observeNanos, captureNanos, captured)
        val image = (captured as? PerceptionScreenshotResult.Image)?.image
        val observation = when {
            observed != null -> observed.copy(
                foregroundPackage = observed.foregroundPackage ?: probe?.foreground ?: probeForeground(),
                foregroundActivity = observed.foregroundActivity ?: probe?.activity ?: probeForegroundActivity(),
                rootPackage = observed.rootPackage ?: probe?.rootPackage ?: probeRootPackage(),
                vision = when {
                    image != null -> ToolVision.Ok
                    captured == PerceptionScreenshotResult.Disabled -> ToolVision.Disabled
                    captured is PerceptionScreenshotResult.Unavailable -> ToolVision.Unavailable
                    else -> ToolVision.Unavailable
                },
                visionReason = when {
                    image != null -> null
                    captured == PerceptionScreenshotResult.Disabled -> null
                    // Task 6 review: when the source itself reports the screen unavailable (`no_accessibility`), that
                    // reason explains the missing observation; prefer it over the capture's image-only reason, exactly
                    // as `ToolCatalog.screenAttempt` does for shell/dial.
                    observed.vision == ToolVision.Unavailable && observed.visionReason != null -> observed.visionReason
                    captured is PerceptionScreenshotResult.Unavailable -> captured.reason
                    else -> null
                },
            )
            image != null -> ToolObservation(
                // No source answered, so no screen/coverage text may be invented. The tool's own probes are the
                // honest facts it still has; `screen`/`coverage` stay null to say "no observation source".
                foregroundPackage = probe?.foreground ?: probeForeground(),
                foregroundActivity = probe?.activity ?: probeForegroundActivity(),
                rootPackage = probe?.rootPackage ?: probeRootPackage(),
                vision = ToolVision.Ok,
            )
            else -> ToolObservation(
                // A verification read that produced no screen source still knows where the phone is; those facts are
                // the observation's foreground/root (Task 8) even when no capture exists.
                foregroundPackage = probe?.foreground,
                foregroundActivity = probe?.activity,
                rootPackage = probe?.rootPackage,
                vision = ToolVision.Unavailable,
                visionReason = VISION_NO_SOURCE,
            )
        }
        return ObservationAttempt(observation, capturedImage(captured))
    }

    /** A throwing foreground port must degrade the observation, never turn the tool result into `TOOL_THREW`. */
    private fun probeForeground(): String? = runCatching { foreground() }.getOrNull()

    private fun probeForegroundActivity(): String? = runCatching { foregroundActivity() }.getOrNull()

    private fun probeRootPackage(): String? = runCatching { rootPackage() }.getOrNull()

    /** Task 5: the observation text a pure argument refusal can afford - no picture is attempted. */
    private fun textObservation(probe: ObservationProbes? = null): ToolObservation {
        val observed = runCatching { observe() }.getOrNull()
            ?: return ToolObservation(vision = ToolVision.Unavailable, visionReason = VISION_NO_SOURCE)
        return observed.copy(
            foregroundPackage = observed.foregroundPackage ?: probe?.foreground ?: probeForeground(),
            foregroundActivity = observed.foregroundActivity ?: probe?.activity ?: probeForegroundActivity(),
            rootPackage = observed.rootPackage ?: probe?.rootPackage ?: probeRootPackage(),
            vision = ToolVision.Unavailable,
            visionReason = VISION_NOT_ATTEMPTED,
        )
    }

    /**
     * Task 5: a `list` read reports its own facts - nothing was applied, verified or changed.
     *
     * `applied=null` is the read/not-applicable convention (the same as the `DeviceTool` reads), not the launch
     * refusal's `applied=false`: `list` never dispatches an action that could have applied.
     */
    private fun listDoneExecution(): ToolExecution = ToolExecution(
        outcome = ToolOutcome.Done,
        accepted = true,
    )

    /** Task 5: `alreadyForeground` did not launch, so the call applied nothing and the screen did not change. */
    private fun alreadyForegroundExecution(): ToolExecution = ToolExecution(
        outcome = ToolOutcome.Done,
        accepted = true,
        applied = false,
        verified = null,
        changed = false,
    )

    /** Task 5: a verified launch is the only `open` path with evidence for all four facts. */
    private fun verifiedExecution(): ToolExecution = ToolExecution(
        outcome = ToolOutcome.Done,
        accepted = true,
        applied = true,
        verified = true,
        changed = true,
    )

    private fun isHttpUrl(url: String): Boolean = try {
        val parsed = java.net.URI(url)
        (parsed.scheme?.lowercase() == "http" || parsed.scheme?.lowercase() == "https") &&
            !parsed.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private fun singleQuote(value: String): String = value.replace("'", "'\\''")

    private data class LaunchTarget(val packageName: String, val label: String)

    private data class AppRow(val label: String, val packageName: String)

    companion object {
        const val NAME = "open"

        private const val MAX_LIST_ROWS = 100
        private const val PACKAGE_PREFIX = "package:"

        /** R104: the launchable set - one component per line, user and system apps alike. */
        private const val LAUNCHER_QUERY =
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"

        /** R104 fallback: every installed package, never the third-party-only `-3` filter that hid system apps. */
        private const val PACKAGE_LIST_COMMAND = "pm list packages"

        private const val STATUS_OK = "Status: ok"

        /** How long a launch may take to put its package in front before the observation is treated as a failure. */
        private const val FOREGROUND_TIMEOUT_MILLIS = 2_000L

        /** The default `waitForForeground` poll interval. */
        private const val FOREGROUND_POLL_MILLIS = 100L
        private const val APP_AMBIGUOUS = "APP_AMBIGUOUS"
        private const val APP_NOT_FOUND = "APP_NOT_FOUND"
        private const val STATE_UNCHANGED = "STATE_UNCHANGED"
        private const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        private const val INVALID_URL = "INVALID_URL"
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /** Task 5: why a screen attempt has no picture, in the same vocabulary `AgentLoop` uses. */
        private const val VISION_NO_SOURCE = "no_source"
        private const val VISION_NOT_ATTEMPTED = "not_attempted"

        /** Task 4 fix: a capture port that throws becomes this `unavailable` reason, not a thrown tool. */
        private const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"

        private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")
        private val PLAYER_PACKAGE = Regex("(?i)player|music|video|media")
        private val PLAYER_LABEL = Regex("(?i)音乐|播放器|视频|music|player|video")
        private const val SETTINGS_PACKAGE = "com.android.settings"

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        private val COMPONENT_LINE = Regex("""(?m)^\s*([A-Za-z0-9._]+)/([A-Za-z0-9._$]+)\s*$""")

        /** The same ten-page allowlist as `IntentActionExecutor.open_settings` (spec §4.11). */
        private val SETTINGS_PAGES: Map<String, String> = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        )

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "打开应用、http(s) 页面或设置页",
            description = "按 target 打开：应用名/包名、http(s) 链接，或设置页别名（wifi/bluetooth/apps/display/sound/battery 等）。应用名有歧义时返回 APP_AMBIGUOUS 并附候选，此时问用户选哪个，不要猜；找不到返回 APP_NOT_FOUND；启动后前台不是目标应用返回 STATE_UNCHANGED。list=true 只列可启动应用（只列目录时 list_apps 也可），不执行打开；relaunch=false 时目标已在前台不会重开。打开应用/网页/设置页优先用本工具（支持包名、候选和启动后校验）；只有 Shizuku 通道不可用时才用 open_app/open_url/open_settings 兜底。不支持 file://、content:// 与裸本地路径。",
            parametersJson = """{"type":"object","properties":{""" +
                """"target":{"type":"string","description":"应用名/包名、http(s) URL、或设置页别名（wifi/bluetooth/apps/display/sound/battery 等）。用户说类别词（如“文件”）时保持原词，不要自行扩成某个具体应用；歧义由工具返回候选。不支持 file://、content://、裸本地路径。"},""" +
                """"list":{"type":"boolean","description":"true 时列出可启动应用，不执行打开。"},""" +
                """"relaunch":{"type":"boolean"}},"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "device",
            screenObservation = true,
        )
    }
}
