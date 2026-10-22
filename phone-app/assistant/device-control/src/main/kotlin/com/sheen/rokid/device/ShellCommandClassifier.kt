package com.sheen.rokid.device

import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.PrecheckDecision

/**
 * 0.25 Task 11 (R50): classifies one `shell` command **before** it runs.
 *
 * This is a pure-JVM table, not a shell. It never executes anything, never touches the network and never inspects the
 * device: parses the string, answers [PrecheckDecision.Allow] (no human confirmation is needed - **not** "the command
 * is safe"), [PrecheckDecision.RequireConfirmation] (the wearer must be asked) or [PrecheckDecision.Reject] (the
 * argument is not a command at all).
 *
 * Two rules dominate everything else:
 *
 * 1. **Fail closed.** A compound command is split on `;`, `&&`, `||`, `|` and newlines and classified segment by
 *    segment; if any segment needs confirmation the whole command does. Shell wrappers (`sh -c`, `eval`, `env ... sh`),
 *    command substitution (`$(...)`, backticks), variable expansion, encoded payloads, subshells, background execution
 *    and redirections are all "cannot be read" and are therefore confirmed - never allowed. An unknown command is
 *    confirmed too, not rejected: the model may have produced a legitimate tool this table does not know, and a human
 *    is the right decider. The **only** redirections stripped before the scan are the ones that cannot change what
 *    runs - a `/dev/null` target (`2>/dev/null`, `>/dev/null`, `1>`/`2</dev/null` with an optional space) and the two
 *    fd duplications `2>&1`/`1>&2` (R130). Everything else (`>file`, `>>`, `<file`, a non-`/dev/null` target) still
 *    forces a question, and stripping never launders the base command: `pm clear x >/dev/null` remains irreversible.
 * 2. **The reason is a category, never the command.** Shell text can contain user data (`content delete --uri …`), so
 *    the reason carried to the audit trail and the summary shown to the wearer name a class of risk, not the words.
 *
 * The allow list is deliberately narrow: read-only queries (`dumpsys`, `pm list`, `settings get`, `cmd package list`,
 * …) need no question, while every command that changes device state (`pm disable`, `settings put`, `am force-stop`,
 * `input`, `reboot`, …) or whose content cannot be read is confirmed. An unknown command is confirmed, never allowed,
 * so a command this build has not seen fails closed.
 */
object ShellCommandClassifier {

    /** Longer strings are not commands; rejecting them keeps the scanner's work bounded. */
    const val MAX_COMMAND_CHARS = 4096

    private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"

    // Reason categories. They are stable identifiers, not sentences, because they reach the audit trail.
    private const val IRREVERSIBLE = "IRREVERSIBLE"
    private const val DATA_WRITE = "DATA_WRITE"
    private const val SECURITY = "SECURITY"
    private const val PROCESS_KILL = "PROCESS_KILL"
    private const val SHELL_WRAPPER = "SHELL_WRAPPER"
    private const val OBFUSCATED = "OBFUSCATED"
    private const val SHELL_SYNTAX = "SHELL_SYNTAX"
    private const val DEVICE_STATE = "DEVICE_STATE"
    private const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"

    /**
     * Commands whose arguments decide the risk. Everything absent from this table is [UNKNOWN_COMMAND], which means
     * "ask", never "allow" - so a command this build has never seen is fail-closed by construction.
     */
    private val READ_ONLY_COMMANDS = setOf(
        "dumpsys",
        "getprop",
        "ls",
        "stat",
        "cat",
        "head",
        "tail",
        "wc",
        "grep",
        "echo",
        "printf",
        "date",
        "id",
        "whoami",
        "uname",
        "uptime",
        "df",
        "du",
        "ps",
        "top",
        "which",
        "readlink",
        "realpath",
        "dirname",
        "basename",
        "sleep",
        "true",
        "false",
    )

    /** Interpreters and evaluators: whatever follows them is not visible to this classifier. */
    private val INTERPRETERS = setOf(
        "sh", "bash", "zsh", "dash", "ksh", "fish", "eval", "source",
        "python", "python3", "perl", "ruby", "node", "php", "awk", "gawk",
    )

    /** A prefix that runs another command; the real command is resolved past it. */
    private val PREFIX_WRAPPERS = setOf("env", "xargs", "nohup", "timeout", "nice", "command", "exec", "stdbuf")

    /**
     * The only `cmd package` verbs allowed without a question: each one reports state and writes nothing. The rest of
     * `cmd` stays [UNKNOWN_COMMAND] (ask), so a service/verb this table has not vetted cannot ride the allow path.
     */
    private val CMD_PACKAGE_READ_ONLY = setOf("list", "path", "resolve-activity")

    private val WHITESPACE = Regex("\\s+")

    /**
     * R130: the two harmless redirection shapes, matched at the position where the token starts.
     *
     * A `/dev/null` target discards the output (or supplies an immediate EOF), and `2>&1`/`1>&2` only duplicate a
     * descriptor, so neither can change the command's words. The negative lookahead keeps a look-alike target
     * (`/dev/nullx`, `/dev/null/foo`) from being treated as the safe one. `\s*` accepts `> /dev/null`, which the shell
     * reads the same way.
     */
    private val SAFE_DEV_NULL_REDIRECTION = Regex("""[12]?[<>]\s*/dev/null(?![\w/.\-:])""")

    /** R130: `2>&1` / `1>&2` only; anything after the target digit must not extend it into another word. */
    private val SAFE_FD_DUPLICATION = Regex("""[12]>&[12](?![\w])""")

    /** Characters that end a word, so a leading fd digit there is a descriptor and not part of an argument. */
    private const val WORD_BOUNDARIES = " \t\r\n;|&("

    /** One pass over the raw string: the segments, and the first structural feature that cannot be read safely. */
    private class Scanned(val segments: List<String>, val syntaxReason: String?)

    fun classify(command: String): ActionPrecheck {
        if (command.isBlank() || command.contains('\u0000') || command.length > MAX_COMMAND_CHARS) {
            return ActionPrecheck(PrecheckDecision.Reject, reason = INVALID_ARGUMENTS)
        }
        // R130: remove harmless `/dev/null` / fd-duplication redirects first - they cannot change what runs, and
        // leaving them in the scan made every `dumpsys ... 2>/dev/null` ask the wearer. The remaining text is classified
        // by the same table, so the removal cannot wash a dangerous command clean.
        val scanned = scan(stripSafeRedirections(command))
        // Structure that can hide or build a second command is a confirmation, never an allow. This is checked first:
        // `ls > f` must not pass merely because `ls` is readable.
        scanned.syntaxReason?.let { return required(it) }
        for (segment in scanned.segments) {
            classifySegment(segment)?.let { return required(it) }
        }
        return ActionPrecheck(PrecheckDecision.Allow)
    }

    private fun required(reason: String) = ActionPrecheck(
        decision = PrecheckDecision.RequireConfirmation,
        summary = summaryFor(reason),
        reason = reason,
    )

    /** The wearer-facing question: a class of consequence, never the command text. */
    private fun summaryFor(reason: String): String = when (reason) {
        IRREVERSIBLE -> "终端命令：会删除或覆盖数据，无法撤销"
        DATA_WRITE -> "终端命令：会写入或修改数据"
        SECURITY -> "终端命令：会修改授权或安全设置"
        PROCESS_KILL -> "终端命令：会终止进程"
        SHELL_WRAPPER -> "终端命令：通过 shell 或解释器执行，内容无法预审"
        OBFUSCATED -> "终端命令：包含变量展开、命令替换或编码"
        SHELL_SYNTAX -> "终端命令：包含无法安全解析的 shell 结构"
        DEVICE_STATE -> "终端命令：会修改系统或设备状态"
        else -> "终端命令：无法确认是否安全"
    }

    // ---------------------------------------------------------------- harmless redirections (R130)

    /**
     * Deletes the harmless redirections from [command] before [scan], leaving every other character untouched.
     *
     * The scan is quote- and escape-aware, so this pass is too: a `>` inside quotes is an argument, not a redirection,
     * and a backslash-escaped one is literal text. That is what keeps `echo "2>/dev/null"` an ordinary `echo`.
     *
     * Deleting a token cannot hide another command: the surrounding words and separators are copied verbatim, so
     * `pm clear x >/dev/null` is still the irreversible `pm clear x`. Only the redirection's own bytes disappear.
     */
    private fun stripSafeRedirections(command: String): String {
        val stripped = StringBuilder(command.length)
        var singleQuoted = false
        var doubleQuoted = false
        var index = 0
        while (index < command.length) {
            val character = command[index]
            when {
                singleQuoted -> {
                    stripped.append(character)
                    if (character == '\'') singleQuoted = false
                    index++
                }
                doubleQuoted -> when {
                    character == '"' -> { doubleQuoted = false; stripped.append(character); index++ }
                    character == '\\' && index + 1 < command.length -> {
                        stripped.append(character).append(command[index + 1])
                        index += 2
                    }
                    else -> { stripped.append(character); index++ }
                }
                character == '\'' -> { singleQuoted = true; stripped.append(character); index++ }
                character == '"' -> { doubleQuoted = true; stripped.append(character); index++ }
                // An escaped character is literal text in the shell; keep the pair and let `scan` read it the same way.
                character == '\\' && index + 1 < command.length -> {
                    stripped.append(character).append(command[index + 1])
                    index += 2
                }
                character == '>' || character == '<' || character == '1' || character == '2' -> {
                    val length = safeRedirectionLength(command, index)
                    if (length == 0) {
                        stripped.append(character)
                        index++
                    } else {
                        index += length
                    }
                }
                else -> { stripped.append(character); index++ }
            }
        }
        return stripped.toString()
    }

    /**
     * Length of a harmless redirection starting at [index], or `0` when the character there starts something else.
     *
     * A redirection with an explicit descriptor (`2>/dev/null`) is only a redirection when the digit starts a word: in
     * `echo x2>/dev/null` the `2` belongs to the argument `x2` and only the bare `>/dev/null` may be removed. The
     * command text itself is never executed; this only feeds classification.
     */
    private fun safeRedirectionLength(command: String, index: Int): Int {
        val character = command[index]
        val fdPrefixed = character == '1' || character == '2'
        if (fdPrefixed) {
            val before = command.getOrNull(index - 1)
            if (before != null && !before.isWhitespace() && before !in WORD_BOUNDARIES) return 0
        }
        if (character == '>' || character == '<' || fdPrefixed) {
            val devNull = SAFE_DEV_NULL_REDIRECTION.find(command, index)
            if (devNull != null && devNull.range.first == index) return devNull.value.length
        }
        if (character == '>' || fdPrefixed) {
            val duplication = SAFE_FD_DUPLICATION.find(command, index)
            if (duplication != null && duplication.range.first == index) return duplication.value.length
        }
        return 0
    }

    // ---------------------------------------------------------------- scanner

    /**
     * Splits [command] on the shell's command separators while keeping quoted text intact.
     *
     * Quotes are removed from the resulting segment text (the shell removes them too), which is what lets `'rm' -rf /`
     * be classified as `rm` rather than as an unknown token. Structure that this classifier cannot reason about is
     * recorded in [Scanned.syntaxReason] instead of being ignored: a redirection, a background `&`, a subshell, a
     * brace group, a backtick, `$(...)`, `${...}`, or a `$VAR` expansion. The harmless `/dev/null` and fd-duplication
     * redirects have already been stripped by [stripSafeRedirections] (R130), so only unreadable ones reach here.
     */
    private fun scan(command: String): Scanned {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var singleQuoted = false
        var doubleQuoted = false
        var syntax: String? = null
        var index = 0

        fun flush() {
            if (current.isNotBlank()) segments += current.toString()
            current.setLength(0)
        }

        while (index < command.length) {
            val character = command[index]
            when {
                singleQuoted -> {
                    // Single quotes are literal in the shell: no separator and no expansion applies.
                    if (character == '\'') singleQuoted = false else current.append(character)
                    index++
                }

                doubleQuoted -> when {
                    character == '"' -> { doubleQuoted = false; index++ }
                    character == '\\' &&
                        command.getOrNull(index + 1)?.let { it in DOUBLE_QUOTE_ESCAPES } == true -> {
                        // A backslash-escaped `$` or backtick is literal text in the shell, so it stays unexpanded.
                        current.append(command[index + 1])
                        index += 2
                    }
                    // Double quotes do **not** suppress expansion: `"$(...)"`, `` "`...`" `` and `"$VAR"` all run
                    // before the command does. Allowing them would let a quoted substitution execute unconfirmed.
                    character == '`' -> {
                        syntax = syntax ?: OBFUSCATED
                        current.append(character)
                        index++
                    }
                    character == '$' && looksLikeExpansion(command, index) -> {
                        syntax = syntax ?: OBFUSCATED
                        current.append(character)
                        index++
                    }
                    else -> { current.append(character); index++ }
                }

                character == '\'' -> { singleQuoted = true; index++ }
                character == '"' -> { doubleQuoted = true; index++ }
                // A backslash escapes the next character; outside quotes it cannot be a separator either.
                character == '\\' -> {
                    if (index + 1 < command.length) {
                        current.append(command[index + 1])
                        index += 2
                    } else {
                        index++
                    }
                }
                character == ';' || character == '\n' || character == '|' || character == '&' -> {
                    flush()
                    if (character == '&' && command.getOrNull(index + 1) != '&') {
                        syntax = syntax ?: SHELL_SYNTAX
                    }
                    index++
                    if (index < command.length && command[index] == character &&
                        (character == '|' || character == '&')
                    ) {
                        index++
                    }
                }
                character == '(' || character == ')' || character == '{' || character == '}' ||
                    character == '>' || character == '<' -> {
                    flush()
                    syntax = syntax ?: SHELL_SYNTAX
                    index++
                }
                character == '`' -> {
                    syntax = syntax ?: OBFUSCATED
                    current.append(character)
                    index++
                }
                character == '$' && looksLikeExpansion(command, index) -> {
                    syntax = syntax ?: OBFUSCATED
                    current.append(character)
                    index++
                }
                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        flush()
        return Scanned(segments, syntax)
    }

    /** `$VAR`, `${...}` and `$(...)` all build text the shell will interpret; none may be allowed unseen. */
    private fun looksLikeExpansion(command: String, index: Int): Boolean {
        val next = command.getOrNull(index + 1) ?: return false
        return next == '(' || next == '{' || next.isLetterOrDigit() || next in "_@*#?!$-"
    }

    // ---------------------------------------------------------------- one segment

    private fun classifySegment(segment: String): String? {
        val tokens = segment.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // Interpreters transform their arguments into a new command, and base64 hides one; both are confirmed
        // wherever they appear, which also covers `env sh -c …` and `echo … | base64 -d | sh`.
        if (tokens.any { basename(it) in INTERPRETERS }) return SHELL_WRAPPER
        if (tokens.any { basename(it) == "base64" }) return OBFUSCATED

        var index = 0
        // `A=com.x pm clear $A` is still `pm clear`: environment assignments are not a command of their own.
        while (index < tokens.size && isAssignment(tokens[index])) index++
        while (index < tokens.size) {
            val wrapper = basename(tokens[index])
            if (wrapper !in PREFIX_WRAPPERS) break
            index++
            while (index < tokens.size && tokens[index].startsWith("-")) index++
            if (wrapper == "timeout" && index < tokens.size) index++ // the duration is not the command
        }
        if (index >= tokens.size) return UNKNOWN_COMMAND
        return classifyCommand(basename(tokens[index]), tokens.drop(index))
    }

    private fun classifyCommand(base: String, tokens: List<String>): String? {
        if (base in READ_ONLY_COMMANDS) return null
        return when (base) {
            "pm" -> when (tokens.getOrNull(1)) {
                "list", "path", "dump" -> null
                // Disabling/enabling a package and writing a setting change device state, so they are asked even
                // though they can be reversed afterwards.
                "disable", "disable-user", "enable" -> DEVICE_STATE
                "clear", "uninstall" -> IRREVERSIBLE
                "grant", "revoke" -> SECURITY
                else -> if (tokens.getOrNull(1)?.startsWith("get-") == true) null else UNKNOWN_COMMAND
            }
            "settings" -> when (tokens.getOrNull(1)) {
                "get", "list" -> null
                "put" -> DEVICE_STATE
                "delete", "reset" -> IRREVERSIBLE
                else -> UNKNOWN_COMMAND
            }
            "am" -> when (tokens.getOrNull(1)) {
                "start", "start-activity" -> null
                "force-stop", "kill", "kill-all" -> PROCESS_KILL
                else -> UNKNOWN_COMMAND
            }
            "cmd" -> when {
                tokens.getOrNull(1) == "package" &&
                    tokens.getOrNull(2) in CMD_PACKAGE_READ_ONLY -> null
                else -> UNKNOWN_COMMAND
            }
            "content" -> when (tokens.getOrNull(1)) {
                "query" -> null
                "delete" -> IRREVERSIBLE
                "insert", "update", "call" -> DATA_WRITE
                else -> UNKNOWN_COMMAND
            }
            "rm", "rmdir", "mv", "dd", "shred", "truncate" -> IRREVERSIBLE
            "cp", "ln", "tee", "screencap", "screenrecord" -> DATA_WRITE
            "kill", "pkill", "killall" -> PROCESS_KILL
            "chmod", "chown", "chgrp", "su", "sudo" -> SECURITY
            "reboot", "shutdown", "svc", "setprop", "stop", "start", "input" -> DEVICE_STATE
            else -> if (base == "format" || base.startsWith("mkfs")) IRREVERSIBLE else UNKNOWN_COMMAND
        }
    }

    private fun basename(token: String): String = token.substringAfterLast('/').lowercase()

    /** `NAME=value`, the shell's environment-assignment prefix. A bare `NAME=value` is not a command. */
    private fun isAssignment(token: String): Boolean {
        val equals = token.indexOf('=')
        if (equals <= 0) return false
        val name = token.substring(0, equals)
        return (name.first().isLetter() || name.first() == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' }
    }

    private const val DOUBLE_QUOTE_ESCAPES = "\\\"$`\n"
}
