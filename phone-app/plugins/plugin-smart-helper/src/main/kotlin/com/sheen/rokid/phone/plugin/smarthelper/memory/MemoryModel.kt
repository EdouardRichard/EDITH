package com.sheen.rokid.phone.plugin.smarthelper.memory

import kotlin.math.ln

/**
 * P3.4 §3.1/§3.2: what a long-term memory is.
 *
 * [kind] is the store's only classification: a stable fact about the user, a preference, or a task the user keeps
 * doing. [text] is a self-contained sentence (spec §3.1: at most 120 characters) and is the **only** user content this
 * feature ever writes; every log line about memory carries ids and counts instead.
 */
enum class MemoryKind { FACT, PREFERENCE, ROUTINE }

/** How an entry got in: the user said so, or the device's own rule matched (spec §3.2). */
enum class MemorySource { EXPLICIT, AUTO }

data class MemoryEntry(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val useCount: Int,
    val source: MemorySource,
    /**
     * Phase 4 §3.9 6d: the normalized (third-person) form of [text], persisted alongside the original.
     *
     * Empty string signals "not yet normalized" — old documents lack this field and decode with the default.
     * The retriever falls back to [text] when this is blank.
     *
     * **Persisted** in the JSON document (unlike [embedding]), because it is derived once at write time and must
     * survive across sessions without re-normalization.
     */
    val normalizedText: String = "",
    /**
     * The 256-dim embedding of [text] (spec §5.2), or `null` when it has not been computed yet.
     *
     * **In memory only — never persisted.** A thousand of these is a multi-megabyte rewrite on every single write,
     * and the vector is recomputable from the text at any time. [MemoryLog] therefore leaves this field out of the
     * document, and an entry read back from storage carries `null` until the retriever fills it in.
     */
    val embedding: FloatArray? = null,
) {
    // A `FloatArray` compares by identity in a generated `equals`, which would make two content-identical entries
    // unequal whenever their vectors are different objects. The vector is a cache, not part of the entry's identity.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MemoryEntry &&
                id == other.id && kind == other.kind && text == other.text &&
                createdAtMillis == other.createdAtMillis && lastUsedAtMillis == other.lastUsedAtMillis &&
                useCount == other.useCount && source == other.source &&
                normalizedText == other.normalizedText
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + createdAtMillis.hashCode()
        result = 31 * result + lastUsedAtMillis.hashCode()
        result = 31 * result + useCount
        result = 31 * result + source.hashCode()
        result = 31 * result + normalizedText.hashCode()
        return result
    }

    override fun toString() = "MemoryEntry(id=$id, kind=$kind, redacted)"
}

/** The longest text the store accepts: spec §3.1 says 120, and the extractor refuses longer clauses instead of cutting
 * one in half (a truncated sentence would be a fact the user never stated). */
const val MEMORY_MAX_CHARS = 120

/**
 * One or more alphanumerics. The `+` (rather than `{2,}`) matters: a single digit is the whole difference between
 * `我的工位是3楼` and `我的工位是4楼`, so dropping it merged two different facts into one entry.
 */
private val ASCII_WORD = Regex("[a-z0-9]+")

/**
 * Chinese has no word boundaries, so scores are computed over **character bigrams**. That choice is what makes a
 * negation distinguishable: `我喜欢喝茶` and `我不喜欢喝茶` share three of five bigrams and differ on `我喜` vs `我不`,
 * where a single-character or whole-string comparison would call them the same statement (spec §8.1).
 */
private fun cjkBigrams(text: String): Set<String> {
    val out = mutableSetOf<String>()
    var run = StringBuilder()
    fun flush() {
        val s = run.toString()
        if (s.length == 1) out += s
        for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        run = StringBuilder()
    }
    for (ch in text) {
        if (ch.code in 0x4E00..0x9FFF) run.append(ch) else flush()
    }
    flush()
    return out
}

private fun asciiWords(text: String): Set<String> =
    ASCII_WORD.findAll(text.lowercase()).map { it.value }.toSet()

/**
 * Jaccard similarity over the same feature set the score uses. 0.85 is the de-duplication threshold (spec §3.2):
 * `我喜欢喝茶` vs `我不喜欢喝茶` scores 0.6 here, so the pair stays two entries rather than one being dropped.
 */
fun similarity(a: String, b: String): Double {
    val left = asciiWords(a) + cjkBigrams(a)
    val right = asciiWords(b) + cjkBigrams(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
}

/**
 * How much of [query] is literally in [text]. ASCII words count double because a whole word is a stronger signal than
 * one bigram; the negation examples in the tests are decided by the bigram half.
 */
fun lexicalScore(query: String, text: String): Int {
    val words = (asciiWords(query) intersect asciiWords(text)).size
    val bigrams = (cjkBigrams(query) intersect cjkBigrams(text)).size
    return 2 * words + bigrams
}

/**
 * Spec §3.3's **negation** term: whether a clause says what it is *not*.
 *
 * The marker list is short and literal on purpose - this breaks a tie between twins (`我喜欢喝茶` / `我不喜欢喝茶`), it is
 * not a parser, and a word that merely contains a marker (`不错`) is counted as negated. The cost of that is one tie
 * broken the wrong way; the cost of the opposite mistake is injecting a memory that says the reverse of what the user
 * just said.
 */
fun negated(text: String): Boolean = NEGATION_MARKERS.any { it in text }

private val NEGATION_MARKERS = listOf("不", "没", "别", "无", "未")

/** How much a disagreement about negation costs. One point, so it decides a tie without overriding a real lexical hit. */
private const val NEGATION_PENALTY = -1.0

/**
 * The ranking score (spec §3.3): lexical hit, a scenario bonus the caller may assert, use count and recency, and the
 * **negation** term. Every term is deterministic - no clock is read here and nothing is random - so a ranking is
 * reproducible in a test.
 */
fun score(entry: MemoryEntry, query: String, nowMillis: Long, scenarioMatched: Boolean): Double {
    val lexical = lexicalScore(query, entry.text).toDouble()
    val scenario = if (scenarioMatched) 1.0 else 0.0
    val usage = 0.5 * ln(1.0 + entry.useCount)
    val ageDays = ((nowMillis - entry.lastUsedAtMillis).coerceAtLeast(0L)) / 86_400_000.0
    val recency = 0.3 / (1.0 + ageDays)
    val negation = if (negated(query) != negated(entry.text)) NEGATION_PENALTY else 0.0
    return lexical + scenario + usage + recency + negation
}

/**
 * The best [limit] entries for [query] that also fit [charBudget], best first.
 *
 * Entries are taken **whole**: a budget that only half-fits an entry stops rather than truncating it, because a
 * truncated memory is a statement the user never made. Ties break on the most recently used, then on input order, so
 * the result is stable.
 */
fun rank(entries: List<MemoryEntry>, query: String, nowMillis: Long, limit: Int, charBudget: Int): List<MemoryEntry> {
    if (limit <= 0 || charBudget <= 0) return emptyList()
    val ranked = entries
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<MemoryEntry>> { score(it.value, query, nowMillis, false) }
                .thenByDescending { it.value.lastUsedAtMillis }
                .thenBy { it.index },
        )
        .map { it.value }
    val out = mutableListOf<MemoryEntry>()
    var used = 0
    for (entry in ranked) {
        if (out.size >= limit) break
        if (used + entry.text.length > charBudget) break
        out += entry
        used += entry.text.length
    }
    return out
}

/** Spec §5.6 step 3: an entry created within this window is not evicted unless every entry is inside it. */
const val PROTECT_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Keeps at most [cap] entries, dropping the least **used** first, and reports how many went (spec §5.6).
 *
 * The order is `useCount` ascending, then `lastUsedAtMillis` ascending, then input order — so the memory that has
 * earned its place survives, and two entries that tie on use fall back to which one was needed more recently.
 *
 * The seven-day protection is a **preference, not an exemption**: a just-learned fact is kept ahead of a stale one,
 * but when *every* entry is inside the window something still has to go, or a store that has filled with recent
 * entries could never accept another.
 *
 * Kept entries keep their input order, so the stored document does not reshuffle on every write.
 */
fun evictByUsage(
    entries: List<MemoryEntry>,
    cap: Int,
    nowMillis: Long,
    protectMillis: Long = PROTECT_MILLIS,
): Pair<List<MemoryEntry>, Int> {
    if (entries.size <= cap) return entries to 0
    val excess = entries.size - cap
    val byUsage = entries.withIndex().sortedWith(
        compareBy<IndexedValue<MemoryEntry>> { it.value.useCount }
            .thenBy { it.value.lastUsedAtMillis }
            .thenBy { it.index },
    )
    val isProtected = { indexed: IndexedValue<MemoryEntry> ->
        nowMillis - indexed.value.createdAtMillis < protectMillis
    }
    val droppable = byUsage.filterNot(isProtected)
    val chosen = if (droppable.size >= excess) {
        droppable.take(excess)
    } else {
        // Every remaining candidate is protected, so the protection yields: the least used protected entries go.
        droppable + byUsage.filter(isProtected).take(excess - droppable.size)
    }
    val doomed = chosen.map { it.index }.toSet()
    return entries.filterIndexed { index, _ -> index !in doomed } to doomed.size
}

/** A statement only counts as a candidate if it is first-person, present-tense and unbounded in time (spec §3.2). */
private val TIME_QUALIFIERS = listOf(
    "今天", "明天", "昨天", "今晚", "昨晚", "现在", "刚才", "这次", "那次", "上次", "下次",
    "上周", "下周", "上个月", "下个月", "今年", "去年", "明年", "一会儿", "待会儿",
)

private val PREFERENCE = Regex("^我(也)?(不)?(很|挺|特别|最)?(喜欢|爱|讨厌|习惯|通常|一般)")

private val FACT = Regex("^我的.{1,20}?是.{1,60}$")

private val CLAUSE_SPLIT = Regex("[，,。；;！!？?\\n]")

/** Six or more digits once spaces, hyphens and dots are removed: a phone number, an ID, a card. Never a memory. */
private fun hasIdentifierShape(text: String): Boolean =
    Regex("\\d{6,}").containsMatchIn(text.replace(Regex("[\\s\\-.]"), ""))

private fun candidateFor(clause: String): Pair<MemoryKind, String>? {
    if (clause.isEmpty() || clause.length > MEMORY_MAX_CHARS) return null
    if (clause.endsWith("吗") || clause.endsWith("呢")) return null
    if (hasIdentifierShape(clause)) return null
    if (TIME_QUALIFIERS.any { it in clause }) return null
    return when {
        PREFERENCE.containsMatchIn(clause) -> MemoryKind.PREFERENCE to clause
        FACT.matches(clause) -> MemoryKind.FACT to clause
        else -> null
    }
}

/**
 * The utterances that become memories, as `(kind, text)` (spec §3.2).
 *
 * Only the two shapes the spec names are accepted, and each is first-person: `我(不)喜欢/习惯/通常/一般 …` is a
 * preference, `我的 … 是 …` is a fact. Everything else - questions, time-qualified or one-off statements, anything
 * that looks like an identifier, and any clause longer than [MEMORY_MAX_CHARS] - yields nothing. There is deliberately
 * no `ROUTINE` here: a habit of *doing* is counted from executed tool calls by the caller (spec §3.2), never inferred
 * from a sentence.
 */
fun extractCandidates(utterance: String): List<Pair<MemoryKind, String>> =
    utterance
        .split(CLAUSE_SPLIT)
        .map { it.trim() }
        .mapNotNull(::candidateFor)
        .distinct()
        .take(5)

/** The words that turn a clause into an instruction rather than a statement (spec §3.2's explicit path). */
private val EXPLICIT_PREFIXES = listOf("记住", "记下")

/**
 * The one memory an **explicit** instruction asks for, or null (spec §3.2).
 *
 * "记住 X" stores X itself, not the sentence around it, and X goes through exactly the same rejection rules as an
 * automatically extracted clause: an identifier, a question or a clause too long to stand alone is refused even when
 * the user asked for it, because storing a phone number because someone said "记住" would be the one way this feature
 * could leak something no one wanted remembered. "以后…都…" is a standing instruction and becomes a `ROUTINE`.
 */
fun extractExplicit(utterance: String): Pair<MemoryKind, String>? {
    for (clause in utterance.split(CLAUSE_SPLIT).map { it.trim() }) {
        if (clause.isEmpty() || clause.length > MEMORY_MAX_CHARS) continue
        if (hasIdentifierShape(clause)) continue
        val prefix = EXPLICIT_PREFIXES.firstOrNull { clause.startsWith(it) }
        if (prefix != null) {
            val remainder = clause.removePrefix(prefix).trim()
            if (remainder.isEmpty() || remainder.length > MEMORY_MAX_CHARS) continue
            return when {
                PREFERENCE.containsMatchIn(remainder) -> MemoryKind.PREFERENCE to remainder
                else -> MemoryKind.FACT to remainder
            }
        }
        if (clause.startsWith("以后") && "都" in clause) return MemoryKind.ROUTINE to clause
    }
    return null
}
