package com.sheen.rokid.phone.plugin.diagnostic

import java.nio.charset.StandardCharsets

/**
 * This plugin's own fixed model-facing sentences (0.3 design §4.5: "`DiagnosticTextPlugin` 固定句 → 插件资源").
 *
 * **Module-local on purpose.** `plugin-diagnostic` is an independent diagnostic plugin and must not import
 * `plugin-smart-helper`'s `PromptCatalog`: that would couple two plugins for two sentences and would make the
 * diagnostic build depend on the assistant's prompt surface. The files under `prompts/diagnostic/` are this module's
 * own classpath resource seam, not a second catalog.
 *
 * Loading is normalised the same way `PromptCatalog` normalises its resources (CRLF → LF, trim) so a Windows checkout
 * and a Linux build send identical bytes. A missing resource fails at class-initialisation time — a diagnostic plugin
 * that silently lost its prompt would be a shipped test measuring nothing.
 */
internal object DiagnosticPrompts {
    /** `INSTRUCTIONS`: the fixed one-sentence instruction of a diagnostic request. */
    val instructions: String = load("instructions.md")

    /** The fixed input sent instead of a transcript in synthetic mode. */
    val syntheticInput: String = load("synthetic-input.md")

    private fun load(name: String): String {
        val path = "/prompts/diagnostic/$name"
        val stream = requireNotNull(DiagnosticPrompts::class.java.getResourceAsStream(path)) {
            "the diagnostic plugin must ship $path"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }
}
