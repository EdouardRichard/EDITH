package com.sheen.rokid.phone.plugin.smarthelper.skill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec §3.2/§3.3: the SKILL files this repository ships in `docs/project/skills/` must actually **parse**.
 *
 * A SKILL is a data artifact the user drops on the SD card, and the parser refuses a document whose front matter is
 * not exactly the declared subset. Writing one by hand and reading it back with the eye is how a file reaches a device
 * and then silently contributes nothing: `SkillManager` skips an invalid file and keeps the rest, so a broken SKILL
 * looks exactly like "the user installed no SKILL". This test is the check that a shipped SKILL is a loadable one.
 */
class SkillLibraryTest {

    /**
     * The repository root, from the module's working directory (`plugins-smart`).
     *
     * The SKILL lives in `docs/project/skills/` rather than in test resources on purpose: it is a **deliverable** the
     * user copies to `/sdcard/EDITH/skills/`, and a second copy under `src/test/resources` would be a second place to
     * drift from. The test skips loudly rather than passing quietly when it cannot find the root.
     */
    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "docs/project/skills").isDirectory }
            ?: error("repository root not found from ${File("").absolutePath}")

    private fun skillFiles(): List<File> {
        val directory = File(repositoryRoot(), "docs/project/skills")
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".md") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    @Test
    fun `every shipped SKILL parses into the declared subset`() {
        val files = skillFiles()
        assertTrue(files.isNotEmpty(), "no SKILL files found under docs/project/skills")
        files.forEach { file ->
            val outcome = SkillParser.parse(file.name, file.readText(Charsets.UTF_8))
            assertIs<SkillParse.Ok>(
                outcome,
                "${file.name} does not parse: ${(outcome as? SkillParse.Invalid)?.reason}",
            )
        }
    }

    @Test
    fun `a shipped SKILL is enabled, non blank and free of placeholder text`() {
        skillFiles().forEach { file ->
            val skill = (SkillParser.parse(file.name, file.readText(Charsets.UTF_8)) as? SkillParse.Ok)?.skill
                ?: return@forEach
            assertTrue(skill.enabled, "${file.name} would be injected nowhere")
            // C9/D60 inject every enabled body whole into every prompt, so a placeholder is not a note to a reader -
            // it is text the model would read as an instruction.
            listOf("TODO", "TBD", "待补", "XXX", "FIXME").forEach { marker ->
                assertTrue(marker !in skill.body, "${file.name}'s body carries the placeholder $marker")
            }
            // The front matter's identity is a kebab-case slug and the file name mirrors it, so a reader can find the
            // skill a prompt is talking about. The parser does not require this; the library does.
            assertTrue(
                skill.name == file.nameWithoutExtension,
                "${file.name} declares name=${skill.name}; the file name and the identity must agree",
            )
            assertTrue(
                skill.name.all { it.isLowerCase() || it.isDigit() || it == '-' },
                "${file.name} declares a name that is not a kebab-case slug: ${skill.name}",
            )
        }
    }
}
