package com.sheen.rokid.phone.artifacts

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.25 Task 13 / R67 + R72: the file-backed artifact store.
 *
 * It lives in the App cache (never the repository), keeps metadata JSON free of image bytes, retains the newest K
 * artifacts even when the count/byte cap would otherwise drop them, and returns null for a missing artifact so the
 * encoders can render a visible placeholder.
 */
class ArtifactRetentionTest {

    private lateinit var root: File
    private val clock = AtomicLong(1_000L)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("edith-artifacts-test-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(
        maxCount: Int = 32,
        maxBytes: Long = 64L * 1024L * 1024L,
        retainRecent: Int = 3,
    ): FileArtifactStore = FileArtifactStore(
        root = root,
        maxCount = maxCount,
        maxBytes = maxBytes,
        retainRecent = retainRecent,
        clock = { clock.incrementAndGet() },
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `put then get returns the bytes and the real metadata`() {
        val store = store()
        val bytes = "image-bytes".toByteArray(Charsets.UTF_8)

        val ref = store.put(bytes, "image/png", width = 120, height = 240)

        assertTrue(ref.artifactId, ref.artifactId.startsWith("img-"))
        assertEquals("image/png", ref.mime)
        assertEquals(120, ref.width)
        assertEquals(240, ref.height)
        assertEquals(sha256(bytes), ref.sha256)

        val stored = requireNotNull(store.get(ref.artifactId))
        assertArrayEquals(bytes, stored.bytes)
        assertEquals("image/png", stored.mime)
        assertEquals(120, stored.width)
        assertEquals(240, stored.height)
        assertEquals(sha256(bytes), stored.sha256)
    }

    @Test
    fun `a missing artifact resolves to null instead of throwing`() {
        assertNull(store().get("img-does-not-exist"))
    }

    @Test
    fun `the store persists across instances rooted at the same app cache directory`() {
        val bytes = byteArrayOf(9, 8, 7)
        val ref = store().put(bytes, "image/jpeg", 10, 20)

        val reopened = store()

        assertArrayEquals(bytes, requireNotNull(reopened.get(ref.artifactId)).bytes)
    }

    @Test
    fun `when the count cap is exceeded the oldest artifact is evicted but the newest K survive`() {
        val store = store(maxCount = 5, retainRecent = 3)
        val refs = (1..8).map { store.put(byteArrayOf(it.toByte()), "image/png", 1, 1) }

        assertNull("the oldest artifact is gone", store.get(refs[0].artifactId))
        assertNull(store.get(refs[1].artifactId))
        assertNull(store.get(refs[2].artifactId))
        refs.takeLast(3).forEach { ref ->
            assertNotNull("the newest K references must survive", store.get(ref.artifactId))
        }
        assertEquals("the count cap is enforced", 5, filesWithSuffix(".bin"))
    }

    @Test
    fun `when the byte cap is exceeded the newest K are still retained`() {
        val store = store(maxCount = 100, maxBytes = 12L, retainRecent = 3)
        val refs = (1..6).map { store.put(byteArrayOf(1, 2, 3, 4), "image/png", 1, 1) }

        // 6 × 4 B = 24 B > 12 B: the oldest three leave, the newest three (the K floor) stay.
        assertEquals(3, filesWithSuffix(".bin"))
        refs.takeLast(3).forEach { assertNotNull(store.get(it.artifactId)) }
        refs.take(3).forEach { assertNull(store.get(it.artifactId)) }
    }

    @Test
    fun `the K floor wins even when the byte cap is impossible`() {
        val store = store(maxCount = 100, maxBytes = 1L, retainRecent = 3)
        val refs = (1..4).map { store.put(byteArrayOf(1, 2, 3, 4), "image/png", 1, 1) }

        assertEquals("never evict below the newest K", 3, filesWithSuffix(".bin"))
        refs.takeLast(3).forEach { assertNotNull(store.get(it.artifactId)) }
    }

    @Test
    fun `evictBefore keeps only the newest references`() {
        val store = store(maxCount = 10, retainRecent = 3)
        val refs = (1..5).map { store.put(byteArrayOf(it.toByte()), "image/png", 1, 1) }

        store.evictBefore(3)

        refs.take(2).forEach { assertNull(store.get(it.artifactId)) }
        refs.takeLast(3).forEach { assertNotNull(store.get(it.artifactId)) }
        assertEquals(3, filesWithSuffix(".bin"))
    }

    @Test
    fun `a read makes an artifact recent enough to survive the next put`() {
        val store = store(maxCount = 4, retainRecent = 3)
        val a = store.put(byteArrayOf(1), "image/png", 1, 1)
        val b = store.put(byteArrayOf(2), "image/png", 1, 1)
        val c = store.put(byteArrayOf(3), "image/png", 1, 1)
        val d = store.put(byteArrayOf(4), "image/png", 1, 1)

        // Reading `a` makes it the most recently used; the least recently used entry is now `b`.
        assertNotNull(store.get(a.artifactId))
        val e = store.put(byteArrayOf(5), "image/png", 1, 1)

        assertNull(store.get(b.artifactId))
        listOf(a, c, d, e).forEach { assertNotNull(store.get(it.artifactId)) }
    }

    @Test
    fun `metadata JSON carries ids and hashes, never the image bytes`() {
        val store = store()
        val plain = "SECRET-IMAGE-BYTES-MARKER".toByteArray(Charsets.UTF_8)

        store.put(plain, "image/png", 1, 1)

        val metadata = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        assertTrue("a metadata file is expected: ${root.listFiles()?.map { it.name }}", metadata.isNotEmpty())
        metadata.forEach { file ->
            val text = file.readText()
            assertFalse("the store metadata must not carry image bytes: $file", text.contains("SECRET-IMAGE-BYTES-MARKER"))
            assertTrue("metadata names the artifact", text.contains("img-"))
        }
    }

    @Test
    fun `nothing is written outside the supplied cache root`() {
        val store = store()
        store.put(byteArrayOf(1), "image/png", 1, 1)

        val canonicalRoot = root.canonicalFile
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            assertTrue(
                "artifact files stay inside the app cache root: ${file.canonicalPath}",
                file.canonicalPath.startsWith(canonicalRoot.path),
            )
        }
    }

    private fun filesWithSuffix(suffix: String): Int =
        root.listFiles().orEmpty().count { it.isFile && it.name.endsWith(suffix) }
}
