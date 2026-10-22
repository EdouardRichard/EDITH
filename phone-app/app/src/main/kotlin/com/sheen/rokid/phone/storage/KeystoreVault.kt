package com.sheen.rokid.phone.storage
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.SecretResolver
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.UUID
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface EncryptedBlobStore {
    fun read(id: String): ByteArray?
    fun write(id: String, value: ByteArray)
    fun delete(id: String)
}

internal interface ProfileBlobRecovery {
    fun pruneProfilePayloads(keep: Set<String>)
}

class KeystoreVault(private val blobs: EncryptedBlobStore) : SecretResolver {
    constructor(context: Context) : this(AndroidEncryptedBlobStore(context))
    private val lock = Any()
    private val revoked = mutableSetOf<String>()
    @Volatile private var authority: (String) -> Boolean = { false }
    private fun owners(): Map<String, String> = blobs.read("vault-manifest")?.let { bytes ->
        try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(json["version"]!!.jsonPrimitive.int == 1)
            json["owners"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) { unavailable() } finally { bytes.fill(0) }
    } ?: emptyMap()
    private fun publish(owners: Map<String, String>) {
        val bytes = buildJsonObject { put("version", 1); put("owners", buildJsonObject { owners.forEach { (id, ref) -> put(id, ref) } }) }
            .toString().toByteArray(Charsets.UTF_8)
        try { blobs.write("vault-manifest", bytes) } catch (_: Exception) { unavailable() } finally { bytes.fill(0) }
    }
    fun putSecret(id: String, value: CharArray): SecretHandle {
        try {
            require(id.isNotBlank())
            synchronized(lock) {
                val previous = owners(); val staged = stage(value)
                try { publish(previous + (id to staged.id)) } catch (error: Exception) { blobs.delete(staged.id); throw error }
                previous[id]?.let { blobs.delete(it) }
                return staged
            }
        } finally { value.fill('\u0000') }
    }
    fun deleteSecret(id: String) = synchronized(lock) {
        val previous = owners(); val handle = previous[id] ?: return@synchronized
        revoked += handle
        try { publish(previous - id) } finally { blobs.delete(handle) }
    }
    internal fun stage(value: CharArray): SecretHandle {
        var bytes: ByteArray? = null
        var handle: SecretHandle? = null
        try {
            require(value.isNotEmpty() && value.any { !it.isWhitespace() } && value.size <= 8192)
            val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(value))
            bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill(0)
            handle = opaqueSecret("credential-${UUID.randomUUID()}")
            blobs.write(handle.id, bytes)
            return handle
        } catch (_: Exception) { handle?.let { runCatching { blobs.delete(it.id) } }; unavailable() }
        finally { bytes?.fill(0); value.fill('\u0000') }
    }
    internal fun authorize(authority: (String) -> Boolean) { synchronized(lock) { this.authority = authority } }
    internal fun directHandles(): Set<String> = synchronized(lock) { owners().values.toSet() }
    override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) {
        if (!credentialId(handle.id)) unavailable()
        // Repository admission precedes the vault lock, preserving the repository -> vault lock order.
        val referenced = authority(handle.id)
        val bytes = synchronized(lock) {
            if (handle.id in revoked) unavailable()
            if (!referenced && handle.id !in owners().values) unavailable()
            blobs.read(handle.id) ?: unavailable()
        }
        val chars = try {
            val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            CharArray(decoded.remaining()).also { decoded.get(it); if (decoded.hasArray()) decoded.array().fill('\u0000') }
        } catch (_: Exception) { unavailable() } finally { bytes.fill(0) }
        try { block(chars) } finally { chars.fill('\u0000') }
    }
    override fun revoke(handle: SecretHandle) = synchronized(lock) {
        if (!credentialId(handle.id)) return@synchronized
        revoked += handle.id
        try {
            val previous = owners()
            val next = previous.filterValues { it != handle.id }
            if (next != previous) publish(next)
        } finally { blobs.delete(handle.id) }
    }
}

internal fun opaqueSecret(value: String): SecretHandle = object : SecretHandle {
    override val id = value
    override fun toString() = "SecretHandle(redacted)"
}
internal fun unavailable(): Nothing = throw ContractError.ConfigurationRejected("Storage unavailable; reconfigure")
internal fun credentialId(id: String) = id.matches(Regex("credential-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

internal interface BlobKeys {
    val providerName: String
    fun contains(name: String): Boolean
    fun get(name: String): SecretKey?
    fun create(name: String): SecretKey
    fun delete(name: String)
    fun aliases(): List<String>
}

internal class AndroidBlobKeys : BlobKeys {
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    override val providerName: String get() = store.provider.name
    override fun contains(name: String) = store.containsAlias(name)
    override fun get(name: String) = store.getKey(name, null) as? SecretKey
    override fun create(name: String): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
    override fun delete(name: String) = store.deleteEntry(name)
    override fun aliases() = store.aliases().toList()
}

internal interface BlobFile {
    val baseFile: File
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
    fun delete()
}

internal class AndroidBlobFile(override val baseFile: File) : BlobFile {
    private val atomic = AtomicFile(baseFile)
    override fun openRead() = atomic.openRead()
    override fun startWrite() = atomic.startWrite()
    override fun finishWrite(stream: FileOutputStream) = atomic.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomic.failWrite(stream)
    override fun delete() = atomic.delete()
}

class AndroidEncryptedBlobStore internal constructor(
    directory: () -> File,
    private val store: BlobKeys,
    private val files: (File) -> BlobFile = ::AndroidBlobFile,
) : EncryptedBlobStore, ProfileBlobRecovery {
    constructor(context: Context) : this({ File(context.noBackupFilesDir, "edith-vault-v1") }, AndroidBlobKeys())
    private val directory by lazy { directory().apply { if (!isDirectory && !mkdirs()) unavailable() } }
    private val cipher = BlobCipher()
    val providerName: String get() = store.providerName
    private fun token(id: String): String {
        require(id.isNotBlank() && id.length <= 1024)
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun fileName(id: String) = when {
        id.startsWith("credential-") -> "credential-"
        id.startsWith("profile-") -> "profile-"
        else -> ""
    } + token(id)
    private fun file(id: String) = files(File(directory, fileName(id)))
    private fun aliasPrefix() = "edith.v1.${token(directory.absolutePath)}."
    private fun alias(id: String) = aliasPrefix() + fileName(id)
    private fun key(id: String, create: Boolean): SecretKey {
        val name = alias(id)
        store.get(name)?.let { return it }
        if (!create) unavailable()
        return store.create(name)
    }
    @Synchronized override fun read(id: String): ByteArray? = safely {
        val file = file(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            if (store.contains(alias(id))) unavailable()
            return@safely null
        }
        file.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= BlobCipher.MAX_BYTES + 64); output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            cipher.decrypt(id, bytes, key(id, false))
        }
    }
    @Synchronized override fun write(id: String, value: ByteArray) = safely {
        val target = file(id)
        // Existing ciphertext with a missing key must never be silently overwritten with a new alias.
        val existed = target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()
        val hadAlias = store.contains(alias(id))
        var stream: FileOutputStream? = null
        try {
            val encrypted = cipher.encrypt(id, value, key(id, !existed))
            stream = target.startWrite()
            stream.write(encrypted)
            target.finishWrite(stream)
        } catch (error: Exception) {
            try { stream?.let { target.failWrite(it) } }
            finally {
                // Creation can install an alias before throwing; include encryption and startWrite failures.
                if (!existed && !hadAlias) try { target.delete() } finally { store.delete(alias(id)) }
            }
            throw error
        }
    }
    @Synchronized override fun delete(id: String) = safely { file(id).delete(); store.delete(alias(id)) }
    @Synchronized override fun pruneProfilePayloads(keep: Set<String>) = safely {
        val retained = keep.map(::fileName).toSet()
        val owned = Regex("(?:profile|credential)-[0-9a-f]{64}")
        directory.listFiles()?.forEach { entry ->
            val name = entry.name.removeSuffix(".bak").removeSuffix(".new")
            if (owned.matches(name) && name !in retained) files(File(directory, name)).delete()
        }
        val prefix = aliasPrefix(); val aliases = store.aliases().toList()
        aliases.forEach { name ->
            val suffix = name.removePrefix(prefix)
            if (name.startsWith(prefix) && owned.matches(suffix) && suffix !in retained) store.delete(name)
        }
    }
}

internal class BlobCipher {
    companion object { const val MAX_BYTES = 1024 * 1024 }
    fun encrypt(id: String, plain: ByteArray, key: SecretKey): ByteArray = safely {
        require(plain.size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD("edith:v1:$id".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain); val iv = cipher.iv; require(iv.size == 12)
        ByteBuffer.allocate(1 + 1 + 4 + iv.size + encrypted.size).put(1).put(iv.size.toByte()).putInt(encrypted.size).put(iv).put(encrypted).array()
    }
    fun decrypt(id: String, encoded: ByteArray, key: SecretKey): ByteArray = safely {
        require(encoded.size in 34..(MAX_BYTES + 64))
        val data = ByteBuffer.wrap(encoded); require(data.get().toInt() == 1)
        val ivSize = data.get().toInt(); val size = data.int
        require(ivSize == 12 && size in 16..(MAX_BYTES + 16) && data.remaining() == ivSize + size)
        val iv = ByteArray(ivSize).also { data.get(it) }; val encrypted = ByteArray(size).also { data.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD("edith:v1:$id".toByteArray(Charsets.UTF_8)); cipher.doFinal(encrypted)
    }
}
