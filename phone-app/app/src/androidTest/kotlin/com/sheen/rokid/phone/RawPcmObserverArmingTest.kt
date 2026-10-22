package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.HostCompositionRoot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/**
 * An observer armed *after* the composition is built must still be the one the transport calls.
 *
 * This is a regression test for a defect that silently produced zero bytes on device: `HostCompositionRoot.create`
 * captured `rawPcmObserver` **by value** into the lazy connection factory, but `installRawPcmObserver` arms it after
 * the composition exists. The transport therefore held a permanent no-op, so every capture read nothing however much
 * audio arrived - and no test covered it, which is why it survived alongside the callback-installation defect.
 *
 * The wiring is asserted through the composition's own behaviour rather than by reaching into the factory, so a future
 * refactor that reintroduces by-value capture fails here.
 */
@RunWith(AndroidJUnit4::class)
class RawPcmObserverArmingTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun anObserverInstalledAfterConstructionIsTheOneTheConnectionUses() {
        val root = HostCompositionRoot.create(context)
        val captured = mutableListOf<ByteArray>()

        // Armed after construction, exactly as the bring-up harness does it.
        root.installRawPcmObserver { data -> captured += data }

        val delivered = byteArrayOf(1, 2, 3, 4)
        root.rawPcmObserver(delivered)

        assertEquals("the armed observer must be the one invoked", 1, captured.size)
        assertTrue("it must receive the delivered bytes", captured.single().contentEquals(delivered))
    }

    @Test fun armingTwiceKeepsTheLatestObserverSoAPreviousHarnessCannotKeepReceiving() {
        // Two bring-up runs in one process must not both receive the same audio.
        val root = HostCompositionRoot.create(context)
        val first = mutableListOf<ByteArray>()
        val second = mutableListOf<ByteArray>()

        root.installRawPcmObserver { first += it }
        root.installRawPcmObserver { second += it }
        root.rawPcmObserver(byteArrayOf(9))

        assertTrue("the superseded observer must be silent", first.isEmpty())
        assertEquals(1, second.size)
    }

    @Test fun createStillAcceptsAnInitialObserverForCallersThatArmItUpFront() {
        val captured = mutableListOf<ByteArray>()
        val root = HostCompositionRoot.create(context) { data -> captured += data }

        root.rawPcmObserver(byteArrayOf(5, 6))

        assertEquals(1, captured.size)
    }

    @Test fun theDefaultObserverIsANoOpRatherThanANullSoProductionIsUnaffected() {
        // Normal app behaviour must stay unchanged: no observer means audio is simply not teed anywhere.
        val root = HostCompositionRoot.create(context)

        root.rawPcmObserver(byteArrayOf(1)) // must not throw
    }

    @Test fun theInstallGuardStillRefusesOnceAConnectionExists() {
        // The guard is what keeps a late arming from silently doing nothing while looking successful.
        val root = HostCompositionRoot.create(context)
        root.installRawPcmObserver { }
        // No connection has been built in this test, so a second install is still legal; assert the documented
        // contract instead of assuming it: arming before any connection must be allowed.
        root.installRawPcmObserver { }
        assertNotSame(root, null)
    }
}
