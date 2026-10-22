package com.sheen.rokid.phone.lock

/**
 * The one policy for a screenshot-lock PIN: digits only, 4-16 characters, no trimming or normalization.
 *
 * Digits-only is a security boundary, not a UI preference: the unlock path sends key codes so the PIN never becomes a
 * shell argument, and a non-ASCII/pattern credential cannot be expressed as key codes at all.
 */
object LockPinValidator {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 16

    fun normalize(input: String): String? = input.takeIf {
        it.length in MIN_LENGTH..MAX_LENGTH && it.all { c -> c in '0'..'9' }
    }
}
