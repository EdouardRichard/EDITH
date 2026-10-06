package edith.contracts

sealed class ContractError(message: String) : IllegalArgumentException(message) {
    class ConfigurationRejected(message: String) : ContractError(message)
    class CapabilityMissing(message: String) : ContractError(message)
    class SessionUnavailable(message: String) : ContractError(message)
    class ResourceConflict(message: String) : ContractError(message)
    class Cancelled : ContractError("cancelled")
    class Expired : ContractError("expired")
    class Transport(message: String) : ContractError(message)
}
