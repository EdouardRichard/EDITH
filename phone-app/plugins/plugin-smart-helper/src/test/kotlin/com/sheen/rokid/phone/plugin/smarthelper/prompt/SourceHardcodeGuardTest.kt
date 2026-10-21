package com.sheen.rokid.phone.plugin.smarthelper.prompt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.3 design §3.6 rule 2, §4.6 (`PromptHardcodeGuardTest`, source half) and §9.3/§9.4: model-facing prompt
 * prose lives in versioned resources, not in `src/main/kotlin`.
 *
 * **Scope.** `src/main/kotlin` of the three modules that own the assistant prompt surface:
 * `agent-runtime` (base mechanism), `plugin-smart-helper` (product content) and `plugin-diagnostic`
 * (its own fixed sentences, per A7). Build outputs, `.worktrees` and tests are out of scope.
 *
 * **Method.** The scan is deliberately layered rather than "ban every Chinese literal":
 *
 * 1. comments are stripped before the literal checks, because prose in a KDoc cannot reach the model.
 *    The one exception is the base layer's product vocabulary: a KDoc that names `WAKE`/`PROACTIVE`
 *    is itself an A′ layering leak (D175), so that check reads the raw file;
 * 2. prompt construction patterns banned by §4.6 are rejected anywhere: `SmartPrompts`,
 *    `PROACTIVE_INSTRUCTION`, a `你是一名`/`You are` identity opener, or a Chinese literal assigned to
 *    `instructions`/`prompt`;
 * 3. an authored prompt line (≥20 Han characters, or an English loop instruction of ≥20 letters) must
 *    not reappear verbatim in a source literal, and the files that load/select prompt resources must
 *    not carry paragraph-scale inline prompt prose;
 * 4. user-facing copy is exempt only at four named sites, each scoped to the files that own it:
 *    [ToolLabelMap] labels, `ActionDescriptor.title` in the descriptor files, the confirmation summary
 *    in `SmartAssistantPlugin`, and `SmartConfirmation.APPROVALS`/`DENIALS`. These are localizable UI
 *    strings, not model instructions (design §4.4 keeps the two channels separate). The allowlist is
 *    exact line-shaped, not a file-wide exclusion, and [the user copy allowlist is bounded to four named sites]
 *    proves each entry alone is allowed;
 * 5. the plugin half of rule 2 scans **literals** (comments and identifiers are not prompt prose in the
 *    module that owns the product vocabulary). Two genuine non-prompt literals are exempted line-shaped:
 *    the `propose_hint` wire name and the `/sdcard/EDITH/skills` storage path;
 * 6. the three English loop defaults are authored prompt prose too, although the ≥20-Han corpus would
 *    not see them; `AgentLoop.kt` is their only permitted mirror and a byte-equality test ties it to
 *    `PromptCatalog.loop`.
 *
 * A blanket "no Chinese literal" test would be dishonest here: tool-schema prose (pinned by A5's
 * `ToolContractTest`), memory keyword tables and phonetic/app-name synonyms legitimately stay in
 * Kotlin. The guard therefore pins the *prompt* boundary, and the report records that limitation.
 */
class SourceHardcodeGuardTest {

    @Test
    fun `the base layer carries no product route or brand vocabulary`() {
        val files = ProductionSourceScan.moduleSources(ProductionSourceScan.AGENT_RUNTIME)
        assertTrue(files.size > 20, "agent-runtime source scope unexpectedly small: ${files.size} files")

        val findings = files.flatMap { ProductionSourceScan.productVocabulary(it) }

        assertTrue(
            findings.isEmpty(),
            "the base layer must not name product routes, tools or brand. Found:\n${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `plugin sources carry no product vocabulary outside the named tool and path literals`() {
        val files = ProductionSourceScan.pluginSources()
        assertTrue(files.size > 40, "plugin main-source scope unexpectedly small: ${files.size} files")

        val findings = files.flatMap { ProductionSourceScan.pluginProductVocabulary(it) }

        assertTrue(
            findings.isEmpty(),
            "the plugin may name product routes in code and comments, but not in a prompt literal. " +
                "Found:\n${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `the plugin vocabulary scan is line shaped and comment aware`() {
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "Fixture.kt",
                "private val instructions = \"你是 EDITH，先观察再行动。\"",
            ).isNotEmpty(),
            "a plugin prompt literal naming the product must be rejected",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "Fixture.kt",
                "private val route = \"WAKE 时先观察\"",
            ).isNotEmpty(),
            "a plugin prompt literal naming a product route must be rejected",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "Fixture.kt",
                "// EDITH 是产品名，注释不是提示词",
            ).isEmpty(),
            "a comment is not prompt content",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "Fixture.kt",
                "private val route = PromptRoute.WAKE",
            ).isEmpty(),
            "an identifier is code, not a prompt literal",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "Fixture.kt",
                """const val NAME = "propose_hint"""",
            ).isNotEmpty(),
            "the tool-name literal is exempt only at its named owner, not file-wide",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "ProactiveHintSpec.kt",
                """    const val NAME = "propose_hint"""",
            ).isEmpty(),
            "the propose_hint tool name is a genuine non-prompt literal",
        )
        assertTrue(
            ProductionSourceScan.pluginProductVocabulary(
                "StorageAccessSkillDirectory.kt",
                """    const val SKILL_DIRECTORY = "/sdcard/EDITH/skills"""",
            ).isEmpty(),
            "the EDITH skill path is a genuine non-prompt literal",
        )
    }

    @Test
    fun `main sources carry no legacy prompt construction outside the resources`() {
        val files = ProductionSourceScan.allModuleSources()
        assertTrue(files.size > 60, "main-source scan scope unexpectedly small: ${files.size} files")

        val findings = files.flatMap { ProductionSourceScan.legacyPromptConstructs(it) }

        assertTrue(
            findings.isEmpty(),
            "prompt prose must come from the versioned resources. Found:\n${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `main sources do not duplicate an authored prompt line`() {
        val authoredLines = authoredPromptLines()
        assertTrue(authoredLines.size > 20, "the authored prompt corpus unexpectedly small: ${authoredLines.size} lines")

        val findings = ProductionSourceScan.allModuleSources()
            .flatMap { file -> ProductionSourceScan.duplicatedPromptLines(file, authoredLines) }

        assertTrue(
            findings.isEmpty(),
            "an authored prompt line was copied back into Kotlin. Found:\n${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `the authored corpus covers the non Han loop defaults`() {
        val corpus = authoredPromptLines()

        for (key in listOf("retry", "plan-nudge", "timeout")) {
            assertTrue(
                PromptCatalog.loop(key) in corpus,
                "loop/$key.md must stay in the authored-line corpus, or its copy in Kotlin becomes invisible",
            )
        }
    }

    @Test
    fun `the agent loop defaults are byte identical to the loop resources`() {
        // The three constants are file-private in AgentLoop.kt, so this plugin-side test ties their bytes at the
        // source level instead of widening production visibility inside a guard-only round.
        val source = File(
            ProductionSourceScan.repositoryRoot(),
            "${ProductionSourceScan.AGENT_RUNTIME}/src/main/kotlin/com/sheen/rokid/agent/AgentLoop.kt",
        ).readText()
        val code = ProductionSourceScan.codeOnly(source)

        assertEquals(PromptCatalog.loop("retry"), ProductionSourceScan.constantLiteral(code, "RETRY_INSTRUCTION"))
        assertEquals(
            PromptCatalog.loop("plan-nudge"),
            ProductionSourceScan.constantLiteral(code, "PLAN_NUDGE_INSTRUCTION"),
        )
        assertEquals(PromptCatalog.loop("timeout"), ProductionSourceScan.constantLiteral(code, "ACTION_TIMEOUT_NOTE"))
    }

    @Test
    fun `the AgentLoop loop default whitelist is line shaped`() {
        val retry = PromptCatalog.loop("retry")

        assertTrue(
            ProductionSourceScan.isLoopDefaultMirror("AgentLoop.kt", "    \"$retry\""),
            "the AgentLoop retry default is the intentional mirror of loop/retry.md",
        )
        assertFalse(
            ProductionSourceScan.isLoopDefaultMirror("Fixture.kt", "    \"$retry\""),
            "the loop-default exemption is scoped to AgentLoop.kt",
        )
        assertFalse(
            ProductionSourceScan.isLoopDefaultMirror("AgentLoop.kt", "    \"你是一名智能助手。\""),
            "the loop-default exemption is the authored line, not any literal in AgentLoop.kt",
        )
    }

    @Test
    fun `prompt owning sources carry no inline paragraph of prompt prose`() {
        val files = ProductionSourceScan.promptOwningSources()
        assertTrue(files.size > 6, "prompt-owning source scope unexpectedly small: ${files.size} files")

        val findings = files.flatMap { ProductionSourceScan.inlinePromptProse(it) }

        assertTrue(
            findings.isEmpty(),
            "prompt-owning source carries inline prompt prose. Found:\n${ProductionSourceScan.render(findings)}",
        )
    }

    @Test
    fun `the scanner rejects every prompt pattern the design names`() {
        // §4.6 minimum: "你是一名, "You are, instructions = ", PROACTIVE_INSTRUCTION (and its owner SmartPrompts).
        val fixtures = mapOf(
            "SmartPrompts owner" to "private val prompts = SmartPrompts.instructions()",
            "PROACTIVE_INSTRUCTION" to "private val text = PROACTIVE_INSTRUCTION",
            "Chinese identity opener" to "private val text = \"你是一名智能眼镜助手。\"",
            "English identity opener" to "private val text = \"You are a helpful assistant.\"",
            "inline instructions assignment" to "val request = AgentRequest(instructions = \"回答要简短。\")",
            "inline prompt assignment" to "private val prompt = \"先观察，再行动。\"",
        )

        fixtures.forEach { (label, source) ->
            assertTrue(
                ProductionSourceScan.legacyPromptConstructs("Fixture.kt", source).isNotEmpty(),
                "$label must be rejected by the source guard",
            )
        }

        // A comment is not prompt content: stripping comments must keep the scan honest rather than noisy.
        assertTrue(
            ProductionSourceScan.legacyPromptConstructs(
                "Fixture.kt",
                "// TODO use SmartPrompts or \"你是一名...\" here one day",
            ).isEmpty(),
            "comment-only text must not be reported as a hardcoded prompt",
        )

        // The two content-based checks are not vacuous either: a paragraph pasted into a prompt-owning file is
        // both inline prose and a duplicate of the authored line it was copied from.
        val paragraph = "回答时先观察再行动，确认界面真的发生变化后再汇报结果，不要只看工具返回。"
        assertTrue(
            ProductionSourceScan.inlinePromptProse("Fixture.kt", "val text = \"$paragraph\"").isNotEmpty(),
            "a paragraph-scale inline prompt must be rejected",
        )
        assertTrue(
            ProductionSourceScan.duplicatedPromptLines("Fixture.kt", "val text = \"$paragraph\"", listOf(paragraph))
                .isNotEmpty(),
            "a copied authored prompt line must be rejected",
        )
        assertTrue(
            ProductionSourceScan.inlinePromptProse("Fixture.kt", "// \"$paragraph\"").isEmpty(),
            "the same paragraph in a comment must not be treated as prompt content",
        )
    }

    @Test
    fun `the user copy allowlist is bounded to four named sites`() {
        val allowed = listOf(
            "ToolLabelMap" to ("ToolLabelMap.kt" to """        "open_app" to "打开应用","""),
            "ActionDescriptor title" to ("NotifyUser.kt" to """        title = "向用户发送通知","""),
            "confirmation summary" to (
                "SmartAssistantPlugin.kt" to
                    """        private const val DEFAULT_SUMMARY = "终端命令：无法确认是否安全""""
                ),
            "SmartConfirmation approvals" to (
                "SmartConfirmation.kt" to """        val APPROVALS = setOf("确认", "允许")"""
                ),
            "SmartConfirmation denials" to (
                "SmartConfirmation.kt" to """        val DENIALS = setOf("拒绝", "取消")"""
                ),
        )

        allowed.forEach { (label, sample) ->
            val (file, line) = sample
            assertTrue(
                ProductionSourceScan.isUserCopy(file, line),
                "$label must be allowlisted as user-facing copy",
            )
        }

        // The same sentence outside its named site is not user copy, so it stays in the guard's scope.
        assertFalse(
            ProductionSourceScan.isUserCopy("AgentLoop.kt", """        "open_app" to "打开应用","""),
            "a ToolLabelMap-shaped line outside ToolLabelMap.kt must not be allowlisted",
        )
        assertFalse(
            ProductionSourceScan.isUserCopy("SmartAssistantPlugin.kt", """        val instructions = "打开应用""""),
            "a prompt assignment must not borrow the user-copy allowlist",
        )
        assertFalse(
            ProductionSourceScan.isUserCopy("SmartConfirmation.kt", """        val APPROVALS = setOf("你是一名")"""),
            "the approval allowlist is the set line, not any line in the file that mentions APPROVALS",
        )
        assertFalse(
            ProductionSourceScan.isUserCopy("AgentLoop.kt", """        title = "你是一名智能助手","""),
            "a title assignment outside the descriptor files is not user copy",
        )
        assertFalse(
            ProductionSourceScan.isUserCopy("AgentLoop.kt", """        val summary = "你是一名智能助手""""),
            "a summary assignment outside the confirmation owner is not user copy",
        )
    }

    /**
     * The authored prompt lines substantial enough that a copy in Kotlin is a prompt, not a short token:
     * ≥20 Han characters, or an English loop instruction of ≥20 letters.
     */
    private fun authoredPromptLines(): List<String> =
        PromptCatalog.manifest()
            .flatMap { entry -> promptText(entry.file).lines() }
            .map { it.trim() }
            .filter(::isAuthoredPromptLine)
            .distinct()

    private fun isAuthoredPromptLine(line: String): Boolean {
        if (line.count { it in '\u4e00'..'\u9fff' } >= 20) return true
        // The loop defaults are English translations of the same instructions; without this branch the
        // Han-only corpus would silently ignore the three texts the loop actually feeds the model.
        val asciiLetters = line.count { it in 'A'..'Z' || it in 'a'..'z' }
        return asciiLetters >= 20 && "{{" !in line && !line.startsWith("#")
    }
}

/**
 * The source scanner shared by the architecture test and this guard. It is production-code-aware but
 * test-only: comments are stripped with a small lexer (string/char literals survive, so `//` inside a
 * literal is not a comment), and every finding names the file, line, rule and offending snippet.
 */
internal object ProductionSourceScan {

    const val AGENT_RUNTIME = "phone-app/assistant/agent-runtime"
    const val SMART_HELPER = "phone-app/plugins/plugin-smart-helper"
    const val DIAGNOSTIC = "phone-app/plugins/plugin-diagnostic"

    val modules = listOf(AGENT_RUNTIME, SMART_HELPER, DIAGNOSTIC)

    data class Finding(val file: String, val line: Int, val rule: String, val snippet: String)

    data class Literal(val line: Int, val value: String)

    private val HAN = '\u4e00'..'\u9fff'

    /** Whole-token product vocabulary: `WAKE` is a route, `wakeWord` is ordinary base-layer vocabulary. */
    private val PRODUCT_VOCABULARY = Regex("""(?<![A-Za-z0-9_])(WAKE|PROACTIVE|propose_hint|SmartPrompts|EDITH)(?![A-Za-z0-9_])""")

    /** The prompt constants §4.6 names as migrated. */
    private val LEGACY_PROMPT_CONSTRUCT = Regex("""\b(SmartPrompts|PROACTIVE_INSTRUCTION)\b""")

    private val PROMPT_OPENERS = listOf("你是一名", "You are")

    /** A prompt copied into a request/prompt assignment. `==` and `+=` are not assignments. */
    private val PROMPT_ASSIGNMENT = Regex("""\b(?:instructions|prompt)\s*=(?!=)\s*""")

    private val TOOL_LABEL_ENTRY = Regex("""^\s*"[A-Za-z0-9_]+"\s+to\s+"[^"]*",?\s*$""")
    private val TITLE_ASSIGNMENT = Regex("""\btitle\s*=\s*"[^"]*",?\s*$""")
    private val CONFIRMATION_SUMMARY = Regex("""\bDEFAULT_SUMMARY\s*=\s*"[^"]*"|^\s*val\s+summary\s*=\s*"[^"]*",?\s*$""")

    /** Files that construct an [ActionDescriptor], and therefore own `title =` user copy. */
    private val TITLE_OWNING_FILES = setOf("NotifyUser.kt", "LoadSkillTool.kt", "ProactiveHintSpec.kt")

    /** Files that own the confirmation card's summary copy. */
    private val CONFIRMATION_SUMMARY_FILES = setOf("SmartAssistantPlugin.kt")

    /** A named, whole-line exemption: the name is the reason, the file and pattern bound it. */
    private data class LiteralExemption(val name: String, val file: String, val pattern: Regex) {
        fun matches(fileName: String, line: String): Boolean =
            file == fileName && pattern.matches(line.trim())
    }

    /** Genuine plugin literals that are values, not prose: a wire tool name and a storage path. */
    private val NON_PROMPT_PRODUCT_LITERALS = listOf(
        LiteralExemption(
            "propose_hint wire tool name (ProactiveHintSpec.NAME)",
            "ProactiveHintSpec.kt",
            Regex("""^const val NAME = "propose_hint"$"""),
        ),
        LiteralExemption(
            "EDITH skill storage path (StorageAccessSkillDirectory.SKILL_DIRECTORY)",
            "StorageAccessSkillDirectory.kt",
            Regex("""^const val SKILL_DIRECTORY = "/sdcard/EDITH/skills"$"""),
        ),
    )

    /** The three intentional copies of the loop resources that AgentLoop.kt keeps as constructor defaults. */
    private val LOOP_DEFAULT_MIRRORS = listOf(
        LiteralExemption(
            "AgentLoop RETRY_INSTRUCTION mirrors loop/retry.md",
            "AgentLoop.kt",
            Regex("""^"${Regex.escape(PromptCatalog.loop("retry"))}"$"""),
        ),
        LiteralExemption(
            "AgentLoop PLAN_NUDGE_INSTRUCTION mirrors loop/plan-nudge.md",
            "AgentLoop.kt",
            Regex("""^"${Regex.escape(PromptCatalog.loop("plan-nudge"))}"$"""),
        ),
        LiteralExemption(
            "AgentLoop ACTION_TIMEOUT_NOTE mirrors loop/timeout.md",
            "AgentLoop.kt",
            Regex("""^"${Regex.escape(PromptCatalog.loop("timeout"))}"$"""),
        ),
    )

    fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repository root not found from ${File("").absolutePath}")

    fun moduleSources(module: String): List<File> {
        val root = File(repositoryRoot(), "$module/src/main/kotlin")
        assertTrue(root.isDirectory, "missing source root: $root")
        return root.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    fun allModuleSources(): List<File> = modules.flatMap(::moduleSources)

    /** The two product modules design rule 2's plugin half covers. */
    fun pluginSources(): List<File> = listOf(SMART_HELPER, DIAGNOSTIC).flatMap(::moduleSources)

    /**
     * A heuristic surface for the inline-prose check: every `prompt` package source plus the five named
     * callers. It does not prove that no other file holds prompt prose — the duplicated-line check still
     * covers every main source — but it bounds which files the paragraph-scale check visits.
     */
    fun promptOwningSources(): List<File> {
        val inPromptPackage = allModuleSources().filter { file ->
            val path = file.invariantSeparatorsPath
            "/prompt/" in path ||
                file.name in setOf(
                    "AgentLoop.kt",
                    "ProactiveGate.kt",
                    "ModelSummarizer.kt",
                    "DiagnosticTextPlugin.kt",
                    "DiagnosticPrompts.kt",
                )
        }
        return inPromptPackage.distinctBy { it.path }
    }

    fun render(findings: List<Finding>): String =
        if (findings.isEmpty()) {
            "(none)"
        } else {
            findings.joinToString("\n") { finding ->
                "${finding.file}:${finding.line} [${finding.rule}] ${finding.snippet}"
            }
        }

    /**
     * Design rule 2's base-layer half. Read **raw** (comments included): naming a product route in
     * base-layer prose is the layering leak this rule exists to prevent.
     */
    fun productVocabulary(file: File): List<Finding> =
        file.readLines().mapIndexedNotNull { index, line ->
            val match = PRODUCT_VOCABULARY.find(line) ?: return@mapIndexedNotNull null
            Finding(
                file.relativeTo(repositoryRoot()).invariantSeparatorsPath,
                index + 1,
                "product vocabulary '${match.value}'",
                line.trim().take(180),
            )
        }

    /**
     * Design rule 2's plugin half. The plugin owns the product vocabulary (`PromptRoute.WAKE`,
     * `AssistantRoute.PROACTIVE`), so identifiers and comments are legitimate there; what must not exist is
     * a **literal** carrying product prose the model could read. Comments are stripped first. The two
     * genuine non-prompt literals (a wire tool name and a storage path) are exempted line-shaped, not
     * file-wide, so the same text in another file still fails.
     */
    fun pluginProductVocabulary(file: File): List<Finding> =
        pluginProductVocabulary(file.name, file.readText())

    fun pluginProductVocabulary(fileName: String, source: String): List<Finding> {
        val code = codeOnly(source)
        val lines = code.lines()
        return literals(code).mapNotNull { literal ->
            val match = PRODUCT_VOCABULARY.find(literal.value) ?: return@mapNotNull null
            val lineText = lines.getOrNull(literal.line - 1).orEmpty()
            if (isNonPromptProductLiteral(fileName, lineText)) return@mapNotNull null
            Finding(
                fileName,
                literal.line,
                "product vocabulary '${match.value}' in a literal",
                literal.value.take(180),
            )
        }
    }

    /** Comment-aware legacy prompt construction scan. */
    fun legacyPromptConstructs(file: File): List<Finding> =
        legacyPromptConstructs(file.name, file.readText())

    fun legacyPromptConstructs(fileName: String, source: String): List<Finding> {
        val code = codeOnly(source)
        val lines = code.lines()
        val findings = mutableListOf<Finding>()

        lines.forEachIndexed { index, line ->
            if (isUserCopy(fileName, line)) return@forEachIndexed
            val match = LEGACY_PROMPT_CONSTRUCT.find(line) ?: return@forEachIndexed
            findings += Finding(fileName, index + 1, "legacy prompt constant '${match.value}'", line.trim().take(180))
        }

        for (literal in literals(code)) {
            val lineText = lines.getOrNull(literal.line - 1).orEmpty()
            if (isUserCopy(fileName, lineText)) continue
            val opener = PROMPT_OPENERS.firstOrNull { it in literal.value } ?: continue
            findings += Finding(fileName, literal.line, "prompt identity opener '$opener'", literal.value.take(180))
        }

        for (match in PROMPT_ASSIGNMENT.findAll(code)) {
            if (match.range.first > 0 && code[match.range.first - 1] == '+') continue
            val line = code.substring(0, match.range.first).count { it == '\n' } + 1
            if (isUserCopy(fileName, lines.getOrNull(line - 1).orEmpty())) continue
            val rest = code.substring(match.range.last + 1).trimStart('\n', '\r', ' ', '\t')
            if (!rest.startsWith("\"\"\"") && !rest.startsWith("\"")) continue
            val literal = literals(rest).firstOrNull()?.value ?: continue
            if (literal.none { it in HAN }) continue
            findings += Finding(fileName, line, "inline prompt assignment", literal.take(180))
        }

        return findings.distinctBy { Triple(it.line, it.rule, it.snippet) }
    }

    /** An authored prompt line (≥20 Han, or an English loop instruction) must not reappear in a Kotlin literal. */
    fun duplicatedPromptLines(file: File, authoredLines: List<String>): List<Finding> =
        duplicatedPromptLines(file.name, file.readText(), authoredLines)

    fun duplicatedPromptLines(fileName: String, source: String, authoredLines: List<String>): List<Finding> {
        val code = codeOnly(source)
        val lines = code.lines()
        val findings = mutableListOf<Finding>()
        for (literal in literals(code)) {
            val lineText = lines.getOrNull(literal.line - 1).orEmpty()
            if (isUserCopy(fileName, lineText)) continue
            if (isLoopDefaultMirror(fileName, lineText)) continue
            val duplicated = authoredLines.firstOrNull { it in literal.value } ?: continue
            findings += Finding(
                fileName,
                literal.line,
                "duplicated authored prompt line",
                duplicated.take(180),
            )
        }
        return findings
    }

    /** Paragraph-scale inline prompt prose in a prompt-owning file (≥20 Han characters). */
    fun inlinePromptProse(file: File): List<Finding> = inlinePromptProse(file.name, file.readText())

    fun inlinePromptProse(fileName: String, source: String): List<Finding> {
        val code = codeOnly(source)
        val lines = code.lines()
        return literals(code).mapNotNull { literal ->
            if (isUserCopy(fileName, lines.getOrNull(literal.line - 1).orEmpty())) return@mapNotNull null
            if (literal.value.count { it in HAN } < 20) return@mapNotNull null
            Finding(
                fileName,
                literal.line,
                "inline prompt prose",
                literal.value.take(180),
            )
        }
    }

    /**
     * The only Kotlin text allowed to be user-facing copy rather than model prompt prose. Each branch is
     * line-shaped **and** scoped to the file that owns that copy, so the same assignment in another file
     * cannot hide behind it.
     */
    fun isUserCopy(fileName: String, line: String): Boolean {
        val trimmed = line.trim()
        return when (fileName) {
            "ToolLabelMap.kt" -> TOOL_LABEL_ENTRY.matches(line)
            "SmartConfirmation.kt" -> {
                (trimmed.startsWith("val APPROVALS =") || trimmed.startsWith("val DENIALS =")) &&
                    listOf("\"确认\"", "\"允许\"", "\"拒绝\"", "\"取消\"").any { it in line }
            }
            in TITLE_OWNING_FILES -> TITLE_ASSIGNMENT.containsMatchIn(line)
            in CONFIRMATION_SUMMARY_FILES -> CONFIRMATION_SUMMARY.containsMatchIn(line)
            else -> false
        }
    }

    /** A product token in a literal that is genuinely a value (tool name or path), not prompt prose. */
    fun isNonPromptProductLiteral(fileName: String, line: String): Boolean =
        NON_PROMPT_PRODUCT_LITERALS.any { it.matches(fileName, line) }

    /** The three loop defaults AgentLoop.kt intentionally mirrors from the catalog resources. */
    fun isLoopDefaultMirror(fileName: String, line: String): Boolean =
        LOOP_DEFAULT_MIRRORS.any { it.matches(fileName, line) }

    /**
     * The first string literal after `private const val <name> =` in [code]. The loop defaults are
     * file-private, so a test in another module cannot read them through Kotlin; this ties their bytes at
     * the source level instead.
     */
    fun constantLiteral(code: String, name: String): String {
        val declaration = Regex("""\bprivate const val $name\s*=\s*""").find(code)
            ?: error("constant '$name' not found")
        return literals(code.substring(declaration.range.last + 1)).firstOrNull()?.value
            ?: error("constant '$name' has no string literal")
    }

    /** Removes comments while preserving string/char literals and the line structure. */
    fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> {
                    if (text.startsWith("\"\"\"", index)) {
                        out.append("\"\"\"")
                        index += 3
                        while (index < text.length) {
                            if (text.startsWith("\"\"\"", index)) {
                                out.append("\"\"\"")
                                index += 3
                                break
                            }
                            out.append(text[index])
                            index++
                        }
                    } else {
                        out.append(char)
                        index++
                        var escaped = false
                        while (index < text.length) {
                            val next = text[index]
                            out.append(next)
                            index++
                            if (escaped) {
                                escaped = false
                            } else if (next == '\\') {
                                escaped = true
                            } else if (next == '"' || next == '\n') {
                                break
                            }
                        }
                    }
                }
                char == '\'' -> {
                    out.append(char)
                    index++
                    var escaped = false
                    while (index < text.length) {
                        val next = text[index]
                        out.append(next)
                        index++
                        if (escaped) {
                            escaped = false
                        } else if (next == '\\') {
                            escaped = true
                        } else if (next == '\'' || next == '\n') {
                            break
                        }
                    }
                }
                char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    while (index < text.length && text[index] != '\n') {
                        out.append(' ')
                        index++
                    }
                }
                char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    out.append(' ')
                    out.append(' ')
                    index += 2
                    while (index < text.length) {
                        if (text.startsWith("*/", index)) {
                            out.append(' ')
                            out.append(' ')
                            index += 2
                            break
                        }
                        out.append(if (text[index] == '\n') '\n' else ' ')
                        index++
                    }
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** String literal contents with the 1-based line of their opening quote. */
    fun literals(code: String): List<Literal> {
        val literals = mutableListOf<Literal>()
        var index = 0
        var line = 1
        while (index < code.length) {
            val char = code[index]
            when {
                char == '\n' -> {
                    line++
                    index++
                }
                char == '"' -> {
                    val startLine = line
                    if (code.startsWith("\"\"\"", index)) {
                        index += 3
                        val content = StringBuilder()
                        while (index < code.length) {
                            if (code.startsWith("\"\"\"", index)) {
                                index += 3
                                break
                            }
                            if (code[index] == '\n') line++
                            content.append(code[index])
                            index++
                        }
                        literals += Literal(startLine, content.toString())
                    } else {
                        index++
                        val content = StringBuilder()
                        var escaped = false
                        while (index < code.length) {
                            val next = code[index]
                            if (escaped) {
                                content.append(next)
                                escaped = false
                                index++
                                continue
                            }
                            when (next) {
                                '\\' -> {
                                    escaped = true
                                    index++
                                }
                                '"' -> {
                                    index++
                                    break
                                }
                                '\n' -> {
                                    line++
                                    index++
                                    break
                                }
                                else -> {
                                    content.append(next)
                                    index++
                                }
                            }
                        }
                        literals += Literal(startLine, content.toString())
                    }
                }
                else -> index++
            }
        }
        return literals
    }
}
