package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.MainActivity

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Shell starts the real Activity in the instrumented process without app-UID startActivitySync.
internal class ForegroundTestActivity private constructor() : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val events = LinkedBlockingQueue<Pair<MainActivity, Stage>>()
    @Volatile private var activity: MainActivity? = null
    private val callback = ActivityLifecycleCallback { observed, stage ->
        if (observed is MainActivity) {
            if (stage == Stage.RESUMED) activity = observed
            events.offer(observed to stage)
        }
    }

    fun onActivity(action: (MainActivity) -> Unit) = onMain("foreground Activity action") {
        val current = requireNotNull(activity)
        check(monitor.getLifecycleStageOf(current) == Stage.RESUMED) { "MainActivity must be RESUMED" }
        check(current.hasWindowFocus()) { "MainActivity must have foreground window focus" }
        action(current)
    }

    fun recreate() {
        val original = requireNotNull(activity)
        events.clear()
        onActivity { it.recreate() }
        awaitActivity("original Activity destruction during recreation") { it.first === original && it.second == Stage.DESTROYED }
        val replacement = awaitActivity("replacement Activity RESUMED") { it.first !== original && it.second == Stage.RESUMED }
        check(replacement !== original) { "recreation must create a different Activity" }
        awaitWindowFocus(replacement)
    }

    override fun close() {
        try {
            val current = activity
            if (current != null) {
                events.clear()
                val finished = onMain("finish foreground Activity") {
                    if (monitor.getLifecycleStageOf(current) == Stage.DESTROYED) false
                    else { current.finish(); true }
                }
                if (finished) awaitActivity("foreground Activity destruction") { it.first === current && it.second == Stage.DESTROYED }
            }
        } finally {
            onMain("remove Activity lifecycle callback") { monitor.removeLifecycleCallback(callback) }
        }
    }

    private fun awaitActivity(operation: String, matches: (Pair<MainActivity, Stage>) -> Boolean): MainActivity {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AssertionError("$operation not observed within 10 seconds")
            val event = events.poll(remaining, TimeUnit.NANOSECONDS)
                ?: throw AssertionError("$operation not observed within 10 seconds")
            if (matches(event)) return event.first
        }
    }

    private fun <T> onMain(operation: String, action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "helper must run on the test thread" }
        val task = FutureTask(action)
        check(main.post(task)) { "main thread rejected $operation" }
        return try { awaitTestTask(task, operation) } finally { main.removeCallbacks(task) }
    }

    private fun awaitWindowFocus(expected: MainActivity) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            if (onMain("observe foreground window focus") {
                activity === expected && monitor.getLifecycleStageOf(expected) == Stage.RESUMED && expected.hasWindowFocus()
            }) return
            if (System.nanoTime() >= deadline) throw AssertionError("MainActivity window focus not observed within 10 seconds")
            Thread.sleep(10)
        }
    }

    companion object {
        fun launch(): ForegroundTestActivity {
            val foreground = ForegroundTestActivity()
            try {
                foreground.onMain("register Activity lifecycle callback") { foreground.monitor.addLifecycleCallback(foreground.callback) }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val component = ComponentName(instrumentation.targetContext, MainActivity::class.java).flattenToString()
                // No -W: lifecycle callbacks supply the bounded readiness check. A fresh task avoids stale Activities.
                val command = "am start -n $component -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000"
                val descriptor = AtomicReference<ParcelFileDescriptor?>()
                val task = FutureTask {
                    val pipe = instrumentation.uiAutomation.executeShellCommand(command)
                    descriptor.set(pipe)
                    if (Thread.currentThread().isInterrupted) {
                        pipe.close()
                        throw InterruptedException("shell launch was cancelled")
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
                }
                val worker = Thread(task, "t14-shell-foreground-start").apply { isDaemon = true; start() }
                val output = try {
                    awaitTestTask(task, "shell foreground launch")
                } finally {
                    descriptor.get()?.close()
                    worker.interrupt()
                }
                val resumed = foreground.awaitActivity("MainActivity RESUMED; shell output: $output") { it.second == Stage.RESUMED }
                foreground.awaitWindowFocus(resumed)
                return foreground
            } catch (failure: Throwable) {
                try { foreground.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
