package com.sheen.rokid.phone.model

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 0.4 Task 3 fix round 2: free-space readings for a target directory a first run has not created yet.
 *
 * `File.usableSpace` asks the filesystem about the path itself and answers 0 when it is absent, which is exactly the
 * production EmbeddingGemma directory (`<externalFilesDir>/models/embeddinggemma`) before the provisioner creates it.
 * The provisioner must therefore probe the volume through the nearest existing ancestor instead.
 */
class ModelStorageSpaceTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun anExistingDirectoryIsReadFromItself() {
        val directory = temporary.newFolder("existing")
        assertTrue("the host volume must have some free space", directory.usableSpace > 0L)

        assertEquals(directory.usableSpace, ModelStorageSpace.usableBytes(directory))
    }

    @Test
    fun aMissingNestedTargetIsReadFromTheNearestExistingAncestor() {
        val root = temporary.newFolder("root")
        val target = File(File(root, "models"), "embeddinggemma")
        assertFalse("the target must not exist before the probe", target.exists())
        assertFalse(File(root, "models").exists())
        // The raw call is what production used to pass, and why a fresh install was refused for ever.
        assertEquals("an absent target reads as zero bytes", 0L, target.usableSpace)

        val ancestorReading = root.usableSpace
        assertTrue("the ancestor must have free space for this probe to mean anything", ancestorReading > 0L)
        assertEquals(
            "a missing target must report its ancestor's volume, not zero",
            ancestorReading,
            ModelStorageSpace.usableBytes(target),
        )
    }

    @Test
    fun aTargetWithNoExistingAncestorReadsZero() {
        // A relative path whose first segment does not exist has no existing ancestor to ask; the caller can only
        // refuse rather than guess a volume.
        val target = File("missing-${UUID.randomUUID()}", "models/embeddinggemma")
        assertFalse(target.exists())

        assertEquals(0L, ModelStorageSpace.usableBytes(target))
    }
}
