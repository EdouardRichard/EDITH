package com.sheen.rokid.output

import kotlin.test.Test
import kotlin.test.assertEquals

class TtsAvailabilityTest {
    @Test fun `no engine means unavailable regardless of other flags`() {
        assertEquals(TtsAvailability.NO_ENGINE, availability(enginePresent = false, languageResult = 0, initSucceeded = true))
        assertEquals(TtsAvailability.NO_ENGINE, availability(enginePresent = false, languageResult = -1, initSucceeded = false))
    }

    @Test fun `engine present but language unsupported`() {
        // LANG_MISSING_DATA = -1, LANG_NOT_SUPPORTED = -2
        assertEquals(TtsAvailability.LANGUAGE_UNSUPPORTED, availability(enginePresent = true, languageResult = -1, initSucceeded = true))
        assertEquals(TtsAvailability.LANGUAGE_UNSUPPORTED, availability(enginePresent = true, languageResult = -2, initSucceeded = true))
    }

    @Test fun `engine and language ok but init failed`() {
        // LANG_AVAILABLE = 0 or higher
        assertEquals(TtsAvailability.INIT_FAILED, availability(enginePresent = true, languageResult = 0, initSucceeded = false))
    }

    @Test fun `all conditions met means ready`() {
        assertEquals(TtsAvailability.READY, availability(enginePresent = true, languageResult = 0, initSucceeded = true))
        assertEquals(TtsAvailability.READY, availability(enginePresent = true, languageResult = 1, initSucceeded = true))
    }
}
