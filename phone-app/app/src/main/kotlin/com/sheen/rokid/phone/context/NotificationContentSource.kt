package com.sheen.rokid.phone.context

/** How many recent notifications the listener keeps, and the most [NotificationContentSource.recent] can return. */
internal const val NOTIFICATION_CONTENT_LIMIT = 16

/**
 * One notification's perceptible content (edith-0.3 Phase 2, Task 3; spec §7.2, D179).
 *
 * D179 supersedes the old category-only rule: the assistant may perceive this content, so the fields are carried
 * explicitly instead of being reduced to a category. Content-bearing values must never be interpolated into a log line
 * or an evidence file - only counts/categories may be logged (spec §7.8) - and nothing here is persisted. There is no
 * `PendingIntent`, action intent or `Bundle` field, so an action can only ever contribute its [actionLabels] title.
 *
 * `postedAtEpochMillis` is the platform post time, preserved exactly as reported; it is not used to reorder the ring.
 */
internal data class NotificationContent(
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val category: String?,
    val postedAtEpochMillis: Long,
    val ongoing: Boolean,
    val actionLabels: List<String>,
)

/**
 * The in-memory ring the listener service fills, and the one place its bounds are implemented (Task 3).
 *
 * The ring is insertion-ordered: [add] appends (a later post is newer) and [snapshot] walks backwards, so the result is
 * newest-first. It holds at most [capacity] values - older ones are dropped, never persisted. Every access is
 * synchronized, because the system delivers posts/disconnects on its own thread while the perception path reads from
 * the app's. It deliberately has no timestamp-based reordering: the platform's `postTime` is content metadata, not the
 * ring's ordering key.
 */
internal class NotificationContentRing(private val capacity: Int = NOTIFICATION_CONTENT_LIMIT) {

    private val lock = Any()
    private val entries = ArrayDeque<NotificationContent>()

    /** Appends the newest notification, dropping the oldest when the ring is full. */
    fun add(content: NotificationContent) {
        synchronized(lock) {
            entries.addLast(content)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** Replaces the ring with active notifications supplied oldest-first, keeping the newest [capacity]. */
    fun seed(contents: List<NotificationContent>) {
        synchronized(lock) {
            entries.clear()
            contents.forEach { content ->
                entries.addLast(content)
                while (entries.size > capacity) entries.removeFirst()
            }
        }
    }

    /** Drops all captured content (the listener grant was lost, so nothing may stay readable). */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    /** The newest [limit] values, newest-first; a non-positive limit is an empty answer, never "everything". */
    fun snapshot(limit: Int): List<NotificationContent> {
        if (limit <= 0) return emptyList()
        val bounded = limit.coerceAtMost(capacity)
        return synchronized(lock) { entries.asReversed().take(bounded) }
    }
}

/**
 * One notification read: the captured content plus whether the producer actually answered (edith-0.3 Phase 2, final
 * review I3).
 *
 * An empty [items] with `available = true` is "there are no notifications"; an empty list with `available = false` is
 * "the host could not read the source" and must be reported as `unavailable(...)`, never as `empty` (spec §7.1/§7.2:
 * a failed read is not an empty result).
 */
internal data class NotificationRead(
    val items: List<NotificationContent>,
    val available: Boolean,
)

/**
 * The injectable facade over the listener's captured notification content (edith-0.3 Phase 2, Task 3).
 *
 * The service is the only producer: it maps each post with [NotificationContentMapper] and keeps the last
 * [NOTIFICATION_CONTENT_LIMIT] in memory. This class is the JVM-testable seam Task 6 consumes - it takes the three
 * reads as lambdas, so a test can drive it without Android, and production defaults to the service companion.
 *
 * Degradation is honest: a missing listener grant, a dead binder or any throwing producer is an empty list / no
 * category, never a stale value and never a crash. `latestCategory` stays the opaque category/channel string of the
 * existing `notification.category` signal; it is not a content accessor.
 */
internal class NotificationContentSource(
    private val connected: () -> Boolean = { NotificationCategoryService.isConnected() },
    private val recent: (Int) -> List<NotificationContent> = { NotificationCategoryService.recentContent(it) },
    private val category: () -> String? = { NotificationCategoryService.latest() },
) {

    /** Whether the notification listener is currently bound (the user's grant is live). */
    fun isConnected(): Boolean = runCatching { connected() }.getOrDefault(false)

    /**
     * The newest [limit] notifications, newest-first, capped at [NOTIFICATION_CONTENT_LIMIT].
     *
     * A non-positive limit and a missing grant are both an empty answer; the producer is not called in either case. A
     * producer that throws degrades to empty, and one that ignores the limit cannot widen it.
     */
    fun recent(limit: Int): List<NotificationContent> = read(limit).items

    /**
     * Final review I3: the same bounded read as [recent], plus whether the source actually answered.
     *
     * `available = false` covers both a missing listener grant and a throwing producer; the caller still checks
     * [isConnected] first so the two keep distinct coverage reasons (`no_access` vs `provider`). A non-positive limit
     * is an available empty answer and never calls the producer.
     */
    fun read(limit: Int): NotificationRead {
        if (limit <= 0) return NotificationRead(emptyList(), available = true)
        if (!isConnected()) return NotificationRead(emptyList(), available = false)
        val bounded = limit.coerceAtMost(NOTIFICATION_CONTENT_LIMIT)
        return runCatching { recent.invoke(bounded) }
            .fold(
                onSuccess = { NotificationRead(it.take(bounded), available = true) },
                onFailure = { NotificationRead(emptyList(), available = false) },
            )
    }

    /** The opaque category/channel string of the last categorised notification, or `null` when unavailable. */
    fun latestCategory(): String? {
        if (!isConnected()) return null
        return runCatching { category() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
