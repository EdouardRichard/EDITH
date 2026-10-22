package com.sheen.rokid.phone.actions.a11y

import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceActions
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.RefResult
import com.sheen.rokid.device.ScreenFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R36 wiring regression: the production [RefRegistry] is a companion-held instance created with the
 * class, not a field that only exists once Android connects the service.
 *
 * `HostCompositionRoot.create` builds the kernel during app start, possibly before the
 * accessibility service connects. If the kernel fell back to a second registry there, the refs it
 * renders would be unknown to the service's adapter (which mints them).
 */
class EdithAccessibilityRegistryTest {

    @Test
    fun `the companion registry is one non-null instance before any service exists`() {
        val first = requireNotNull(EdithAccessibilityService.registry()) {
            "the kernel needs a registry at composition start"
        }

        assertSame(first, EdithAccessibilityService.registry())
    }

    @Test
    fun `a service instance exposes the companion registry instead of minting another`() {
        val shared = requireNotNull(EdithAccessibilityService.registry())
        val service = EdithAccessibilityService()

        assertSame(shared, service.registry)
        assertSame(shared, EdithAccessibilityService.registry())
    }

    @Test
    fun `refs minted by an adapter built on the companion registry resolve there`() {
        val registry = requireNotNull(EdithAccessibilityService.registry())
        val platform = ClickPlatform()
        val adapter = AccessibilityAdapter(platform, registry)

        val observation = adapter.observe(ScreenFilter.ALL)
        val ref = registry.assign(observation).values.single()

        assertTrue(registry.resolve(observation, ref) is RefResult.Ok)
        assertTrue(adapter.performNodeAction(ref, DeviceActions.CLICK))
        assertEquals(listOf(0), platform.clicks)
        // A registry that is not the one the adapter used cannot resolve its ref: the refs on the
        // screen belong to the production instance, so the kernel must be built with that one.
        assertTrue(RefRegistry().resolve(observation, ref) is RefResult.Stale)
    }

    /** One clickable application window, enough to exercise ref minting and one node action. */
    private class ClickPlatform : AccessibilityPlatform {
        val clicks = mutableListOf<Int>()

        override fun collect(maxNodes: Int): PlatformSnapshot = PlatformSnapshot(
            windows = listOf(
                PlatformWindow(
                    id = 1,
                    type = PlatformWindowTypes.APPLICATION,
                    layer = 0,
                    bounds = Bounds(0, 0, 1080, 2400),
                    packageName = "com.example.app",
                    title = null,
                    nodes = listOf(
                        PlatformNode(
                            nodeId = 0,
                            windowId = 1,
                            depth = 0,
                            className = "android.widget.Button",
                            text = "OK",
                            contentDescription = null,
                            viewIdResourceName = null,
                            bounds = Bounds(10, 10, 200, 100),
                            clickable = true,
                            longClickable = false,
                            editable = false,
                            scrollable = false,
                            checkable = false,
                            checked = false,
                            selected = false,
                            enabled = true,
                            focused = false,
                            hintText = null,
                            paneTitle = null,
                            tooltipText = null,
                            drawingOrder = 0,
                            actionIds = listOf(PlatformActionIds.CLICK),
                            collection = null,
                            range = null,
                        ),
                    ),
                ),
            ),
        )

        override fun click(nodeId: Int): Boolean {
            clicks += nodeId
            return true
        }

        override fun longClick(nodeId: Int): Boolean = false

        override fun scrollForward(nodeId: Int): Boolean = false

        override fun scrollBackward(nodeId: Int): Boolean = false

        override fun setText(nodeId: Int, text: String): Boolean = false

        override fun dispatchGesture(gesture: Gesture): Boolean = false
    }
}
