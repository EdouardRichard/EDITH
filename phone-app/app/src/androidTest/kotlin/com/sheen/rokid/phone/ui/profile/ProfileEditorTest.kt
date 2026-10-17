package com.sheen.rokid.phone.ui.profile
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.ui.profile.domain
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.storage.opaqueSecret
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.DummyVaultFixture
import com.sheen.rokid.phone.storage.AndroidProfileRepository

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Xml
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.File
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class ProfileEditorTest {
    @Test fun correctedFieldsAndDecimalKSaveCanRetryWithoutReenteringSyntheticSecret() = DummyVaultFixture().use { fixture ->
        val vault = KeystoreVault(fixture.blobs); val repo = AndroidProfileRepository(fixture.blobs, vault)
        var attempts = 0; var scoped: CharArray? = null
        ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ProfileEditorScreen(null, { draft, secret ->
                        attempts++; scoped = secret
                        if (attempts == 1) throw IllegalStateException("synthetic private save error")
                        repo.saveWithSecret(draft, secret); true
                    }, {})
                }
            } } }
            setText("名称", "Synthetic correction"); setText("服务", "Synthetic")
            setText("基础 URL", "https://synthetic.example/v1")
            scrollTo("OpenAI Responses API（文本）")
            assertFalse(hasVisibleText("DeepSeek Responses · 文本"))
            setText("API Key", "synthetic-correction-secret")
            scrollTo("保存"); click("保存")
            await { hasVisibleText("模型：必填") }
            assertEquals(0, attempts); assertTrue(repo.list().isEmpty())
            scrollTo("模型", backwards = true); setText("模型", "synthetic-model")
            scrollTo("最大输入（K tokens）"); setText("最大输入（K tokens）", "8.0009765625")
            scrollTo("最大输出（K tokens）"); setText("最大输出（K tokens）", "1.5")
            assertFalse(hasEditable("输入预算（tokens）")); assertFalse(hasEditable("安全余量（tokens）"))
            scrollTo("保存"); click("保存")
            await { attempts == 1 && hasVisibleText("保存未完成，请重试") }
            assertTrue(repo.list().isEmpty()); assertTrue(requireNotNull(scoped).all { it == '\u0000' })
            click("保存"); await { repo.list().size == 1 }
            val saved = repo.list().single()
            assertEquals(2, attempts); assertEquals("DEEPSEEK_RESPONSES", saved.protocolId)
            assertEquals(8193, saved.usableWindowTokens); assertEquals(1536, saved.maxOutputTokens)
            assertEquals(6401, saved.inputBudgetTokens); assertEquals(256, saved.safetyTokens)
            assertTrue(requireNotNull(scoped).all { it == '\u0000' })
            vault.withSecret(saved.secret) { assertEquals("synthetic-correction-secret", String(it)) }
        }
    }

    @Test fun descendantLabelFixtureFindsUniqueEditableAndExcludesReadonlyProtocol() {
        val root = parseFrozenFixture(
            """
            <hierarchy>
              <node editable="false">
                <node editable="true"><node text="名称" /></node>
                <node editable="true" password="true"><node text="API Key" /></node>
                <node editable="false" text="DeepSeek Responses · 文本"><node text="协议" /></node>
              </node>
            </hierarchy>
            """.trimIndent()
        )
        assertEquals("名称", findFixtureEditable(root, "名称")?.label)
        assertTrue(requireNotNull(findFixtureEditable(root, "API Key")).password)
        assertNull(findFixtureEditable(root, "协议"))
    }

    @Test fun editorSavesDummyProfileAndIndependentModalitiesWithoutUploading() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        val vm = ProfileViewModel(repo, object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle = throw AssertionError("No upload in UI tests")
        })
        try { ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                val state by vm.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ProfileListScreen(vm, state, listOf("smart-assistant" to "智能助手", "interview-assistant" to "面试助手"))
                }
            } } }
            click("新建档案")
            setText("名称", "Synthetic UI profile"); setText("服务", "Synthetic")
            setText("基础 URL", "https://synthetic.example/v1"); setText("模型", "synthetic-model")
            setText("API Key", "synthetic-dummy-secret")
            withEditable("API Key") { assertTrue(it.isPassword) }
            click("IMAGE"); click("AUDIO", betweenLabels = "声明输入" to "声明输出")
            scrollTo("保存"); click("保存")
            await { repo.list().size == 1 }
            val saved = repo.list().single()
            assertEquals(InputModality.entries.toSet(), saved.inputs)
            assertEquals(setOf(OutputModality.TEXT), saved.outputs)
            assertTrue(saved.verifications.isEmpty())
            click("文本连接测试"); await { vm.state.value.confirmation != null }
            assertEquals("synthetic.example", vm.state.value.confirmation!!.domain)
            click("取消"); assertNull(vm.state.value.confirmation)
            click("删除 Synthetic UI profile"); click("取消"); assertEquals(1, repo.list().size)
            click("删除 Synthetic UI profile"); click("删除"); await { repo.list().isEmpty() }
        } } finally { vm.close() }
    }

    @Test fun cancellingEditorLeavesNoDummyCredentialOrAutomaticProbe() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileEditorScreen(null, { _, _ -> throw AssertionError("Cancelled editor must not save") }, { activity.setContent { MaterialTheme {} } })
                }
            } } }
            setText("API Key", "synthetic-dummy-secret"); scrollTo("取消"); click("取消")
            assertTrue(repo.list().isEmpty()); assertTrue(fixture.files().isEmpty())
        }
    }

    @Test fun editingWithBlankSecretRetainsCredentialAndClearsVerification() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        val original = repo.saveWithSecret(testProfile("editable"), "synthetic-original-secret".toCharArray())
        repo.verify(original.id, CapabilityVerification(original.revision, "TEXT_REQUEST", VerificationState.Verified, 1))
        val vm = ProfileViewModel(repo, noUploadTester())
        try { ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileEditorScreen(repo.get(original.id), { draft, secret -> vm.save(draft, secret); vm.state.value.error == null }, {})
                }
            } } }
            setText("模型", "edited-model"); scrollTo("保存"); click("保存")
            await { repo.get(original.id)?.model == "edited-model" }
            val saved = requireNotNull(repo.get(original.id))
            assertEquals(original.secret.id, saved.secret.id)
            assertEquals(original.usableWindowTokens, saved.usableWindowTokens)
            assertEquals(original.maxOutputTokens, saved.maxOutputTokens)
            assertEquals(original.inputBudgetTokens, saved.inputBudgetTokens)
            assertEquals(original.safetyTokens, saved.safetyTokens)
            assertTrue(saved.verifications.isEmpty())
        } } finally { vm.close() }
    }

    @Test fun pluginSelectionsRemainIndependent() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        val smart = repo.saveWithSecret(testProfile("smart"), "synthetic-smart-secret".toCharArray())
        val interview = repo.saveWithSecret(testProfile("interview"), "synthetic-interview-secret".toCharArray())
        val vm = ProfileViewModel(repo, noUploadTester())
        try { ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                val state by vm.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileListScreen(vm, state, listOf("smart-assistant" to "智能助手", "interview-assistant" to "面试助手"))
                }
            } } }
            click("智能助手 档案")
            click(smart.name)
            click("面试助手 档案"); click(interview.name)
            await { repo.selections().size == 2 }
            assertEquals(smart.id, repo.selections()["smart-assistant"])
            assertEquals(interview.id, repo.selections()["interview-assistant"])
        } } finally { vm.close() }
    }

    @Test fun unsupportedCustomProtocolShowsErrorWithoutTesterCall() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        repo.saveWithSecret(testProfile("custom", protocol = "CUSTOM"), "synthetic-custom-secret".toCharArray())
        val tester = RecordingProfileTester()
        val vm = ProfileViewModel(repo, tester)
        try { ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                val state by vm.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileListScreen(vm, state, emptyList())
                }
            } } }
            click("文本连接测试")
            await { vm.state.value.error == "CAPABILITY_MISSING" && hasVisibleText("该档案不支持文本连接测试") }
            assertEquals(0, tester.calls)
        } } finally { vm.close() }
    }

    @Test fun confirmationCancelLoadingAndFailureRemainExplicit() = DummyVaultFixture().use { fixture ->
        val repo = AndroidProfileRepository(fixture.blobs, KeystoreVault(fixture.blobs))
        val profile = repo.saveWithSecret(testProfile("probe"), "synthetic-probe-secret".toCharArray())
        val tester = RecordingProfileTester()
        val vm = ProfileViewModel(repo, tester)
        try { ForegroundTestActivity.launch().use { foreground ->
            foreground.onActivity { activity -> activity.setContent { MaterialTheme {
                val state by vm.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileListScreen(vm, state, emptyList())
                }
            } } }
            click("文本连接测试")
            await { vm.state.value.confirmation != null && hasVisibleText("确认文本上传") }
            captureLayout("confirmation", listOf("确认文本上传", "目标域：synthetic.example", "内容类别：固定合成测试文本", "只回复测试通过", "同意并测试", "取消"))
            click("取消")
            await { vm.state.value.confirmation == null }
            assertEquals(0, tester.calls)
            click("文本连接测试"); click("同意并测试")
            await { vm.state.value.loadingId == profile.id && hasVisibleText("取消测试") }
            captureLayout("loading", listOf("Synthetic probe", "取消测试"))
            tester.emit(ModelEnd.FAILED)
            await { vm.state.value.loadingId == null && vm.state.value.error == "TEST_FAILED" && hasVisibleText("连接测试失败") }
            captureLayout("failure", listOf("连接测试失败", "Synthetic probe", "文本连接测试"))
            assertEquals(1, tester.calls)
        } } finally { vm.close() }
    }

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val scrollLog = mutableListOf<String>()

    private fun captureLayout(name: String, requiredLabels: List<String>) {
        if (InstrumentationRegistry.getArguments().getString("p2LayoutCapture") != "true") return
        automation.waitForIdle(100, 3000)
        // The list is scrollable; bring every required label into one viewport before asserting it is
        // simultaneously visible. This is reachability of the fixture, not a relaxation of the check.
        revealRequired(requiredLabels.toSet())
        val root = requireNotNull(automation.rootInActiveWindow)
        val rectangles = linkedMapOf<String, Rect>()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                assertFalse("Layout fixtures must not capture populated passwords", node.isPassword && !node.text.isNullOrEmpty())
                val label = node.text?.toString()
                if (label in requiredLabels && node.isVisibleToUser) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    check(rectangles.put(requireNotNull(label), bounds) == null) { "Duplicate visible label" }
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
        } finally { root.recycle() }
        writeLayoutDiagnostic(name, requiredLabels, rectangles.keys)
        assertEquals("All required labels remain visible", requiredLabels.toSet(), rectangles.keys)
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            val screen = Rect(0, 0, screenshot.width, screenshot.height)
            rectangles.values.forEach { assertTrue("Label is inside screen", !it.isEmpty && screen.contains(it)) }
            val entries = rectangles.entries.toList()
            entries.forEachIndexed { index, first -> entries.drop(index + 1).forEach { second ->
                assertFalse("Labels overlap: ${first.key}, ${second.key}", Rect.intersects(first.value, second.value))
            } }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.filesDir, "p2-layout-evidence").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val metrics = context.resources.displayMetrics
            val report = JSONObject().put("width", screenshot.width).put("height", screenshot.height)
                .put("densityDpi", metrics.densityDpi).put("fontScale", context.resources.configuration.fontScale)
                .put("fixture", "synthetic-local-no-network").put("nonOverlappingRequiredLabels", true)
                .put("labels", JSONArray().apply { entries.forEach { (label, bounds) ->
                    put(JSONObject().put("label", label).put("bounds", bounds.flattenToString()))
                } })
            File(directory, "$name.json").writeText(report.toString(2))
        } finally { screenshot.recycle() }
    }
    private data class EditableTarget(val info: AccessibilityNodeInfo, val isPassword: Boolean) : AutoCloseable {
        override fun close() { info.recycle() }
    }
    private data class FrozenNode(val text: String?, val editable: Boolean, val password: Boolean, val children: List<FrozenNode>)
    private data class FixtureEditable(val label: String, val password: Boolean)

    private fun withEditable(label: String, action: (EditableTarget) -> Unit) {
        revealEditable(label)
        val target = requireNotNull(findEditable(label))
        try { action(target) } finally { target.close() }
    }

    private fun hasEditable(label: String): Boolean = findEditable(label)?.also { it.close() } != null

    private fun writeLayoutDiagnostic(name: String, requiredLabels: List<String>, found: Set<String>) {
        val root = automation.rootInActiveWindow ?: return
        val nodes = JSONArray()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { text ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    nodes.put(JSONObject().put("text", text).put("visible", node.isVisibleToUser).put("bounds", bounds.flattenToString()))
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
        } finally { root.recycle() }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "p2-layout-evidence").apply { mkdirs() }
        val report = JSONObject().put("required", JSONArray(requiredLabels))
            .put("found", JSONArray(found.toList())).put("nodes", nodes)
        File(directory, "$name.diag.json").writeText(report.toString(2))
    }

    private fun revealEditable(label: String) {
        if (pollEditable(label, 800)) return
        repeat(6) {
            if (!scrollStep(forward = true)) return@repeat
            if (pollEditable(label, 900)) return
        }
        repeat(6) {
            if (!scrollStep(forward = false)) return@repeat
            if (pollEditable(label, 900)) return
        }
        writeEditableDiagnostic(label)
        fail("Editable '$label' not reachable within 5 seconds")
    }

    private fun writeEditableDiagnostic(name: String) {
        val root = automation.rootInActiveWindow
        val nodes = JSONArray()
        if (root != null) {
            try {
                fun visit(node: AccessibilityNodeInfo) {
                    val text = node.text?.toString()
                    if (!text.isNullOrEmpty() || node.isEditable || node.isScrollable) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val labels = labelsBelow(node).joinToString("|")
                        nodes.put(JSONObject().put("cls", node.className?.toString() ?: "")
                            .put("text", text ?: "").put("editable", node.isEditable).put("scrollable", node.isScrollable)
                            .put("visible", node.isVisibleToUser).put("labels", labels).put("bounds", bounds.flattenToString()))
                    }
                    repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                        try { visit(child) } finally { child.recycle() }
                    } }
                }
                visit(root)
            } finally { root.recycle() }
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "p2-layout-evidence").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        File(directory, "editable-diag-$safe.json").writeText(JSONObject().put("label", name)
            .put("scrollLog", JSONArray(scrollLog.toList())).put("nodes", nodes).toString(2))
    }

    private fun pollEditable(label: String, millis: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + millis
        while (true) {
            if (hasEditable(label)) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            SystemClock.sleep(20)
        }
    }

    private fun revealRequired(required: Set<String>) {
        if (pollRequired(required, 800)) return
        if (scanRequired(required, forward = true)) return
        if (scanRequired(required, forward = false)) return
    }

    private fun pollRequired(required: Set<String>, millis: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + millis
        while (true) {
            if (required.all { it in visibleTexts() }) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            SystemClock.sleep(20)
        }
    }

    private fun scanRequired(required: Set<String>, forward: Boolean): Boolean {
        repeat(6) {
            if (required.all { it in visibleTexts() }) return true
            if (!scrollStep(forward)) return required.all { it in visibleTexts() }
            SystemClock.sleep(120)
        }
        return required.all { it in visibleTexts() }
    }

    private fun visibleTexts(): Set<String> = snapshot().filter { it.visible }.mapNotNull { it.text }.toSet()

    private fun scrollStep(forward: Boolean): Boolean {
        val bounds = scrollableBounds() ?: return false
        val x = bounds.centerX()
        // Mirrors the gesture geometry the device helper already used to scroll this editor.
        val low = bounds.top + (bounds.height() * 0.79).toInt()
        val high = bounds.top + (bounds.height() * 0.29).toInt()
        val startY = if (forward) low else high
        val endY = if (forward) high else low
        return try {
            // A shell-injected swipe scrolls the Compose container reliably; ACTION_SCROLL_FORWARD is
            // rejected by this Compose scroll node and in-process input injection is not delivered.
            val output = automation.executeShellCommand("input swipe $x $startY $x $endY 300").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
            }
            scrollLog += (if (forward) "fwd" else "back") + " $startY->$endY:${output.trim()}"
            SystemClock.sleep(150)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun scrollableBounds(): Rect? {
        val root = automation.rootInActiveWindow ?: return null
        try {
            fun find(node: AccessibilityNodeInfo): Rect? {
                if (node.isScrollable && node.isVisibleToUser) return Rect().also(node::getBoundsInScreen)
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { find(child)?.let { return it } } finally { child.recycle() }
                } }
                return null
            }
            return find(root)
        } finally { root.recycle() }
    }

    private fun findEditable(label: String): EditableTarget? {
        val root = automation.rootInActiveWindow ?: return null
        val candidates = mutableListOf<EditableTarget>()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isEditable) candidates += EditableTarget(AccessibilityNodeInfo.obtain(node), node.isPassword)
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
            val matches = candidates.filter { candidate ->
                candidate.info.isVisibleToUser && labelsBelow(candidate.info).any { labelMatches(it, label) }
            }
            if (matches.size > 1) {
                candidates.forEach { it.close() }
                fail("Expected one visible editable labelled '$label', found ${matches.size}")
            }
            val match = matches.singleOrNull()
            candidates.filter { it !== match }.forEach { it.close() }
            return match
        } finally { root.recycle() }
    }

    private fun labelsBelow(node: AccessibilityNodeInfo): Set<String> {
        val labels = linkedSetOf<String>()
        fun add(value: CharSequence?) { value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(labels::add) }
        add(node.text); add(node.hintText); add(node.contentDescription)
        fun visit(child: AccessibilityNodeInfo) {
            add(child.text); add(child.hintText); add(child.contentDescription)
            repeat(child.childCount) { index -> child.getChild(index)?.let { nested ->
                try { visit(nested) } finally { nested.recycle() }
            } }
        }
        repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
            try { visit(child) } finally { child.recycle() }
        } }
        return labels
    }

    private fun labelMatches(value: String, requested: String) =
        value == requested || (requested == "API Key" && value.startsWith("API Key（"))

    private fun setText(label: String, value: String) {
        withEditable(label) { target ->
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            assertTrue("Field accepts text", target.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        }
    }

    private fun click(label: String, betweenLabels: Pair<String, String>? = null) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val match = findClickLabel(label, betweenLabels)
            if (match == null) {
                if (SystemClock.elapsedRealtime() >= end) fail("Control '$label' not visible within 5 seconds")
                scrollClickForward()
                SystemClock.sleep(100)
                continue
            }
            val target = try { nearestClickableOwned(match) } finally { match.recycle() }
            if (target == null) {
                if (SystemClock.elapsedRealtime() >= end) fail("Control '$label' has no fresh clickable ancestor within 5 seconds")
                SystemClock.sleep(100)
                continue
            }
            try {
                assertTrue("Control '$label' is visible and enabled", target.isVisibleToUser && target.isEnabled)
                assertTrue("Control '$label' accepts click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            } finally { target.recycle() }
        }
    }

    private fun findClickLabel(label: String, betweenLabels: Pair<String, String>?): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var insideSection = betweenLabels == null
        var starts = 0
        var ends = 0
        try {
            fun visit(node: AccessibilityNodeInfo) {
                val text = node.text?.toString()
                if (betweenLabels != null) {
                    if (text == betweenLabels.first) { starts++; insideSection = true }
                    if (text == betweenLabels.second) { ends++; insideSection = false }
                }
                if (insideSection && node.isVisibleToUser && (text == label || node.contentDescription?.toString() == label)) {
                    matches += AccessibilityNodeInfo.obtain(node)
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
            if (betweenLabels != null) {
                assertTrue("Expected unique section boundaries for '$label'", starts <= 1 && ends <= 1)
                if (starts != 1 || ends != 1) return null
            }
            assertTrue("Expected one visible control labelled '$label', found ${matches.size}", matches.size <= 1)
            // Transfer the sole owned copy only after the complete current tree is checked.
            return matches.singleOrNull()?.also { matches.clear() }
        } finally {
            matches.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun nearestClickableOwned(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cursor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
        try {
            repeat(64) {
                val current = cursor ?: return null
                if (!current.refresh()) return null
                if (current.isClickable) {
                    cursor = null
                    return current
                }
                // getParent returns an independently owned node; release only the old cursor.
                val parent = current.parent
                cursor = parent
                current.recycle()
            }
            fail("Clickable ancestor exceeds bounded accessibility depth")
            return null
        } finally { cursor?.recycle() }
    }

    private fun scrollClickForward() {
        val root = automation.rootInActiveWindow ?: return
        try {
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { if (scroll(child)) return true } finally { child.recycle() }
                } }
                return false
            }
            scroll(root)
        } finally { root.recycle() }
    }

    private fun hasVisibleText(label: String) = snapshot().any { it.visible && it.text == label }

    private data class NodeSummary(val text: String?, val description: String?, val visible: Boolean)

    private fun snapshot(): List<NodeSummary> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<NodeSummary>()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                result += NodeSummary(node.text?.toString(), node.contentDescription?.toString(), node.isVisibleToUser)
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
            return result
        } finally { root.recycle() }
    }

    private fun scrollTo(label: String, backwards: Boolean = false) {
        repeat(20) {
            if (snapshot().any { it.visible && it.text == label }) return
            val root = automation.rootInActiveWindow ?: return@repeat
            try {
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    if (node.isScrollable) {
                        val target = AccessibilityNodeInfo.obtain(node)
                        return try { target.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { target.recycle() }
                    }
                    repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                        try { if (scroll(child)) return true } finally { child.recycle() }
                    } }
                    return false
                }
                scroll(root)
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        fail("Editor action unavailable after bounded scrolling")
    }

    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= end) fail("UI state not observed within 5 seconds"); SystemClock.sleep(20) }
    }

    private fun parseFrozenFixture(xml: String): FrozenNode {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        fun parseNode(): FrozenNode {
            check(parser.eventType == XmlPullParser.START_TAG)
            val text = parser.getAttributeValue(null, "text")?.takeIf { it.isNotEmpty() }
            val editable = parser.getAttributeValue(null, "editable") == "true"
            val password = parser.getAttributeValue(null, "password") == "true"
            val children = mutableListOf<FrozenNode>()
            while (parser.next() != XmlPullParser.END_TAG) if (parser.eventType == XmlPullParser.START_TAG) children += parseNode()
            return FrozenNode(text, editable, password, children)
        }
        while (parser.next() != XmlPullParser.START_TAG) { }
        return parseNode()
    }

    private fun findFixtureEditable(root: FrozenNode, label: String): FixtureEditable? {
        val matches = mutableListOf<FixtureEditable>()
        fun labelsBelow(node: FrozenNode): Set<String> = buildSet {
            node.text?.let(::add)
            node.children.forEach { child -> addAll(labelsBelow(child)) }
        }
        fun visit(node: FrozenNode) {
            if (node.editable && labelsBelow(node).any { labelMatches(it, label) }) matches += FixtureEditable(label, node.password)
            node.children.forEach(::visit)
        }
        visit(root)
        return matches.singleOrNull()
    }
}

private fun noUploadTester() = object : ProfileTester {
    override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
        throw AssertionError("No upload in UI tests")
}

private fun testProfile(id: String, protocol: String = "DEEPSEEK_RESPONSES") = ModelProfile(
    id, 0, "Synthetic $id", "Synthetic", "https://synthetic.example/v1", protocol, "synthetic-model",
    opaqueSecret("draft-placeholder"), 4096, 2048, 1024, 128, 5000,
    setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList()
)

private class RecordingProfileTester : ProfileTester {
    var calls = 0
    var cancellations = 0
    private var listener: ((ModelStreamEvent) -> Unit)? = null

    override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        calls++
        this.listener = listener
        return object : CancellationHandle { override fun cancel() { cancellations++ } }
    }

    fun emit(end: ModelEnd) {
        listener?.invoke(ModelStreamEvent.Terminal("synthetic-test", end, null, null))
    }
}
