package com.sheen.rokid.phone.actions.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The accessibility service configuration is the only authority for what the production service
 * may do (0.25 Task 9, spec §6.4).
 *
 * These are JVM tests without Robolectric: the XML is a resource file, so parsing it directly is
 * both simpler and stricter than rendering it through an Android harness.
 */
class AccessibilityConfigTest {

    @Test
    fun configIncludesAllSixCapabilitiesTheAdapterNeeds() {
        val service = accessibilityServiceElement()

        assertEquals("true", service.getAttribute("android:canRetrieveWindowContent"))
        assertEquals("true", service.getAttribute("android:canPerformGestures"))
        assertEquals("true", service.getAttribute("android:canTakeScreenshot"))

        val flags = service.getAttribute("android:accessibilityFlags")
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        assertTrue("flagRetrieveInteractiveWindows missing from $flags", "flagRetrieveInteractiveWindows" in flags)
        assertTrue("flagReportViewIds missing from $flags", "flagReportViewIds" in flags)
        assertTrue("flagIncludeNotImportantViews missing from $flags", "flagIncludeNotImportantViews" in flags)

        assertEquals("typeAllMask", service.getAttribute("android:accessibilityEventTypes"))
    }

    @Test
    fun configKeepsTheExistingServiceDescriptionAndFeedback() {
        val service = accessibilityServiceElement()

        assertEquals("feedbackGeneric", service.getAttribute("android:accessibilityFeedbackType"))
        assertEquals("@string/a11y_service_description", service.getAttribute("android:description"))
        assertEquals("100", service.getAttribute("android:notificationTimeout"))
    }

    private fun accessibilityServiceElement(): Element {
        val candidates = listOf(
            File("src/main/res/xml/edith_accessibility_config.xml"),
            File("phone-app/app/src/main/res/xml/edith_accessibility_config.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            fail("accessibility config not found; looked at ${candidates.joinToString { it.absolutePath }}")
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.documentElement
    }
}
