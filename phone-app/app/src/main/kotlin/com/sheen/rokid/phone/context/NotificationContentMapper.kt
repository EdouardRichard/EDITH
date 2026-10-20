package com.sheen.rokid.phone.context

/**
 * The pure half of notification content (edith-0.3 Phase 2, Task 3; spec §7.2, D179).
 *
 * D179 supersedes the old category-only rule: the perception pipeline is allowed to see a notification's title, text,
 * subtext and big text, plus the labels of its actions. This object is the whole mapping rule. It takes exactly the
 * values the listener service collects - package name, resolved app label, an extras map, the category string, post
 * time, the ongoing flag and action **titles** - and produces a [NotificationContent]. Nothing Android-specific happens
 * here, so every rule below is pinned by a plain JVM test.
 *
 * The rules follow the contract's "no invented facts" shape:
 * - an absent or blank value stays `null`; blank is an absence, not an empty fact;
 * - only the platform extras keys below are read; any other extras entry is ignored rather than guessed into a field;
 * - `actionLabels` are the trimmed, non-blank `Notification.Action.title` values in platform order - no
 *   `PendingIntent`, no action intent and no action data is accepted by this signature, so none can be exposed
 *   downstream.
 *
 * The extras keys are the platform's own key strings, restated here so the mapper itself needs no Android class. The
 * listener service reads the `Bundle` with these very constants, so one name is the key on both sides of the seam.
 */
internal object NotificationContentMapper {

    /** `Notification.EXTRA_TITLE` ("android.title"). */
    const val KEY_TITLE = "android.title"

    /** `Notification.EXTRA_TEXT` ("android.text"). */
    const val KEY_TEXT = "android.text"

    /** `Notification.EXTRA_SUB_TEXT` ("android.subText"). */
    const val KEY_SUB_TEXT = "android.subText"

    /** `Notification.EXTRA_BIG_TEXT` ("android.bigText"). */
    const val KEY_BIG_TEXT = "android.bigText"

    /**
     * Maps one posted notification to the content the perception pipeline may read.
     *
     * @param packageName the posting package as the platform reports it.
     * @param appLabel the resolved application label, or `null` when it could not be resolved.
     * @param extras only the content extras the service copied out of the notification's `Bundle`.
     * @param category the notification's category, or the channel id fallback the service applies.
     * @param postedAtEpochMillis `StatusBarNotification.getPostTime()`.
     * @param ongoing `StatusBarNotification.isOngoing()`.
     * @param actionTitles the action labels in platform order; blank labels are dropped here.
     */
    fun map(
        packageName: String,
        appLabel: String?,
        extras: Map<String, String?>,
        category: String?,
        postedAtEpochMillis: Long,
        ongoing: Boolean,
        actionTitles: List<String>,
    ): NotificationContent = NotificationContent(
        packageName = packageName,
        appLabel = appLabel.normalised(),
        title = extras.text(KEY_TITLE),
        text = extras.text(KEY_TEXT),
        subText = extras.text(KEY_SUB_TEXT),
        bigText = extras.text(KEY_BIG_TEXT),
        category = category.normalised(),
        postedAtEpochMillis = postedAtEpochMillis,
        ongoing = ongoing,
        actionLabels = actionTitles.mapNotNull { it.normalised() },
    )

    /** A reported string, or `null` when it is absent or only whitespace. */
    private fun String?.normalised(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, String?>.text(key: String): String? = this[key].normalised()
}
