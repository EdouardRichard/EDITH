package com.sheen.rokid.contracts

sealed class ContractError(message: String) : IllegalArgumentException(message) {
    class ConfigurationRejected(message: String) : ContractError(message)

    /**
     * 0.25 (rulings R70/R101): the send-time context budget's own "this request does not fit the model window" signal.
     *
     * Deliberately a **sibling** of [ConfigurationRejected], not a subclass (both are [ContractError]): the model
     * gateway's overflow recovery may answer only this signal, while [ConfigurationRejected] also means a
     * profile-revision race, an invalid endpoint or a client-state refusal. Retrying those with a cheaper context
     * would spend recoveries and relabel the real cause as `CONTEXT_OVERFLOW`.
     */
    class ContextWindowExceeded(message: String) : ContractError(message)
    class CapabilityMissing(message: String) : ContractError(message)
    class SessionUnavailable(message: String) : ContractError(message)
    class ResourceConflict(message: String) : ContractError(message)
    class Cancelled : ContractError("cancelled")
    class Expired : ContractError("expired")
    class Transport(message: String) : ContractError(message)
}
