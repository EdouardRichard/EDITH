package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.Assistance

interface CancellationHandle { fun cancel() }
interface TaskScope { fun launch(block: () -> Unit): CancellationHandle; fun cancelAll() }
interface SecretHandle { val id: String }
interface OutputSink { fun submit(assistance: Assistance): OutputSubmission }
enum class OutputSubmission { Accepted, Rejected }

interface HostServices {
    fun require(capability: Capability): Any
    val tasks: TaskScope
}

object EmptyHostServices : HostServices {
    override fun require(capability: Capability): Any = error("capability unavailable: $capability")
    override val tasks: TaskScope = object : TaskScope {
        override fun launch(block: () -> Unit): CancellationHandle { block(); return object : CancellationHandle { override fun cancel() = Unit } }
        override fun cancelAll() = Unit
    }
}
