package edith.model

import edith.plugin.*
import edith.storage.ProfileRepository
import java.time.Clock
import edith.contracts.*
import java.util.UUID

class ControlledProfileTester(private val client: ControlledModelClient, private val repository: ProfileRepository,
    private val clock: MonotonicClock, private val wallClock: Clock = Clock.systemUTC()) : ProfileTester {
    override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        val profile = repository.get(profileId) ?: throw ContractError.ConfigurationRejected("Model configuration unavailable")
        val id = UUID.randomUUID().toString()
        val now = clock.nowNanos()
        val request = ModelRequest(EventEnvelope(SessionId("profile-probe:$profileId"), Generation(1), id, now, now),
            id, id, profile.id, profile.revision, "Reply briefly with plain text.", listOf(InputPart.Text("只回复测试通过")),
            emptyList(), emptyList(), OutputModality.TEXT, Long.MAX_VALUE)
        return client.probe(request) { event ->
            if (event is ModelStreamEvent.Terminal && event.end == ModelEnd.COMPLETED)
                repository.verify(profileId, CapabilityVerification(profile.revision, "TEXT_REQUEST", VerificationState.Verified,
                    wallClock.millis()))
            listener(event)
        }
    }
}
