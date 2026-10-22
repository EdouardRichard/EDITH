package com.sheen.rokid.phone.perception

/**
 * The screen source could not produce an observation, with the kernel's own stable reason.
 *
 * Before this type the host's observe lambda threw a bare `IllegalStateException`, the provider swallowed it into
 * `null`, and the builder rendered every failure as `unavailable(no_accessibility)` - so a locked or dozing phone was
 * reported to the model as "the accessibility permission is not open", and the model told the wearer exactly that
 * (2026-10-22 K70 acceptance). Carrying the code lets the coverage line say `locked` / `screen_off` honestly.
 */
class ScreenReadUnavailable(val reason: String?) :
    IllegalStateException(reason ?: "SCREEN_READ_UNAVAILABLE")
