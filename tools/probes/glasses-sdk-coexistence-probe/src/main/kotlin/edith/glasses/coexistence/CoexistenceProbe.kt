package edith.glasses.coexistence

/**
 * T0's answer as a value rather than a log line.
 *
 * The question is "can the Glasses-side open SDK and the CXR-S bridge live in one app", and the failure this guards
 * against is the one that makes a device run worthless: both SDKs initialise, nothing throws, and at least one of them
 * is not actually working. `ok` therefore requires a positive signal from *each* question, and [missing] names the
 * ones that failed, in a fixed order, so the log can be read without interpretation.
 */
data class CoexistenceVerdict(val ok: Boolean, val missing: List<String>)

fun evaluate(glassSdkReady: Boolean, glassServiceReachable: Boolean, cxrRoundTrip: Boolean): CoexistenceVerdict {
    val missing = buildList {
        if (!glassSdkReady) add("glassSdkReady")
        if (!glassServiceReachable) add("glassServiceReachable")
        if (!cxrRoundTrip) add("cxrRoundTrip")
    }
    return CoexistenceVerdict(ok = missing.isEmpty(), missing = missing)
}
