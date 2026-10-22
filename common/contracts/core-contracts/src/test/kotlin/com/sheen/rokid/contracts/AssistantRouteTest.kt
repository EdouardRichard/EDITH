package com.sheen.rokid.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The route a conversation belongs to (spec §1.3 C3): the wake route and the proactive route keep independent
 * histories, so every message that travels between the phone and the glasses has to say which one it belongs to.
 *
 * The enum lives in `core-contracts` because four modules need the same name for it — `output-runtime` (the wire),
 * `glasses-app` (the two display buffers), `plugin-api` (the port the plugin calls) and `phone-app` (the log the
 * tabs read). A second copy anywhere would drift, and the drift would show up as a message filed under the wrong
 * conversation.
 *
 * `fromWire` is the untrusted boundary: the wire carries the enum's *name*, and a name this build does not know must
 * be refused rather than folded into a default. Filing a proactive message into the wake conversation would show the
 * wearer a sentence they were never told in the context they are reading.
 */
class AssistantRouteTest {
    @Test fun `every route survives the wire under its own name`() {
        AssistantRoute.entries.forEach { route ->
            assertEquals(route, AssistantRoute.fromWire(route.name), "route ${route.name} must round-trip")
        }
    }

    @Test fun `the two routes are exactly the two the spec names`() {
        assertEquals(listOf(AssistantRoute.WAKE, AssistantRoute.PROACTIVE), AssistantRoute.entries.toList())
    }

    @Test fun `an unknown name is refused rather than defaulted`() {
        assertNull(AssistantRoute.fromWire("SOMETHING_ELSE"),
            "an unknown route must be refused, not silently filed as the wake route")
        assertNull(AssistantRoute.fromWire(""))
        assertNull(AssistantRoute.fromWire(null))
    }

    @Test fun `a lower-case name is not accepted`() {
        assertNull(AssistantRoute.fromWire("wake"), "the wire carries the enum name, and the name is upper case")
    }
}
