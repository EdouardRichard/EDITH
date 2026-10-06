package edith.host

import edith.contracts.*

class OutputArbiter(private val nowNanos: () -> Long) {
    fun submit(assistance: Assistance): OutputStatus =
        if (assistance.expiresAtNanos <= nowNanos()) OutputStatus.Expired else OutputStatus.Accepted
}
