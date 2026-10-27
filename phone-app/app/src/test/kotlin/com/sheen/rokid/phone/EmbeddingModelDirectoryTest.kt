package com.sheen.rokid.phone
import com.sheen.rokid.phone.app.EmbeddingModelDirectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * edith-0.2 Phase B (C4) / 0.4 Task 3: the one place that decides where the EmbeddingGemma artifacts are read from.
 *
 * Why this is pinned by a test at all: the value it produces **is** the plugin's hard-dependency verdict. The
 * directory that used to be here (`filesDir/engine-models`) does not exist on any device and nothing ever provisions
 * it, so `EmbeddingServiceImpl.isAvailable` was false and `serviceRefusal()` refused every single turn. 0.4 now
 * provisions the artifacts itself (spec §6.3): the **external** files directory stays primary, and an internal
 * `filesDir/models/embeddinggemma` fallback is the reachable target when external storage is not mounted. The
 * provisioner and the readers must agree on this one decision.
 */
class EmbeddingModelDirectoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the artifacts are read from the external files directory when it is available`() {
        val external = temporary.newFolder("external")
        val internal = temporary.newFolder("internal")

        val located = EmbeddingModelDirectory.locate(external, internal)

        assertEquals(File(File(external, "models"), "embeddinggemma"), located)
        assertEquals("embeddinggemma", located.name)
    }

    @Test
    fun `an unmounted external files directory falls back to reachable internal storage`() {
        // `getExternalFilesDir(null)` is null when external storage is not mounted. 0.4 must still be able to
        // materialize and read the bundled artifacts, so the fallback is a real internal directory (spec §6.3:
        // "外部目录为主、增加可到达的内部回退"), not the old path that could never exist.
        val internal = temporary.newFolder("internal")

        val located = EmbeddingModelDirectory.locate(null, internal)

        assertEquals(File(File(internal, "models"), "embeddinggemma"), located)
        assertEquals("embeddinggemma", located.name)
    }

    @Test
    fun `a context with neither directory still resolves instead of throwing`() {
        // Not hypothetical, and it was found the expensive way: `SessionViewModelTest` builds the production
        // composition root over a `ContextWrapper(null)`, where both `getExternalFilesDir` and `getFilesDir` read as
        // null. Routing that through a non-null parameter threw
        // `NullPointerException: getFilesDir(...) must not be null` out of `HostCompositionRoot.create`, so the app
        // could not assemble at all - a device would have crashed on start rather than degrading to a refusal.
        val located = EmbeddingModelDirectory.locate(null, null)

        assertTrue(
            "the unreachable prefix must still be in the path: ${located.path}",
            located.path.replace('\\', '/').endsWith("no-external-files-dir/models/embeddinggemma"),
        )
        assertFalse("must not resolve to a directory that exists", located.exists())
    }
}
