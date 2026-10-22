package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.OutputSink

class FakeGlassesOutput : OutputSink {
    val accepted = mutableListOf<Assistance>()
    override fun submit(assistance: Assistance): com.sheen.rokid.plugin.OutputSubmission { accepted += assistance; return com.sheen.rokid.plugin.OutputSubmission.Accepted }
}
