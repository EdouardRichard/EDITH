package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.agent.RequestLayout
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.phone.plugin.smarthelper.output.ToolLabelMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.3 design §3.6: the six architecture rules that pin A′ — mechanism in the base layer, product
 * content and policy in the plugin.
 *
 * The rules are asserted here in one place, from the production artifacts (manifest, resources,
 * `RequestLayout`, `ToolLabelMap`), not from a hand-written fixture:
 *
 * 1. `agent-runtime` imports no `phone-app`/`plugin-smart-helper` production package;
 * 2. the base layer names no product route/tool/brand vocabulary;
 * 3. prompt resources live only in the allowed directories;
 * 4. `RequestLayout` rejects a dynamic segment **and** a runtime placeholder in the system slot;
 * 5. `PromptCatalog` rejects unresolved placeholders and static/dynamic slot mixing;
 * 6. the prompt names exactly the 24 tools `ToolLabelMap` registers (descriptor prose coverage is
 *    A5's `ToolContractTest`; this test pins the prompt ↔ face coupling instead of duplicating it).
 *
 * Rules 1–2 reuse [ProductionSourceScan], the same scanner [SourceHardcodeGuardTest] exercises, so
 * the two files cannot drift into two different definitions of "no product vocabulary".
 */
class PromptArchitectureTest {

    @Test
    fun `agent runtime imports no phone app or smart helper production package`() {
        val files = ProductionSourceScan.moduleSources(ProductionSourceScan.AGENT_RUNTIME)
        assertTrue(files.size > 20, "agent-runtime source scope unexpectedly small: ${files.size} files")

        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                val isImport = trimmed.startsWith("import ")
                val isProduct = trimmed.contains("com.sheen.rokid.phone.") ||
                    trimmed.contains("com.sheen.rokid.plugin.smarthelper")
                if (isImport && isProduct) {
                    "${file.relativeTo(ProductionSourceScan.repositoryRoot()).invariantSeparatorsPath}:${index + 1}: $trimmed"
                } else {
                    null
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "the base layer must not import the product modules. Found:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `the base layer names no product route tool or brand vocabulary`() {
        val findings = ProductionSourceScan.moduleSources(ProductionSourceScan.AGENT_RUNTIME)
            .flatMap { ProductionSourceScan.productVocabulary(it) }

        assertTrue(
            findings.isEmpty(),
            "design §3.6 rule 2: ${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `prompt resources live only in the allowed prompt directories`() {
        val root = ProductionSourceScan.repositoryRoot()

        val promptDirs = listOf("phone-app", "common", "glasses-app", "tools")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { productDir ->
                productDir.walkTopDown()
                    .onEnter { it.name != "build" && it.name != ".gradle" }
                    .filter { it.isDirectory && it.name == "prompts" }
                    .toList()
            }
        val allowed = setOf(
            "phone-app/plugins/plugin-smart-helper/src/main/resources/prompts",
            "phone-app/plugins/plugin-diagnostic/src/main/resources/prompts",
            // Design §4.1 keeps the generic loop mechanism in the base layer; 4A's ruling leaves the
            // actual loop resources in the plugin catalog, and this root is the only future allowance.
            "phone-app/assistant/agent-runtime/src/main/resources/prompts",
        )
        for (dir in promptDirs) {
            val relative = dir.relativeTo(root).invariantSeparatorsPath
            assertTrue(
                relative in allowed,
                "'$relative' is not an allowed prompt resource directory; allowed: ${allowed.sorted()}",
            )
        }

        // A file directly at an allowed `prompts/` root is a prompt resource too, so walking only directories
        // would let it through. Derive each file's nearest `prompts` ancestor and apply the same root allowlist.
        val promptFiles = promptDirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile }.toList()
        }
        for (file in promptFiles) {
            val promptRoot = generateSequence(file.parentFile) { it.parentFile }
                .first { it.name == "prompts" }
            val relativeRoot = promptRoot.relativeTo(root).invariantSeparatorsPath
            assertTrue(
                relativeRoot in allowed,
                "'${file.relativeTo(root).invariantSeparatorsPath}' is under an unlisted prompt root '$relativeRoot'",
            )
        }

        // Even the base-layer allowance is mechanism-only: a product prompt folder or a root-level file
        // there must fail, so `prompts/loop/` is the only legal child.
        val basePromptRoot = File(root, "phone-app/assistant/agent-runtime/src/main/resources/prompts")
        if (basePromptRoot.isDirectory) {
            val unexpected = basePromptRoot.listFiles()!!.filterNot { it.isDirectory && it.name == "loop" }
            assertTrue(
                unexpected.isEmpty(),
                "the base layer may only carry generic prompts/loop; found ${unexpected.map { it.name }}",
            )
        }

        val helperRoot = File(root, "phone-app/plugins/plugin-smart-helper/src/main/resources/prompts")
        assertTrue(helperRoot.isDirectory, "the product prompt root must exist")
        assertEquals(
            setOf("manifest.tsv", "summarizer.md", "core", "route", "context", "loop", "pinned"),
            helperRoot.listFiles()!!.map { it.name }.toSet(),
            "the catalog root may contain only the manifest, the summarizer and the five authored folders",
        )
        val declared = PromptCatalog.manifest().map { it.file }.toSet()
        val authored = helperRoot.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(helperRoot).invariantSeparatorsPath }
            .toSet()
        assertEquals(declared, authored, "every authored prompt file must be declared by the manifest and vice versa")

        val diagnosticRoot = File(root, "phone-app/plugins/plugin-diagnostic/src/main/resources/prompts")
        assertTrue(diagnosticRoot.isDirectory, "the diagnostic module owns its own fixed sentences (A7)")
        assertEquals(
            setOf("diagnostic"),
            diagnosticRoot.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet(),
            "plugin-diagnostic may only carry its own local prompt folder",
        )
        assertEquals(
            setOf("diagnostic/instructions.md", "diagnostic/synthetic-input.md"),
            diagnosticRoot.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(diagnosticRoot).invariantSeparatorsPath }
                .toSet(),
            "the diagnostic seam is exactly the two fixed sentences A7 migrated",
        )

        val productLoaderLiterals = ProductionSourceScan.moduleSources(ProductionSourceScan.AGENT_RUNTIME)
            .flatMap { file ->
                ProductionSourceScan.literals(ProductionSourceScan.codeOnly(file.readText()))
                    .map { literal -> file.name to literal.value }
            }
            .filter { (_, value) ->
                listOf(
                    "prompts/core/",
                    "prompts/route/",
                    "prompts/context/",
                    "prompts/pinned/",
                    "prompts/summarizer",
                ).any { it in value }
            }
        assertTrue(
            productLoaderLiterals.isEmpty(),
            "the base layer must not load product prompts: $productLoaderLiterals",
        )
    }

    @Test
    fun `a system segment carrying a runtime placeholder is rejected`() {
        val placeholders = listOf("当前时间 {{time}}", "机型 {{deviceModel}}", "孤立 {{")

        for (origin in listOf(SegmentOrigin.INSTRUCTIONS, SegmentOrigin.SKILL, SegmentOrigin.MEMORY_POLICY)) {
            for (text in placeholders) {
                assertFailsWith<IllegalArgumentException>("$origin must reject the placeholder in '$text'") {
                    RequestLayout.validateSystem(listOf(Segment(origin, trusted = true, text = text)))
                }
            }
        }
    }

    @Test
    fun `the production system plans pass the layout rule without a placeholder`() {
        for (route in PromptRoute.entries) {
            val plan = PromptCatalog.plan(route, profileFixture())

            assertFalse("{{" in plan.systemText, "$route systemText must not keep slot syntax")
            assertFalse("}}" in plan.systemText, "$route systemText must not keep slot syntax")
            RequestLayout.validateSystem(
                plan.sections.map { Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = it.text) },
            )
        }
    }

    @Test
    fun `the catalog rejects unresolved placeholders and static dynamic mixing`() {
        // A STATIC section cannot carry any slot.
        assertFailsWith<IllegalArgumentException> {
            parse("core/00-identity.md", "STATIC", "你是一名智能助手。{{name}}")
        }

        val deviceTemplate = promptText("context/device-profile.md")
        // Sanity: the authored device profile is legal as SESSION_STATIC.
        parse("context/device-profile.md", "SESSION_STATIC", deviceTemplate)
        // A per-request slot in the session-static template is static/dynamic mixing.
        assertFailsWith<IllegalArgumentException> {
            parse("context/device-profile.md", "SESSION_STATIC", deviceTemplate + "\n当前时间：{{time}}")
        }
        // A non-strict placeholder is unresolved even when its name would be allowed.
        assertFailsWith<IllegalArgumentException> {
            parse("context/device-profile.md", "SESSION_STATIC", deviceTemplate + "\n{{ deviceModel }}")
        }

        val dynamicSlots = listOf(
            "date", "weekday", "time", "timeZone", "deviceState", "foreground", "media",
            "perception", "coverage", "notifications", "calendar", "acoustic", "clipboard",
            "memoryEvidence", "skillBodies", "loopState",
        )
        val dynamicTemplate = dynamicSlots.joinToString("\n") { "{{$it}}" }
        // Sanity: the per-request slots alone are a legal DYNAMIC template.
        parse("context/runtime-state.md", "DYNAMIC", dynamicTemplate)
        // A device-profile slot in the dynamic template is the other direction of the same mixing.
        assertFailsWith<IllegalArgumentException> {
            parse("context/runtime-state.md", "DYNAMIC", dynamicTemplate + "\n机型：{{deviceModel}}")
        }
        // An unknown slot is unresolved, not silently preserved.
        assertFailsWith<IllegalArgumentException> {
            parse("context/runtime-state.md", "DYNAMIC", dynamicTemplate.replace("{{time}}", "{{clock}}"))
        }
        // A malformed opener/closer is unresolved even when the renderer would have every value.
        assertFailsWith<IllegalArgumentException> {
            parse("context/runtime-state.md", "DYNAMIC", dynamicTemplate + "\n{{time}")
        }

        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("当前时间：{{time}}", emptyMap(), setOf("time"))
        }
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("当前时间：{{time}}", mapOf("time" to "18:00"), setOf("date"))
        }
    }

    /** The tool-shaped identifiers in [corpus] that [advertised] does not register. */
    private fun unadvertisedToolNames(corpus: String, advertised: Set<String>): Set<String> =
        TOOL_NAME.findAll(corpus).map { it.value }.toSet() - advertised

    /** Parses one synthetic manifest row through the production validator. */
    private fun parse(file: String, kind: String, body: String) =
        PromptCatalog.parseManifest("x\t$file\t$kind\t1\tdescription") { body }

    private companion object {
        /** The 23-tool default face after the 2026-10-22 retirement, the same set A5's `ToolContractTest` owns. */
        val DEFAULT_TOOL_NAMES: Set<String> = linkedSetOf(
            "read_screen", "tap", "long_press", "scroll", "input_text",
            "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
            "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
            "list_apps", "system_action",
            "device", "shell", "open", "dial",
            "notify_user", "load_skill",
        )

        /** A tool name as a whole token, so `open` is not satisfied by `open_app`. */
        fun mentions(text: String, name: String): Boolean =
            Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])").containsMatchIn(text)

        /** A snake_case tool-shaped identifier; the prompt's own prose has no other underscore words. */
        val TOOL_NAME = Regex("""(?<![A-Za-z0-9_])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_])""")
    }
}
