package edith.probe.toolcall

/**
 * The shortest prefix that still identifies a real credential. Shorter than this and the scan would match ordinary
 * text, which would make the guard pass everything — worse than having no guard, because it would be trusted.
 */
private const val MIN_PREFIX_LENGTH = 4

/**
 * Positions of [secretPrefix] inside [text], case-insensitively.
 *
 * Returns descriptions of *where* a hit is, never *what* it is: the guard runs over the probe's own output, and a guard
 * that echoed the secret back would be the leak it exists to prevent. An empty result means clean.
 *
 * @throws IllegalArgumentException when [secretPrefix] is too short to be meaningful, so a misconfigured guard fails
 * loudly instead of silently approving everything.
 */
fun scan(text: String, secretPrefix: String): List<String> {
    require(secretPrefix.length >= MIN_PREFIX_LENGTH) { "SECRET_PREFIX_TOO_SHORT" }
    val hits = mutableListOf<String>()
    var index = text.indexOf(secretPrefix, startIndex = 0, ignoreCase = true)
    while (index >= 0) {
        hits += "offset=$index length=${secretPrefix.length}"
        index = text.indexOf(secretPrefix, startIndex = index + 1, ignoreCase = true)
    }
    return hits
}
