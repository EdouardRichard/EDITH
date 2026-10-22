package com.sheen.rokid.phone.platform

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds daemon threads for one plugin pool, each named apart: `<prefix>-1`, `<prefix>-2`, ...
 *
 * Both threads of the pool used to be called `edith-plugin-tasks` (the plan that introduced the pool said
 * `edith-plugin-tasks-N`); P3.3's Critical ANR was read off a thread dump, where a repeated name is exactly what hides
 * which thread is blocked on the confirmation question (P3.3 review, Minor 4).
 *
 * Daemon, because a pool must never be what keeps the process alive.
 */
internal fun pluginTaskThreadFactory(prefix: String = "edith-plugin-tasks"): ThreadFactory {
    val next = AtomicInteger()
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${next.incrementAndGet()}").apply { isDaemon = true }
    }
}
