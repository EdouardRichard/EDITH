package edith.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemOneServicesContractTest {
    @Test
    fun `choice returns selectedIndex within bounds and probabilities sum to 1`() {
        val result = ChoiceResult(selectedIndex = 1, probabilities = floatArrayOf(0.2f, 0.6f, 0.2f))
        assertTrue(result.selectedIndex in result.probabilities.indices)
        assertEquals(1.0f, result.probabilities.sum(), 0.01f)
    }

    @Test
    fun `score returns array matching input size`() {
        val result = ScoreResult(scores = floatArrayOf(0.8f, 0.3f, 0.9f))
        assertEquals(3, result.scores.size)
    }

    @Test
    fun `noul confidence is in 0-1 range`() {
        val result = NoulResult(confidence = 0.75f)
        assertTrue(result.confidence in 0f..1f)
    }

    @Test
    fun `SystemOneStatus has three values`() {
        assertEquals(3, SystemOneStatus.entries.size)
        assertNotNull(SystemOneStatus.valueOf("LOADING"))
        assertNotNull(SystemOneStatus.valueOf("READY"))
        assertNotNull(SystemOneStatus.valueOf("UNAVAILABLE"))
    }
}
