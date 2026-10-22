package com.sheen.rokid.storage

import com.sheen.rokid.plugin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProfileRepositoryTest {
    private fun secret(id: String) = object : SecretHandle { override val id = id }
    private fun profile(id: String = "p") = ModelProfile(id, 1, "test", "service", "https://example.test",
        "DEEPSEEK_RESPONSES", "model", secret("dummy"), 4096, 3000, 256, 128, 10_000,
        setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())
    private fun verified(revision: Long) = CapabilityVerification(revision, "TEXT_REQUEST", VerificationState.Verified, 1)

    @Test fun `save assigns revision clears supplied evidence and maintains separate profiles`() {
        val repo: ProfileRepository = MemoryProfileRepository()
        val first = repo.save(profile().copy(verifications = listOf(verified(1))))
        repo.save(profile("other"))
        assertTrue(first.revision > 0)
        assertEquals(emptyList(), first.verifications)
        assertEquals(setOf("p", "other"), repo.list().map { it.id }.toSet())
        assertEquals(first, repo.get("p"))
    }

    @Test fun `all profile edits invalidate evidence and notify pending work`() {
        val repo = MemoryProfileRepository()
        val changes = mutableListOf<String>()
        repo.listenChanges(changes::add)
        var current = repo.save(profile())
        val edits: List<(ModelProfile) -> ModelProfile> = listOf(
            { it.copy(name = "renamed") }, { it.copy(baseUrl = "https://other.test") },
            { it.copy(model = "other") }, { it.copy(protocolId = "other") },
            { it.copy(secret = secret("replacement")) }, { it.copy(inputs = InputModality.entries.toSet()) },
            { it.copy(outputs = OutputModality.entries.toSet()) }, { it.copy(inputBudgetTokens = 2900) })
        for (edit in edits) {
            repo.verify("p", verified(current.revision))
            val next = repo.save(edit(repo.get("p")!!))
            assertTrue(next.revision > current.revision)
            assertEquals(emptyList(), next.verifications)
            current = next
        }
        assertEquals(List(9) { "p" }, changes)
    }

    @Test fun `ordinary edits keep current handle but replacements and deletion revoke`() {
        val revoked = mutableListOf<SecretHandle>()
        val repo = MemoryProfileRepository(revoked::add)
        val first = repo.save(profile())
        val renamed = repo.save(first.copy(name = "renamed"))
        assertSame(first.secret, renamed.secret)
        assertEquals(emptyList(), revoked)
        val replacement = repo.save(renamed.copy(secret = secret("next")))
        assertEquals(listOf(first.secret), revoked)
        repo.delete("p"); repo.delete("p")
        assertEquals(listOf(first.secret, replacement.secret), revoked)
    }

    @Test fun `verification fences revisions without notifying or incrementing`() {
        val repo = MemoryProfileRepository()
        val current = repo.save(profile())
        val changes = mutableListOf<String>()
        repo.listenChanges(changes::add)
        repo.verify("p", verified(current.revision))
        repo.verify("p", verified(current.revision - 1))
        assertEquals(listOf(verified(current.revision)), repo.get("p")!!.verifications)
        assertEquals(current.revision, repo.get("p")!!.revision)
        assertEquals(emptyList(), changes)
        repo.verify("missing", verified(current.revision))
        assertNull(repo.get("missing"))
    }

    @Test fun `delete recreate cannot reuse revision or stale verification`() {
        val repo = MemoryProfileRepository()
        val old = repo.save(profile())
        repo.delete("p")
        val replacement = repo.save(profile())
        repo.verify("p", verified(old.revision))
        assertTrue(replacement.revision > old.revision)
        assertEquals(emptyList(), repo.get("p")!!.verifications)
    }

    @Test fun `input and returned collection aliases cannot mutate stored capability`() {
        val inputs = mutableSetOf(InputModality.TEXT)
        val outputs = mutableSetOf(OutputModality.TEXT)
        val evidence = mutableListOf(verified(1))
        val repo = MemoryProfileRepository()
        val saved = repo.save(profile().copy(inputs = inputs, outputs = outputs, verifications = evidence))
        inputs += InputModality.AUDIO; outputs += OutputModality.AUDIO; evidence.clear()
        fun mutate(snapshot: ModelProfile) {
            runCatching { (snapshot.inputs as MutableSet).clear() }
            runCatching { (snapshot.outputs as MutableSet).clear() }
            runCatching { (snapshot.verifications as MutableList).clear() }
        }
        mutate(saved)
        repo.verify("p", verified(saved.revision))
        mutate(repo.get("p")!!); mutate(repo.list().single())
        assertEquals(setOf(InputModality.TEXT), repo.get("p")!!.inputs)
        assertEquals(setOf(OutputModality.TEXT), repo.get("p")!!.outputs)
        assertEquals(listOf(verified(saved.revision)), repo.get("p")!!.verifications)
    }

    @Test fun `change and secret callbacks allow another thread to reenter repository`() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var repo: MemoryProfileRepository
        fun reenter() { assertNotNull(executor.submit<ModelProfile?> { repo.get("p") }.get(5, TimeUnit.SECONDS)) }
        repo = MemoryProfileRepository { reenter() }
        try {
            val saved = repo.save(profile())
            repo.listenChanges { reenter() }
            repo.save(saved.copy(secret = secret("next")))
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `cancel is idempotent and fences captured later delivery`() {
        val repo = MemoryProfileRepository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        repo.listenChanges { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val changes = mutableListOf<String>()
        val handle = repo.listenChanges(changes::add)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val save = executor.submit { repo.save(profile()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            handle.cancel(); handle.cancel()
            release.countDown(); save.get(5, TimeUnit.SECONDS)
            repo.save(profile())
            assertEquals(emptyList(), changes)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `callback can wait for cancellation from another thread`() {
        val repo = MemoryProfileRepository()
        val executor = Executors.newSingleThreadExecutor()
        lateinit var handle: CancellationHandle
        var delivered = 0
        handle = repo.listenChanges {
            delivered++
            executor.submit { handle.cancel(); repo.get("p") }.get(2, TimeUnit.SECONDS)
        }
        try {
            repo.save(profile())
            repo.save(profile())
            assertEquals(1, delivered)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `throwing listener cannot suppress pending cancellation listeners`() {
        val repo = MemoryProfileRepository()
        repo.listenChanges { throw IllegalStateException("synthetic failure") }
        val changes = mutableListOf<String>()
        repo.listenChanges(changes::add)
        assertFailsWith<IllegalStateException> { repo.save(profile()) }
        assertEquals(listOf("p"), changes)
        assertNotNull(repo.get("p"))
    }

    @Test fun `save delivers all listeners when two throw the same exception`() {
        val repo = MemoryProfileRepository()
        val sharedFailure = IllegalStateException("shared synthetic failure")
        val distinctFailure = IllegalStateException("distinct synthetic failure")
        repo.listenChanges { throw sharedFailure }
        repo.listenChanges { throw sharedFailure }
        repo.listenChanges { throw distinctFailure }
        val changes = mutableListOf<String>()
        repo.listenChanges(changes::add)
        val failure = runCatching { repo.save(profile()) }.exceptionOrNull()
        assertEquals(listOf("p"), changes)
        assertSame(sharedFailure, failure)
        assertContentEquals(arrayOf(distinctFailure), sharedFailure.suppressed)
        assertNotNull(repo.get("p"))
    }

    @Test fun `delete delivers all listeners when two throw the same exception`() {
        val repo = MemoryProfileRepository()
        repo.save(profile())
        val sharedFailure = IllegalStateException("shared synthetic failure")
        repo.listenChanges { throw sharedFailure }
        repo.listenChanges { throw sharedFailure }
        val changes = mutableListOf<String>()
        repo.listenChanges(changes::add)
        val failure = runCatching { repo.delete("p") }.exceptionOrNull()
        assertEquals(listOf("p"), changes)
        assertSame(sharedFailure, failure)
        assertTrue(sharedFailure.suppressed.isEmpty())
        assertNull(repo.get("p"))
    }
}
