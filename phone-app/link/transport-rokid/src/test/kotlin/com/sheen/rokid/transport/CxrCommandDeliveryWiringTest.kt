package com.sheen.rokid.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which layer serves the **inbound** custom-command direction.
 *
 * Kept deliberately small (decision D41: the official sample defines these interactions, so the project does not
 * re-derive the channel). What is worth pinning is the layer choice, because getting it wrong is invisible: the
 * session-level registration delivered nothing on device while the sample's client-level one is what the vendor's own
 * phone code uses.
 *
 * The routing behind the callback is exercised by [BinaryChannelRoutingTest] through the same rig helper, so it is not
 * duplicated here.
 */
class CxrCommandDeliveryWiringTest {

    @Test
    fun theInboundCallbackIsInstalledOnTheClientLikeTheOfficialSample() {
        val rig = CxrBackendRig()
        rig.authorize()

        assertEquals("the client-level subscription must be the one installed", 1, rig.commandInstaller.installs)
        assertNull("the session registration must not also be active, or a reply would be routed twice",
            rig.commandCallback)
    }

    @Test
    fun whenTheClientCannotBeReachedTheSessionRegistrationIsUsedInstead() {
        val rig = CxrBackendRig()
        rig.commandInstaller.installResult = false

        rig.authorize()

        assertNotNull("the fallback keeps a registration on firmware where the session layer serves it",
            rig.commandCallback)
    }

    @Test
    fun theClientSubscriptionIsReleasedWithTheConnection() {
        val rig = CxrBackendRig()
        rig.authorize()

        rig.backend.close()

        assertEquals(1, rig.commandInstaller.releases)
    }
}
