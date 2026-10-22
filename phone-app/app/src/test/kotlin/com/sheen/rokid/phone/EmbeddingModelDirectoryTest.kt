package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.session.SessionViewModelTest
import com.sheen.rokid.phone.app.EmbeddingModelDirectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * edith-0.2 Phase B (C4): the one place that decides where the EmbeddingGemma artifacts are read from.
 *
 * Why this is pinned by a test at all: the value it produces **is** the plugin's hard-dependency verdict. The
 * directory that used to be here (`filesDir/engine-models`) does not exist on any device and nothing ever provisions
 * it, so `EmbeddingServiceImpl.isAvailable` was false and `serviceRefusal()` refused every single turn. The artifacts
 * are provisioned to the **external** files directory (Phase A pushed them there with adb:
 * `docs/project/evidence/2026-10-13-phase-a-performance-k40.md`), and the difference between the two is exactly
 * "the assistant serves" versus "the assistant refuses", which is worth a test rather than a comment.
 */
class EmbeddingModelDirectoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the artifacts are read from the external files directory`() {
        val external = temporary.newFolder("external")
        val internal = temporary.newFolder("internal")

        val located = EmbeddingModelDirectory.locate(external, internal)

        assertEquals(File(File(external, "models"), "embeddinggemma"), located)
        assertEquals("embeddinggemma", located.name)
    }

    @Test
    fun `an unmounted external files directory resolves somewhere that cannot exist`() {
        // `getExternalFilesDir(null)` is null when external storage is not mounted. The composition root runs at
        // process start whether or not the phone's storage is ready, so this must produce a path and not a crash —
        // and the path must be one that cannot exist, so the hard dependency refuses the turn instead of reading
        // some other directory that happens to be there. The exact path is asserted, not just `exists()`: an
        // implementation that fell back to `internalFilesDir` itself would also "not exist" in this fixture.
        val internal = temporary.newFolder("internal")

        val located = EmbeddingModelDirectory.locate(null, internal)

        assertEquals(
            File(File(internal, "no-external-files-dir"), EmbeddingModelDirectory.RELATIVE_PATH),
            located,
        )
        assertFalse("must not resolve to a directory that exists", located.exists())
    }

    @Test
    fun `a context with neither directory still resolves instead of throwing`() {
        // Not hypothetical, and it was found the expensive way: `SessionViewModelTest` builds the production
        // composition root over a `ContextWrapper(null)`, where both `getExternalFilesDir` and `getFilesDir` read as
        // null. Routing that through a non-null parameter threw
        // `NullPointerException: getFilesDir(...) must not be null` out of `HostCompositionRoot.create`, so the app
        // could not assemble at all — a device would have crashed on start rather than degrading to a refusal.
        val located = EmbeddingModelDirectory.locate(null, null)

        assertTrue(
            "the unreachable prefix must still be in the path: ${located.path}",
            located.path.replace('\\', '/').endsWith("no-external-files-dir/models/embeddinggemma"),
        )
        assertFalse("must not resolve to a directory that exists", located.exists())
    }
}
