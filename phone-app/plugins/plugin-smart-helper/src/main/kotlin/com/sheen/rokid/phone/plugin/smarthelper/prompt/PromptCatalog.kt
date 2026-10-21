package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.plugin.DeviceProfile

/**
 * One row of `prompts/manifest.tsv` (spec §4.1).
 *
 * [version] is the resource revision, not the catalog semver: editing a section's prose must bump it
 * together with the golden hash in the same commit.
 */
internal data class PromptManifestEntry(
    val id: String,
    val file: String,
    val kind: PromptKind,
    val version: String,
    val description: String,
)

/**
 * The single selection, order and version point of the system prompt (spec §4.1, §4.2).
 *
 * The catalog is **data**, not prose: every word lives in `src/main/resources/prompts/`, loaded
 * through [PromptDocument], and this object only decides which authored sections go into a plan and
 * how device facts are rendered into them. That keeps the static prompt reviewable as Markdown and
 * keeps `PromptCatalog` free of business wording.
 *
 * Layout produced by [plan], from spec §4.2 and the Phase 3 brief:
 * 1. the core sections in filename order (00-identity … 50-memory-policy);
 * 2. the route posture (`route/wake.md`, `route/proactive-hint.md` or `route/proactive-followup.md`);
 * 3. `context/device-profile.md`, rendered from the live [DeviceProfile], only when a profile exists.
 *
 * The per-request block ([dynamicTemplate]) is deliberately **not** part of a plan: dynamic values
 * in the system prompt would break the cache boundary in spec §4.4. The `loop/`, `pinned/` and
 * `summarizer.md` resources are independent fixed prompts loaded by [loop], [pinned] and
 * [summarizer]; they never enter a system plan (a `PINNED` gate question is its own model call, and
 * a loop instruction is a synthetic turn), so their prose cannot invalidate the cache prefix.
 */
object PromptCatalog {

    /** Catalog semver; a session pins the plan it loaded, it never mixes versions (spec §4.1). */
    const val VERSION: String = "0.4.1"

    private const val PROMPTS_DIR = "prompts/"
    private const val MANIFEST_FILE = "manifest.tsv"
    private const val DEVICE_PROFILE_FILE = "context/device-profile.md"
    private const val RUNTIME_STATE_FILE = "context/runtime-state.md"
    private const val SUMMARIZER_FILE = "summarizer.md"

    /** The only directories a manifest file may live in. */
    private val RESOURCE_FOLDERS = setOf("core", "route", "context", "loop", "pinned")

    /**
     * Every prompt route's posture resource (0.3 Phase 3 spec §5.1). The follow-up route is a separate posture from
     * the hint even though both belong to the proactive chain: a hint decides whether to speak, a follow-up answers a
     * wearer who already replied.
     */
    private val ROUTE_FILES: Map<PromptRoute, String> = mapOf(
        PromptRoute.WAKE to "route/wake.md",
        PromptRoute.PROACTIVE_HINT to "route/proactive-hint.md",
        PromptRoute.PROACTIVE_FOLLOWUP to "route/proactive-followup.md",
    )

    /** The only accepted placeholder shape, matching [PromptRenderer]. */
    private val PLACEHOLDER = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")

    /**
     * Every `DeviceProfile` fact the device-profile template may use, and how its value is rendered
     * (spec §4.2.1). A `null`, blank or non-positive value maps to `null`, which drops its line: the
     * provider signals "unavailable" with `""`/`0` for non-null contract fields, and the prompt must
     * never print `model=` or `apiLevel=0`.
     */
    private val DEVICE_PROFILE_VALUES: Map<String, (DeviceProfile) -> String?> = linkedMapOf(
        "deviceManufacturer" to { profile -> profile.manufacturer.takeIf { it.isNotBlank() } },
        "deviceModel" to { profile -> profile.model.takeIf { it.isNotBlank() } },
        "deviceName" to { profile -> profile.deviceName?.takeIf { it.isNotBlank() } },
        "androidRelease" to { profile -> profile.androidRelease.takeIf { it.isNotBlank() } },
        "apiLevel" to { profile -> profile.apiLevel.takeIf { it > 0 }?.toString() },
        "screenWidth" to { profile -> profile.screenWidthPx.takeIf { it > 0 }?.toString() },
        "screenHeight" to { profile -> profile.screenHeightPx.takeIf { it > 0 }?.toString() },
        "densityDpi" to { profile -> profile.densityDpi.takeIf { it > 0 }?.toString() },
        "glassesModel" to { profile -> profile.glassesModel?.takeIf { it.isNotBlank() } },
        "glassesFirmware" to { profile -> profile.glassesFirmware?.takeIf { it.isNotBlank() } },
        "glassesWidth" to { profile -> profile.glassesWidthPx?.takeIf { it > 0 }?.toString() },
        "glassesHeight" to { profile -> profile.glassesHeightPx?.takeIf { it > 0 }?.toString() },
        "appVersion" to { profile -> profile.appVersion.takeIf { it.isNotBlank() } },
        "locale" to { profile -> profile.locale.takeIf { it.isNotBlank() } },
        "timeZone" to { profile -> profile.timeZone.takeIf { it.isNotBlank() } },
        "wakeWords" to { profile ->
            profile.wakeWords.map { it.trim() }.filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、")
        },
        "capabilities" to { profile ->
            profile.capabilities.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、")
        },
    )

    /**
     * The per-request slots of the dynamic runtime block (spec §4.3). A dynamic template may use
     * exactly these and nothing else, so a `DeviceProfile` value cannot leak into a request-scoped
     * block.
     */
    private val DYNAMIC_SLOTS: Set<String> = linkedSetOf(
        "date",
        "weekday",
        "time",
        "timeZone",
        "deviceState",
        "foreground",
        "media",
        "perception",
        "coverage",
        "notifications",
        "calendar",
        "acoustic",
        "clipboard",
        "memoryEvidence",
        "skillBodies",
        "loopState",
    )

    private val manifestEntries: List<PromptManifestEntry> by lazy {
        parseManifest(PromptDocument.load(PROMPTS_DIR + MANIFEST_FILE), PromptDocument::load)
            .also(::validateCatalog)
    }

    private val resourceTexts: Map<String, String> by lazy {
        manifest().associate { entry ->
            entry.file to normalize(PromptDocument.load(PROMPTS_DIR + entry.file))
        }
    }

    /**
     * Builds the immutable system plan for [route] from [profile], or from the route and core only
     * when the platform could not supply a profile.
     *
     * The result is deterministic for the same route + profile: sections keep the authored order and
     * [PromptPlan.hash] fingerprints the rendered texts, so `P3_PROMPT` can prove which prompt ran.
     * The plan never contains a `DYNAMIC` section and never keeps a `{{slot}}`.
     */
    internal fun plan(route: PromptRoute, profile: DeviceProfile?): PromptPlan {
        val sections = mutableListOf<PromptSection>()
        val entries = manifest()

        entries.filter { it.file.startsWith("core/") }
            .sortedBy { it.file }
            .forEach { sections += PromptSection(it.id, it.kind, sectionText(it)) }

        val routeFile = ROUTE_FILES[route]
            ?: throw IllegalArgumentException("no prompt route mapping for $route")
        val routeEntry = entries.first { it.file == routeFile }
        sections += PromptSection(routeEntry.id, routeEntry.kind, sectionText(routeEntry))

        if (profile != null) {
            val deviceEntry = entries.first { it.file == DEVICE_PROFILE_FILE }
            renderDeviceProfile(profile)?.let {
                sections += PromptSection(deviceEntry.id, PromptKind.SESSION_STATIC, it)
            }
        }

        val systemText = sections.joinToString("\n\n") { it.text }
        return PromptPlan(VERSION, sections, systemText, PromptPlan.hashOf(sections))
    }

    /**
     * The raw `context/runtime-state.md` template, rendered by the caller once per request
     * (spec §4.3). It is not part of any [PromptPlan].
     */
    fun dynamicTemplate(): String = resourceTexts.getValue(RUNTIME_STATE_FILE)

    /**
     * One externally constrained fixed prompt (`pinned/<key>.md`), such as Laya's fine-tuned gate question and its
     * neutral options.
     *
     * PINNED content is not a system section: it belongs to its own call and is deliberately excluded from [plan]
     * because rewriting it or paying for it on every request would invalidate its calibration or the cache prefix.
     */
    fun pinned(key: String): String = manifestResource("pinned/$key.md")

    /**
     * One loop-owned instruction (`loop/<key>.md`): retry, plan nudge or timeout guidance.
     *
     * These are injected as their own synthetic turn by the runtime, never as part of the system plan, so the
     * cacheable prefix does not change when a loop branch runs.
     */
    fun loop(key: String): String = manifestResource("loop/$key.md")

    /**
     * The instruction for the asynchronous conversation summarizer.
     *
     * A summary is an independent request with no tools and no assembled [PromptPlan], so this text is not a
     * system section either.
     */
    fun summarizer(): String = manifestResource(SUMMARIZER_FILE)

    /**
     * The perception block of `context/runtime-state.md` (0.3 Phase 2 Task 9): the template up to, but excluding, the
     * `记忆：` header.
     *
     * Memory evidence, SKILL bodies and the loop state keep their own dedicated tail segments (they are not perception
     * sources), so the perception block renders exactly the thirteen slots above that header. The full template stays
     * intact for a future unification; this accessor is the one the perception renderer uses today.
     */
    fun perceptionTemplate(): String = dynamicTemplate().substringBefore("\n记忆：")

    /** The validated manifest, in authored order. Internal so tests can prove every entry loads. */
    internal fun manifest(): List<PromptManifestEntry> = manifestEntries

    /**
     * Parses and validates one manifest text. [loadResource] resolves a classpath-style path
     * (`prompts/...`) and must throw for a missing resource, which is how a broken manifest fails
     * loudly instead of shipping a session with a silently missing section.
     *
     * Validation rules: five tab-separated columns, legal [PromptKind], unique id and file, a file
     * under `core/`, `route/`, `context/`, `loop/` or `pinned/` (or the root `summarizer.md`), and
     * content that respects its kind's slot contract
     * (`STATIC` has no placeholders, `SESSION_STATIC` uses exactly the device-profile slots,
     * `DYNAMIC` uses exactly the runtime slots). Internal with an injectable loader so the rejection
     * paths are testable without shipping a broken resource.
     */
    internal fun parseManifest(
        text: String,
        loadResource: (String) -> String,
    ): List<PromptManifestEntry> {
        val entries = mutableListOf<PromptManifestEntry>()
        val ids = mutableSetOf<String>()
        val files = mutableSetOf<String>()

        for ((index, rawLine) in text.lines().withIndex()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue

            val fields = line.split('\t')
            require(fields.size == 5) {
                "manifest line ${index + 1} must have 5 tab-separated columns " +
                    "(id, file, kind, version, description)"
            }
            val (id, file, kindName, version, description) = fields
            require(id.isNotBlank()) { "manifest line ${index + 1} has a blank id" }
            require(ids.add(id)) { "duplicate manifest id '$id'" }
            require(file.isNotBlank()) { "manifest '$id' has a blank file" }
            require(files.add(file)) { "duplicate manifest file '$file'" }
            require(file.endsWith(".md")) { "manifest '$id' file '$file' must be Markdown" }
            require(file == SUMMARIZER_FILE || file.substringBefore('/') in RESOURCE_FOLDERS) {
                "manifest '$id' file '$file' must live under one of $RESOURCE_FOLDERS or be $SUMMARIZER_FILE"
            }
            val kind = PromptKind.entries.firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("manifest '$id' has unknown kind '$kindName'")
            require(version.isNotBlank()) { "manifest '$id' has a blank version" }
            require(description.isNotBlank()) { "manifest '$id' has a blank description" }

            val resource = PROMPTS_DIR + file
            val resourceText = loadResource(resource)
            when (kind) {
                PromptKind.STATIC -> validateStaticResource(resourceText, resource)
                PromptKind.SESSION_STATIC -> validateDeviceProfileTemplate(resourceText, resource)
                PromptKind.DYNAMIC -> validateDynamicTemplate(resourceText, resource)
                PromptKind.PINNED -> Unit
            }
            entries += PromptManifestEntry(id, file, kind, version, description)
        }

        require(entries.isNotEmpty()) { "the prompt manifest is empty" }
        return entries
    }

    /**
     * Cross-entry invariants: the core sections are STATIC, every [PromptRoute] has its posture file, and there is
     * exactly one device-profile template and one runtime-state template.
     *
     * Internal (rather than private) so the rejection path for a missing route is testable from a manifest fixture,
     * exactly like [parseManifest].
     */
    internal fun validateCatalog(entries: List<PromptManifestEntry>) {
        val byFile = entries.associateBy { it.file }

        val core = entries.filter { it.file.startsWith("core/") }
        require(core.isNotEmpty()) { "the manifest must list the core prompt sections" }
        core.forEach {
            require(it.kind == PromptKind.STATIC) { "core section '${it.id}' must be STATIC" }
        }

        for (route in PromptRoute.entries) {
            val routeFile = ROUTE_FILES[route]
                ?: throw IllegalStateException("no prompt route mapping for $route")
            val routeEntry = byFile[routeFile]
                ?: throw IllegalArgumentException("the manifest must list the route resource '$routeFile'")
            require(routeEntry.kind == PromptKind.STATIC) { "route section '${routeEntry.id}' must be STATIC" }
        }

        val deviceEntries = entries.filter { it.kind == PromptKind.SESSION_STATIC }
        require(deviceEntries.size == 1 && deviceEntries.single().file == DEVICE_PROFILE_FILE) {
            "the manifest must list exactly one SESSION_STATIC device profile: $DEVICE_PROFILE_FILE"
        }

        val dynamicEntries = entries.filter { it.kind == PromptKind.DYNAMIC }
        require(dynamicEntries.size == 1 && dynamicEntries.single().file == RUNTIME_STATE_FILE) {
            "the manifest must list exactly one DYNAMIC runtime template: $RUNTIME_STATE_FILE"
        }
    }

    private fun sectionText(entry: PromptManifestEntry): String = resourceTexts.getValue(entry.file)

    /**
     * Loads a resource through the validated manifest so an accessor cannot read a file the catalog did not accept
     * (wrong folder, blank version, duplicate id). A missing key fails loudly with the path the caller asked for.
     */
    private fun manifestResource(file: String): String {
        require(file in resourceTexts) { "'$file' is not a validated prompt resource in the manifest" }
        return resourceTexts.getValue(file)
    }

    /**
     * Renders `context/device-profile.md` line by line against [profile].
     *
     * A line that needs a value the profile does not supply (null, blank, zero) is dropped as a
     * whole, so the prompt never shows `设备：` with an empty half, `model=` with no value or an
     * invented `unknown`. Lines without a placeholder (the header) are kept, but if no fact line
     * survives the whole section is omitted rather than shipping a bare header.
     */
    private fun renderDeviceProfile(profile: DeviceProfile): String? {
        val template = resourceTexts.getValue(DEVICE_PROFILE_FILE)
        val values = deviceProfileValues(profile)
        val keptLines = mutableListOf<String>()
        var factLines = 0

        for (line in template.lines()) {
            val names = placeholdersIn(line)
            if (names.isEmpty()) {
                keptLines += line
            } else if (names.all { it in values }) {
                keptLines += line
                factLines++
            }
        }

        if (factLines == 0) return null

        val kept = keptLines.joinToString("\n").trim()
        val used = placeholdersIn(kept)
        return PromptRenderer.render(
            kept,
            values.filterKeys { it in used },
            DEVICE_PROFILE_VALUES.keys,
        ).trim()
    }

    private fun deviceProfileValues(profile: DeviceProfile): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for ((name, read) in DEVICE_PROFILE_VALUES) {
            read(profile)?.let { values[name] = it }
        }
        return values
    }

    private fun validateStaticResource(text: String, resource: String) {
        require("{{" !in text && "}}" !in text) {
            "$resource is STATIC and must not contain a placeholder"
        }
    }

    private fun validateDeviceProfileTemplate(template: String, resource: String) {
        validateStrictPlaceholders(template, resource)
        val names = placeholdersIn(template)
        require(names == DEVICE_PROFILE_VALUES.keys) {
            "$resource may only use the DeviceProfile slots ${DEVICE_PROFILE_VALUES.keys.sorted()}, " +
                "but uses ${names.sorted()}"
        }
    }

    private fun validateDynamicTemplate(template: String, resource: String) {
        validateStrictPlaceholders(template, resource)
        val names = placeholdersIn(template)
        require(names == DYNAMIC_SLOTS) {
            "$resource may only use the per-request slots ${DYNAMIC_SLOTS.sorted()}, " +
                "but uses ${names.sorted()}"
        }
    }

    private fun validateStrictPlaceholders(template: String, resource: String) {
        val stripped = PLACEHOLDER.replace(template, "")
        require("{{" !in stripped && "}}" !in stripped) {
            "$resource contains a placeholder that is not a strict '{{name}}' token"
        }
    }

    private fun placeholdersIn(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    /** Normalises checkout line endings so a CRLF working tree produces the same golden hash. */
    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()
}
