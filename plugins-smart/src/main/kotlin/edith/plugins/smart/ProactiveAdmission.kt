package edith.plugins.smart

/**
 * The interpretable features a proactive candidate is judged on (P3.5 §W3).
 *
 * Why features instead of the one length threshold they replace: the measured 141 refusals that produced `SHORT` were
 * mostly *real* short spoken questions ("现在几点了", "今天星期几"), so length was never the signal it was being used as -
 * it was a proxy that happened to be right on the ambient windows and wrong on the corpus. Each feature here is
 * something a person could point at and disagree with, which is the property the replacement needs: a rule nobody can
 * read is a rule nobody can correct.
 *
 * All of this runs **locally**, on the transcript text and the foreground package only. No new collection, no network.
 */
internal enum class AdmissionFeature {
    /** A word that only appears when the speaker wants something *known*: 几/多少/什么/怎么/为什么/区别/意思 … */
    CONTENT_NEED,

    /** Interrogative shape: a question mark, a trailing 吗/呢/吧, or an A-not-A form (是不是/能不能/要不要). */
    QUESTION_FORM,

    /** A request aimed at an assistant: 帮我/给我/打开/设置/查一下/告诉/记下 … */
    IMPERATIVE,

    /** A time reference: 几点/今天/明天/多久/哪年/星期/现在 … */
    TIME,

    /** First person: 我/咱 — the speaker is talking about their own situation rather than reporting someone else's. */
    PRONOUN_SELF,

    /** A noun tied to what is on screen right now (the foreground app), which is what makes a short phrase actionable. */
    FOREGROUND_ANCHOR,

    /**
     * Long enough to be a sentence. **Deliberately the weakest signal**: it is counted like any other feature, so it
     * can help a candidate over the line but can never by itself admit or refuse one.
     */
    LENGTH,

    /**
     * Filler: laughter, sighs, throat-clearing, a bare interjection. A hard refusal, because there is nothing to answer
     * and the acoustic layer only saw *energy*, not a request.
     */
    CHAT_PARTICLE,
}

/** What the extractor found in one utterance. */
internal data class AdmissionFeatures(val fired: Set<AdmissionFeature>, val normalizedLength: Int)

/**
 * The features of one candidate utterance.
 *
 * @param transcript what was heard (already the recogniser's final hypothesis)
 * @param foreground the foreground package name, or null when the App cannot see it. Only used for
 *   [AdmissionFeature.FOREGROUND_ANCHOR], and a null answer simply cannot fire that feature - it never fires a
 *   substitute.
 */
internal fun admissionFeatures(transcript: String, foreground: String?): AdmissionFeatures {
    val text = transcript.trim().replace(WHITESPACE, " ").lowercase()
    val fired = linkedSetOf<AdmissionFeature>()
    if (text.isEmpty()) return AdmissionFeatures(fired, 0)
    if (CHAT_PARTICLES.any { text.contains(it) } || text.none { it.isLetterOrDigit() }) {
        fired += AdmissionFeature.CHAT_PARTICLE
    }
    if (CONTENT_NEED.any { text.contains(it) }) fired += AdmissionFeature.CONTENT_NEED
    if (QUESTION_MARK.any { text.contains(it) } || QUESTION_TAIL.any { text.endsWith(it) } ||
        A_NOT_A.any { text.contains(it) }
    ) {
        fired += AdmissionFeature.QUESTION_FORM
    }
    if (IMPERATIVE.any { text.contains(it) }) fired += AdmissionFeature.IMPERATIVE
    if (TIME_TERMS.any { text.contains(it) }) fired += AdmissionFeature.TIME
    if (SELF_PRONOUNS.any { text.contains(it) }) fired += AdmissionFeature.PRONOUN_SELF
    if (foreground != null && foregroundAnchored(text, foreground)) fired += AdmissionFeature.FOREGROUND_ANCHOR
    if (text.length >= LENGTH_HINT_CHARS) fired += AdmissionFeature.LENGTH
    return AdmissionFeatures(fired, text.length)
}

/**
 * Whether the utterance names something belonging to the App on screen.
 *
 * The comparison cannot be "does the sentence contain the package's leaf name": the transcript is Chinese and the
 * package is Latin, so `com.android.settings` simply never appears in "设置里怎么改字体". The mapping below is the
 * smallest honest bridge - a handful of system apps whose spoken name everyone knows, plus the package's own leaf for
 * an app that is named the way it is spoken (or a user saying it in English).
 *
 * A package outside the table contributes no label, so the feature stays silent rather than guessing. That is the
 * conservative direction: this feature can only *help* a candidate, so a miss costs nothing here.
 */
private fun foregroundAnchored(text: String, foreground: String): Boolean {
    val pkg = foreground.lowercase()
    val leaf = pkg.substringAfterLast('.')
    if (leaf.length >= 3 && text.contains(leaf)) return true
    if (text.contains(pkg)) return true
    return LABELS_BY_PACKAGE.any { (name, labels) -> (pkg == name || pkg.startsWith("$name.")) && labels.any(text::contains) }
}

/**
 * The spoken names of the system apps this feature can recognise, keyed by package prefix.
 *
 * Deliberately tiny: every entry is a claim that a wearer might say that word while that app is on screen. A bigger
 * table would raise recall on the corpus and prove nothing about a real phone, so it stays at the apps whose Chinese
 * names are unambiguous.
 */
private val LABELS_BY_PACKAGE: Map<String, List<String>> = mapOf(
    "com.android.settings" to listOf("设置"),
    "com.android.camera" to listOf("相机", "拍照"),
    "com.android.dialer" to listOf("拨号", "电话"),
    "com.android.contacts" to listOf("联系人", "通讯录"),
    "com.android.mms" to listOf("短信"),
    "com.android.calendar" to listOf("日历", "日程"),
    "com.android.deskclock" to listOf("时钟", "闹钟"),
    "com.android.gallery3d" to listOf("相册", "图库"),
    "com.android.browser" to listOf("浏览器"),
    "com.miui.gallery" to listOf("相册", "图库"),
    "com.miui.notes" to listOf("便签", "笔记"),
    "com.tencent.mm" to listOf("微信"),
)

private val WHITESPACE = Regex("\\s+")

/**
 * How long a transcript has to be for [AdmissionFeature.LENGTH].
 *
 * **Not a gate.** Twelve characters was the shipped `LOW` threshold; keeping the same number as the weak signal's
 * boundary means the only thing that changed is its authority, which is exactly what makes the before/after comparison
 * honest.
 */
internal const val LENGTH_HINT_CHARS = 12

private val CHAT_PARTICLES = listOf("哈哈", "呵呵", "嘿嘿", "哎哟", "哎呀", "咳咳", "嗯", "哦", "呃", "喂", "啊？", "哈哈")
private val CONTENT_NEED = listOf(
    "几", "多少", "什么", "啥", "怎么", "怎样", "为什么", "哪", "谁", "能不能", "可以不可以", "是不是",
    "有没有", "要不要", "该不该", "区别", "意思", "读音", "全称", "翻译", "来历", "版本",
)
private val QUESTION_MARK = listOf("？", "?")
private val QUESTION_TAIL = listOf("吗", "呢", "吧")
private val A_NOT_A = listOf("是不是", "能不能", "有没有", "要不要", "该不该", "行不行", "对不对")
private val IMPERATIVE = listOf("帮我", "给我", "帮我看", "帮我查", "打开", "设置", "查一下", "查查", "告诉", "记一下", "记下")
private val TIME_TERMS = listOf("几点", "今天", "明天", "昨天", "多久", "哪年", "现在", "星期", "周几", "上个月", "下周")
private val SELF_PRONOUNS = listOf("我", "咱")
