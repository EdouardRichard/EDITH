package com.sheen.rokid.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSmokeTest {
    @Test
    fun testRuntimeCanInstantiateAContractValue() {
        val contractValue = SmokeContractValue("smoke")
        assertEquals("smoke", contractValue.name)
    }

    private data class SmokeContractValue(val name: String)
}
