package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import kotlin.test.*

class OutputArbiterTest {
    @Test fun `expired output is rejected`() {
        val arbiter = OutputArbiter(nowNanos = { 100 })
        val output = Assistance("h", SessionId("s"), Generation(0), null, 1, "x", null, 99)
        assertEquals(OutputStatus.Expired, arbiter.submit(output))
    }
}
