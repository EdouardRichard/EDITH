package com.sheen.rokid.phone.storage

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle

/**
 * The JVM test fixture for the encrypted blob layer.
 *
 * 0.52 Task 7: moved out of the retired profile-editor test so the surviving storage, catalog, settings and host
 * suites keep one in-memory [EncryptedBlobStore]. It is test-only: no production source may reference it.
 */
internal class TestBlobs : EncryptedBlobStore, ProfileBlobRecovery {
    val values = linkedMapOf<String, ByteArray>()
    var failId: String? = null
    var failReadId: String? = null

    override fun read(id: String): ByteArray? {
        if (id == failReadId) throw java.io.IOException("synthetic read failure")
        return values[id]?.copyOf()
    }

    override fun write(id: String, value: ByteArray) {
        if (id == failId) throw java.io.IOException("synthetic disk failure")
        values[id] = value.copyOf()
    }

    override fun delete(id: String) {
        values.remove(id)?.fill(0)
    }

    override fun pruneProfilePayloads(keep: Set<String>) {
        values.keys.filter { (it.startsWith("credential-") || it.startsWith("profile-")) && it !in keep }.toList().forEach(::delete)
    }
}

/** A contract-valid placeholder profile for the tests that only need an id/revision/inputs shape. */
internal fun profile(id: String) = ModelProfile(
    id, 0, "Synthetic $id", "Synthetic", "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
    object : SecretHandle { override val id = "draft-placeholder" }, 4096, 2048, 1024, 128, 5000,
    setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList(),
)
