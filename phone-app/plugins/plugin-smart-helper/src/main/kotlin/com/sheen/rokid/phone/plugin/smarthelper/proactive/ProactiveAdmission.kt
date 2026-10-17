package com.sheen.rokid.phone.plugin.smarthelper.proactive

/**
 * The interpretable features a proactive candidate is judged on (P3.5 §W3).
 *
 * Public because the offline calibration prints them: the whole claim of this rule is that its decision can be read
 * off named features, so the names have to be visible wherever the decision is reported.
 *
 * Why features instead of the one length threshold they replace: the measured 141 refusals that produced `SHORT` were
 * mostly *real* short spoken questions ("现在几点了", "今天星期几"), so length was never the signal it was being used as -
 * it was a proxy that happened to be right on the ambient windows and wrong on the corpus. Each feature here is
 * something a person could point at and disagree with, which is the property the replacement needs: a rule nobody can
 * read is a rule nobody can correct.
 *
 * All of this runs **locally**, on the transcript text and the foreground package only. No new collection, no network.
 */
enum class AdmissionFeature {
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

    /**
     * The sentence is about **someone else's words or situation** (转述/背景声) rather than the speaker's own need:
     * 他说/我妈说/电视/新闻/电影/群里/播客 …
     *
     * A hard refusal, and the single most load-bearing feature on the corpus: the dilemma and privacy negatives are
     * overwhelmingly reported speech ("他说他儿子发烧了", "新闻说高速上出了事故"), while every one of those scenarios'
     * positives is the speaker talking about themselves. Without this the rule cannot tell a report from a request.
     */
    REPORTED_SPEECH,

    /**
     * The same need for information, carried by a marker that **cannot stand alone**: bare 什么/哪/谁.
     *
     * These are what chat, rhetorical questions and self-directed muttering share with real questions ("我们一会儿去哪吃"
     * vs "这个字的读音是啥"). They still count as a signal, but only alongside an independent one - see
     * [admitByFeatures].
     */
    WEAK_CONTENT_NEED,

    /**
     * A **media** frame (TV, news, film, book, podcast, group chat). Unlike a bare reporting frame this is refused even
     * when the sentence is shaped like a question: "有人在群里问怎么处理伤口" is still a question about someone else's
     * chat, and the corpus labels every such row negative.
     */
    MEDIA_REPORT,
}

/** What the extractor found in one utterance. */
internal data class AdmissionFeatures(val fired: Set<AdmissionFeature>, val normalizedLength: Int)

/**
 * Media and third-party frames that are **always** a report: the speaker is relaying a broadcast, a film, a book, a
 * class, a group chat or a quotation. A hard refusal (see [AdmissionFeature.REPORTED_SPEECH]).
 *
 * These are what make the dilemma and privacy negatives negative, and the corpus has **no** positive that contains one:
 * a question about what the TV said is still a question about the TV, not about the wearer's own situation.
 *
 * A bare reporting frame (`他说`, `我妈说`) is deliberately **not** here on its own - see [REPORT_FRAME].
 */
private val MEDIA_FRAME = listOf(
    "电视", "新闻", "电影", "剧里", "电视剧", "播客", "广播", "书里", "课上", "报道", "广告", "群里",
)

/**
 * A reporting frame **plus** the need to be a statement rather than a question.
 *
 * "我朋友说要做个手术靠谱吗" is a real question the speaker is asking (corpus: positive), while "他说他儿子发烧了" is
 * a report (corpus: negative). The frame alone cannot separate them and the question shape alone cannot either, so the
 * refusal applies only when the frame appears **and** the sentence has no question shape.
 */
private val REPORT_FRAME = listOf("我妈说", "我爸说", "我朋友说", "他说", "她说", "同事说", "老板说", "老师说", "有人说")

/**
 * The features of one candidate utterance.
 *
 * @param transcript what was heard (already the recogniser's final hypothesis)
 * @param foreground the foreground package name, or null when the App cannot see it. Only used for
 *   [AdmissionFeature.FOREGROUND_ANCHOR], and a null answer simply cannot fire that feature - it never fires a
 *   substitute.
 * @param lengthHintChars the boundary for [AdmissionFeature.LENGTH]; the sensitivity dial's old threshold, now only a
 *   weak signal's edge.
 */
internal fun admissionFeatures(
    transcript: String,
    foreground: String?,
    lengthHintChars: Int = LENGTH_HINT_CHARS,
): AdmissionFeatures {
    val text = transcript.trim().replace(WHITESPACE, " ").lowercase()
    val fired = linkedSetOf<AdmissionFeature>()
    if (text.isEmpty()) return AdmissionFeatures(fired, 0)
    if (CHAT_PARTICLES.any { text.contains(it) } || text.none { it.isLetterOrDigit() }) {
        fired += AdmissionFeature.CHAT_PARTICLE
    }
    if (MEDIA_FRAME.any { text.contains(it) } || REPORT_FRAME.any { text.contains(it) }) {
        fired += AdmissionFeature.REPORTED_SPEECH
    }
    if (MEDIA_FRAME.any { text.contains(it) }) fired += AdmissionFeature.MEDIA_REPORT
    if (STRONG_CONTENT_NEED.any { text.contains(it) }) fired += AdmissionFeature.CONTENT_NEED
    if (WEAK_CONTENT_NEED.any { text.contains(it) }) fired += AdmissionFeature.WEAK_CONTENT_NEED
    if (QUESTION_MARK.any { text.contains(it) } || A_NOT_A.any { text.contains(it) } ||
        QUESTION_TAIL.any { text.endsWith(it) }
    ) {
        fired += AdmissionFeature.QUESTION_FORM
    }
    if (IMPERATIVE.any { text.contains(it) }) fired += AdmissionFeature.IMPERATIVE
    if (TIME_TERMS.any { text.contains(it) }) fired += AdmissionFeature.TIME
    if (SELF_PRONOUNS.any { it.containsMatchIn(text) }) fired += AdmissionFeature.PRONOUN_SELF
    if (foreground != null && foregroundAnchored(text, foreground)) fired += AdmissionFeature.FOREGROUND_ANCHOR
    if (text.length >= lengthHintChars) fired += AdmissionFeature.LENGTH
    return AdmissionFeatures(fired, text.length)
}

/**
 * How long a transcript has to be for [AdmissionFeature.LENGTH].
 *
 * **Not a gate.** Ten characters, lowered from the shipped `LOW` threshold of twelve *only* as the weak signal's edge:
 * the calibration showed the natural spoken questions the corpus is built from sit at 10-11 characters ("我爸胸口疼该不该等一会儿"),
 * so keeping twelve left the length feature unable to contribute to exactly the sentences it was meant to help. Its
 * weight did not change - it is one feature among several, and it can never admit or refuse a candidate alone.
 */
internal const val LENGTH_HINT_CHARS = 10

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
 * The admission decision for one candidate, with the reason a reviewer can read.
 *
 * Public because [admitByFeatures] returns it and the offline calibration calls that (see its KDoc). [fired] holds
 * enum names, never the transcript, so a caller may log or print it as-is.
 */
data class AdmissionVerdict(val admitted: Boolean, val score: Int, val fired: Set<AdmissionFeature>)

/**
 * Whether a candidate may become a proactive request, judged on [admissionFeatures].
 *
 * **Public on purpose.** The offline calibration (`tools:p3-eval-trigger`'s `--admission` mode) runs *this* function
 * over the frozen corpus, so the number the report prints and the rule the plugin applies cannot drift apart. Keeping
 * it internal would force the harness to re-implement the rule, which is precisely the failure this phase has to avoid.
 *
 * The rule, in full:
 *
 * 1. [AdmissionFeature.CHAT_PARTICLE] refuses outright - there is nothing to answer. So does a **media** frame, and a
 *    bare reporting frame that is not also shaped like a question.
 * 2. Otherwise the **score is the number of features that fired**, [AdmissionFeature.LENGTH] included and worth the
 *    same as every other. That equality is the point: length can tip a candidate over the line but can never decide
 *    alone, which is what "长度从硬门降为弱信号" means concretely.
 * 3. A candidate gets through on a signal that cannot be chatter - [AdmissionFeature.CONTENT_NEED] or
 *    [AdmissionFeature.IMPERATIVE] - or on a **weak** interrogative ([AdmissionFeature.WEAK_CONTENT_NEED], bare
 *    什么/哪/谁) together with a second, independent feature. A sentence that is only long, only first-person, or only
 *    a question shape is not a request for information.
 *
 * @param minChars the sensitivity dial's old length threshold. Kept as a parameter because it is still a *stored*
 *   setting, and it is handed to [admissionFeatures] as nothing more than the [AdmissionFeature.LENGTH] boundary.
 */
fun admitByFeatures(transcript: String, foreground: String?, minChars: Int = LENGTH_HINT_CHARS): AdmissionVerdict {
    val features = admissionFeatures(transcript, foreground, minChars)
    val fired = features.fired
    val score = fired.size
    val strongNeed = AdmissionFeature.CONTENT_NEED in fired || AdmissionFeature.IMPERATIVE in fired
    val weakOnly = AdmissionFeature.WEAK_CONTENT_NEED in fired && !strongNeed
    val otherThanNeed = (fired - NEED_FEATURES).isNotEmpty()
    val refused = AdmissionFeature.CHAT_PARTICLE in fired ||
        AdmissionFeature.MEDIA_REPORT in fired ||
        AdmissionFeature.REPORTED_SPEECH in fired && AdmissionFeature.QUESTION_FORM !in fired
    // Two ways past the door, both requiring a signal that **cannot** be chat:
    //   * an explicit request ([IMPERATIVE]) or a strong information word ([CONTENT_NEED]) - either is a request on its
    //     own, which is what "a short real question must get through" means;
    //   * a **weak** interrogative (bare 什么/哪/谁) plus a second, independent signal - which is what stops a bare
    //     interrogative from carrying a sentence that chat and rhetorical questions also use.
    // `strongNeed` already covers IMPERATIVE (a clear request is a signal on its own). The third clause keeps a bare
    // interrogative from carrying a sentence that chat and rhetorical questions also use.
    val admitted = !refused && (strongNeed || weakOnly && otherThanNeed)
    return AdmissionVerdict(admitted, score, fired)
}

/**
 * One corpus evaluation: how many rows of each kind the rule would admit.
 *
 * Counts only - no text - so this can be printed into evidence. The chat count is carried separately because §11.3's
 * chat clause is a *separate* gate from precision, and a rule that passes precision by admitting chat negatives is not
 * acceptable.
 */
data class AdmissionConfusion(
    val positives: Int,
    val admittedPositives: Int,
    val negatives: Int,
    val admittedNegatives: Int,
    val chat: Int,
    val admittedChat: Int,
) {
    /** Admission recall: of the rows that should trigger, how many pass the door. */
    val recall: Double get() = if (positives == 0) 0.0 else admittedPositives.toDouble() / positives

    /** Admission precision: of the rows that pass the door, how many should have. */
    val precision: Double
        get() = if (admittedPositives + admittedNegatives == 0) 1.0
        else admittedPositives.toDouble() / (admittedPositives + admittedNegatives)
}

/**
 * Runs the admission rule over labelled rows and counts the outcome.
 *
 * @param rows `(scenario, label, text)` - the frozen corpus's own three columns. The **scenario** matters because the
 *   chat clause is defined on it, and the **label** is the frozen judgement; neither is derived from the rule.
 */
fun admissionReport(rows: List<Triple<String, String, String>>): AdmissionConfusion {
    var positives = 0
    var admittedPositives = 0
    var negatives = 0
    var admittedNegatives = 0
    var chat = 0
    var admittedChat = 0
    for ((scenario, label, text) in rows) {
        val admitted = admitByFeatures(text, foreground = null).admitted
        if (label == "positive") {
            positives++
            if (admitted) admittedPositives++
        } else {
            negatives++
            if (admitted) admittedNegatives++
        }
        if (scenario == "chat") {
            chat++
            if (admitted) admittedChat++
        }
    }
    return AdmissionConfusion(positives, admittedPositives, negatives, admittedNegatives, chat, admittedChat)
}

private val CHAT_PARTICLES = listOf("哈哈", "呵呵", "嘿嘿", "哎哟", "哎呀", "咳咳", "嗯", "哦", "呃", "喂", "啊？", "哈哈")

/** The features that express the *need itself*; everything else is corroboration. Used by [admitByFeatures]. */
private val NEED_FEATURES = setOf(
    AdmissionFeature.CONTENT_NEED,
    AdmissionFeature.WEAK_CONTENT_NEED,
    AdmissionFeature.IMPERATIVE,
)

/**
 * The **strong** content-need markers: words that only appear in a real information question.
 *
 * Chosen for precision first. An earlier version matched bare 什么/哪/谁, and the calibration showed why that cannot
 * work: "我们一会儿去哪吃" and "谁知道呢" are exactly the sentences a proactive assistant must stay out of, and both
 * matched. 多少/怎么/为什么/意思是/区别 carry the same need and almost none of the noise.
 */
private val STRONG_CONTENT_NEED = listOf(
    "几", "多少", "多远", "多大", "多长", "多重", "多久", "咋", "咋样", "什么时候", "什么样", "什么区别",
    "什么名字", "什么意思", "怎么", "怎样", "为什么", "哪个", "哪年", "哪儿", "哪里", "哪些",
    "是不是", "能不能", "可以不可以", "有没有", "要不要", "该不该", "行不行", "对不对",
    "区别", "意思", "读音", "全称", "翻译", "来历", "版本", "作用", "原理", "原因", "方法", "步骤",
)

/** The **weak** markers - bare interrogatives that chat and rhetorical questions share. See [AdmissionFeature.WEAK_CONTENT_NEED]. */
private val WEAK_CONTENT_NEED = listOf("什么", "啥", "哪", "谁")

private val QUESTION_MARK = listOf("？", "?")
private val QUESTION_TAIL = listOf("吗", "呢", "吧")
private val A_NOT_A = listOf("是不是", "能不能", "有没有", "要不要", "该不该", "行不行", "对不对")

private val IMPERATIVE = listOf(
    "帮我", "给我", "帮我看", "帮我查", "打开", "设置", "查一下", "查查", "告诉", "记一下", "记下", "提醒",
)
private val TIME_TERMS = listOf("几点", "今天", "明天", "昨天", "多久", "哪年", "现在", "星期", "周几", "上个月", "下周")
private val SELF_PRONOUNS = listOf(Regex("(?<!你)我"), Regex("咱"))
