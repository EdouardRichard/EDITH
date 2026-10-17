package edith.testsupport

import edith.contracts.*
import edith.plugin.OutputSink

class FakeGlassesOutput : OutputSink {
    val accepted = mutableListOf<Assistance>()
    override fun submit(assistance: Assistance): edith.plugin.OutputSubmission { accepted += assistance; return edith.plugin.OutputSubmission.Accepted }
}
