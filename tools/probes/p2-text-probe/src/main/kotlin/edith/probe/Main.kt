package edith.probe

import com.sheen.rokid.model.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.*
import okhttp3.OkHttpClient
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    var config: Path? = null
    var probe = false
    try {
        var index = 0
        while (index < args.size) {
            when (args[index++]) {
                "--config" -> { require(config == null && index < args.size); config = Path.of(args[index++]) }
                "--synthetic-probe" -> { require(!probe); probe = true }
                else -> throw IllegalArgumentException()
            }
        }
    } catch (_: Exception) { println("CLI_INVALID"); return }
    val path = config ?: run { println("CONFIG_MISSING"); return }
    MemorySecretResolver().use { resolver ->
        val loaded = DevelopmentProfileLoader(resolver).load(path)
        if (loaded.safeCode != null) { println(loaded.safeCode); return }
        if (!probe || !loaded.allowSyntheticTextUpload) { println("PROBE_DISABLED"); return }
        val repository = MemoryProfileRepository(resolver::revoke)
        val profile = repository.save(loaded.profile ?: run { println("CONFIG_INVALID"); return })
        val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
        val timers = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "edith-probe-deadline").apply { isDaemon = true } }
        val scheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                val future = timers.schedule(task, (deadlineNanos - clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
            }
        }
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        try {
            ControlledModelClient(http, repository, resolver, CapabilityGate(), ContextBudgeter(), clock, scheduler,
                { RequestConsent(true, false, false, true) }).use { client ->
                val terminal = CountDownLatch(1)
                try {
                    val handle = ControlledProfileTester(client, repository, clock).testText(profile.id) { event ->
                        if (event is ModelStreamEvent.Terminal) { println("STATUS_${event.end.name}"); terminal.countDown() }
                    }
                    try { terminal.await() } catch (_: InterruptedException) { handle.cancel(); Thread.currentThread().interrupt() }
                } catch (_: Exception) { println("PROBE_REJECTED") }
            }
        } finally {
            timers.shutdownNow(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
