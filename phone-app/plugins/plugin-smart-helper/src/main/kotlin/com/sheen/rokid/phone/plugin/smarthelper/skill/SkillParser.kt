package com.sheen.rokid.phone.plugin.smarthelper.skill

/**
 * Parses one SKILL file (spec §3.2/§3.3).
 *
 * It understands **exactly** the subset the spec declares — three required scalars, two optional list fields, one
 * optional enum and one optional boolean — and refuses anything else rather than guessing. The subset is deliberately
 * not a YAML implementation: this module has no YAML dependency, and a general YAML parser would accept documents
 * whose behaviour no spec describes. A field this version does not know is ignored (forward compatibility for a SKILL
 * written against a later spec); a *value* it cannot read in a field it does know is a rejection.
 */
object SkillParser {

    private const val DELIMITER = "---"
    private const val REPLACEMENT_CHARACTER = '\uFFFD'

    fun parse(fileName: String, text: String): SkillParse {
        if (REPLACEMENT_CHARACTER in text) return SkillParse.Invalid(SkillRejection.INVALID_ENCODING)

        // CRLF is normalised before anything else, so the body handed to the model never carries a stray carriage
        // return and the delimiter line matches on a document written on Windows.
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        if (lines.firstOrNull()?.trim() != DELIMITER) return SkillParse.Invalid(SkillRejection.NO_FRONT_MATTER)
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == DELIMITER }
            ?: return SkillParse.Invalid(SkillRejection.MALFORMED_FRONT_MATTER)

        val fields = parseFields(lines.subList(1, end))
            ?: return SkillParse.Invalid(SkillRejection.MALFORMED_FRONT_MATTER)

        val name = fields["name"].asScalar() ?: return SkillParse.Invalid(SkillRejection.MISSING_REQUIRED_FIELD)
        val version = fields["version"].asScalar() ?: return SkillParse.Invalid(SkillRejection.MISSING_REQUIRED_FIELD)
        val description = fields["description"].asScalar()
            ?: return SkillParse.Invalid(SkillRejection.MISSING_REQUIRED_FIELD)

        val priority = fields["priority"].asScalar()?.let { raw ->
            SkillPriority.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return SkillParse.Invalid(SkillRejection.MALFORMED_FRONT_MATTER)
        } ?: SkillPriority.NORMAL

        val enabled = fields["enabled"].asScalar()?.let { raw ->
            when (raw.lowercase()) {
                "true" -> true
                "false" -> false
                else -> return SkillParse.Invalid(SkillRejection.MALFORMED_FRONT_MATTER)
            }
        } ?: true

        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (body.isEmpty()) return SkillParse.Invalid(SkillRejection.BLANK_BODY)

        return SkillParse.Ok(
            Skill(
                name = name,
                version = version,
                description = description,
                triggers = fields["triggers"].asList(),
                tags = fields["tags"].asList(),
                appliesWhen = fields["applies_when"].asList(),
                notAppliesWhen = fields["not_applies_when"].asList(),
                priority = priority,
                enabled = enabled,
                body = body,
            ),
        )
    }

    /** `null` when a line is not a shape this subset defines. */
    private fun parseFields(lines: List<String>): Map<String, Any>? {
        val fields = LinkedHashMap<String, Any>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trimEnd()
            index++
            if (line.isBlank()) continue
            // An indented line that is not part of a block list has no key to belong to.
            if (line.first().isWhitespace()) return null

            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val key = line.substring(0, colon).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null

            val value = line.substring(colon + 1).trim()
            when {
                value.isEmpty() -> {
                    // A bare key opens a block list; it may legitimately be empty.
                    val items = mutableListOf<String>()
                    while (index < lines.size) {
                        val candidate = lines[index].trim()
                        if (candidate.startsWith("- ")) {
                            items += candidate.removePrefix("- ").trim()
                            index++
                        } else if (candidate.isEmpty()) {
                            index++
                        } else {
                            break
                        }
                    }
                    fields[key] = items
                }

                value.startsWith("[") && value.endsWith("]") ->
                    fields[key] = value.substring(1, value.length - 1)
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

                else -> fields[key] = value
            }
        }
        return fields
    }

    private fun Any?.asScalar(): String? = (this as? String)?.takeIf { it.isNotBlank() }

    private fun Any?.asList(): List<String> = (this as? List<*>)?.map { it.toString() } ?: emptyList()
}
