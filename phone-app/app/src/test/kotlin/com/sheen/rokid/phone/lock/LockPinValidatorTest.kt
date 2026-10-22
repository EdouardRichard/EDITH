package com.sheen.rokid.phone.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockPinValidatorTest {

    @Test
    fun aFourToSixteenDigitPinIsAccepted() {
        assertEquals("1234", LockPinValidator.normalize("1234"))
        assertEquals("123456", LockPinValidator.normalize("123456"))
        assertEquals("1234567890123456", LockPinValidator.normalize("1234567890123456"))
    }

    @Test
    fun nonDigitsAndOutOfRangePinsAreRejected() {
        assertNull(LockPinValidator.normalize(""))
        assertNull(LockPinValidator.normalize("123"))
        assertNull(LockPinValidator.normalize("12a4"))
        assertNull(LockPinValidator.normalize("12345678901234567"))
        assertNull(LockPinValidator.normalize(" 1234 "))
        // `Char.isDigit()` accepts non-ASCII digits; the unlock path maps a digit to KEYCODE_0 + (c - '0'), so a
        // full-width/Arabic-Indic digit would become the wrong key code. Only ASCII digits are accepted.
        assertNull(LockPinValidator.normalize("１２３４"))
        assertNull(LockPinValidator.normalize("١٢٣٤"))
    }
}
