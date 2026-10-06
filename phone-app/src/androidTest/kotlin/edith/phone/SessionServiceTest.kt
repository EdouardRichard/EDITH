package edith.phone

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.AndroidJUnitRunner
import edith.contracts.*
import edith.host.*
import edith.plugin.*
import edith.storage.MemoryProfileRepository
import edith.testsupport.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

class PhoneTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, FakePhoneApplication::class.java.name, context)
}

class FakePhoneApplication : EdithApplication() {
    val inputEpochs = CopyOnWriteArrayList<InputEpoch>()
    val stoppedEpochs = CopyOnWriteArrayList<InputEpoch>()
    @Volatile var resource: ResourceScope? = null
    override fun createComposition(): HostCompositionRoot {
        val env = object : HostEnvironment {
            override val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime() }
            override val input = object : InputPort {
                override fun start(epoch: InputEpoch) { inputEpochs += epoch }
                override fun stop(epoch: InputEpoch) { stoppedEpochs += epoch }
            }
            override val output = RecordingHintDevice()
            override val availableCapabilities = setOf(Capability.Audio)
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices {
                resource = scope
                return ScopedHostServices(scope, granted, mapOf(Capability.Audio to object : AudioSubscription {
                    override fun listen(listener: (AudioSegment) -> Unit): CancellationHandle = FakeCancellation()
                }), FakeTaskScope())
            }
        }
        val host = HostRuntime(1, env)
        host.register(object : PluginLifecycle {
            override val descriptor = PluginDescriptor("p2-diagnostic", "1.0.0", 1, "test", listOf(Capability.Audio), ConfigSchema(1))
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        })
        return HostCompositionRoot(host, MemoryProfileRepository(), object : ModelClient {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                throw AssertionError("instrumentation must never request a model")
        }, env)
    }
}

@RunWith(AndroidJUnit4::class)
class SessionServiceTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as FakePhoneApplication

    @Before fun reset() { app.composition.stop(); app.inputEpochs.clear(); app.stoppedEpochs.clear() }
    @After fun stop() {
        app.composition.stop()
        context.stopService(Intent(context, SessionService::class.java))
        await { app.composition.state.value.session == SessionState.Idle }
    }

    @Test fun explicitStartExposesPauseStopAndStopRevokesScope() {
        ForegroundTestActivity.launch().use { scenario ->
            scenario.onActivity { SessionService.startFromUi(it, "p2-diagnostic") }
            await { app.composition.state.value.session == SessionState.BuildingScene }
            app.composition.onSceneReady()
            await { app.inputEpochs.size == 1 }
            assertEquals(SessionState.Ready, app.composition.state.value.session)
            val resource = requireNotNull(app.resource)
            val manager = context.getSystemService(NotificationManager::class.java)
            await { manager.activeNotifications.isNotEmpty() }
            val notification = manager.activeNotifications.single().notification
            assertEquals(listOf("暂停", "停止"), notification.actions.map { it.title.toString() })
            notification.actions[0].actionIntent.send()
            await { app.composition.state.value.session == SessionState.Paused }
            assertTrue(resource.isRevoked)
            notification.actions[1].actionIntent.send()
            await { app.composition.state.value.session == SessionState.Idle && manager.activeNotifications.isEmpty() }
            assertNull(app.composition.host.currentInputEpoch)
            assertEquals(1, app.stoppedEpochs.size)
        }
    }

    @Test fun activityRecreationObservesSameHostWithoutStartingInput() {
        ForegroundTestActivity.launch().use { scenario ->
            scenario.onActivity { SessionService.startFromUi(it, "p2-diagnostic") }
            await { app.composition.state.value.session == SessionState.BuildingScene }
            app.composition.onSceneReady()
            await { app.inputEpochs.size == 1 }
            val original = app.composition.host
            scenario.recreate()
            assertSame(original, app.composition.host)
            assertEquals(1, app.inputEpochs.size)
            assertEquals(SessionState.Ready, app.composition.state.value.session)
        }
    }

    @Test fun unauthorisedServiceIntentNeverStartsInput() {
        context.startService(Intent(context, SessionService::class.java).setAction("edith.START"))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(SessionState.Idle, app.composition.state.value.session)
        assertTrue(app.inputEpochs.isEmpty())
    }

    @Test fun deniedAdmissionAndRedeliveryNeverRestartInput() {
        val root = app.composition
        val denied = root.authorizeStart("p2-diagnostic")
        assertFalse(root.dispatch("edith.START", denied, permissionGranted = false))
        assertEquals("BLUETOOTH_PERMISSION_DENIED", root.state.value.errorCode)
        val replay = root.authorizeStart("p2-diagnostic")
        assertFalse(root.dispatch("edith.START", replay, redelivered = true))
        assertFalse(root.dispatch(null))
        assertTrue(app.inputEpochs.isEmpty())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("expected lifecycle outcome within 3 seconds", condition())
    }
}
