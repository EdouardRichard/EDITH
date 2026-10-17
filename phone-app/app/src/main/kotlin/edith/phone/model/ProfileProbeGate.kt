package edith.phone.model

import edith.contracts.ContractError
import edith.model.ProfileTester
import edith.model.RequestConsent
import edith.plugin.*
import edith.storage.ProfileRepository
import java.util.concurrent.atomic.AtomicReference

internal class ProfileProbeGate(private val repository: ProfileRepository) {
    private val confirmation = AtomicReference<ProbeConsent?>()
    private val admission = ThreadLocal<ProbeConsent>()
    fun grant(consent: ProbeConsent?) { confirmation.set(consent) }
    fun requestConsent(): RequestConsent {
        val consent = admission.get()
        val profile = consent?.let { repository.get(it.id) }
        val allowed = consent != null && profile != null && consent.revision == profile.revision && consent.domain == domain(profile.baseUrl)
        return RequestConsent(allowed, false, false, allowed)
    }
    fun test(tester: ProfileTester, profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        val consent = confirmation.getAndSet(null) ?: throw ContractError.ConfigurationRejected("Probe confirmation required")
        val profile = repository.get(profileId) ?: throw ContractError.ConfigurationRejected("Profile unavailable")
        if (consent.id != profile.id || consent.revision != profile.revision || consent.domain != domain(profile.baseUrl))
            throw ContractError.ConfigurationRejected("Probe confirmation expired")
        admission.set(consent)
        try { return tester.testText(profileId, listener) } finally { admission.remove() }
    }
}
