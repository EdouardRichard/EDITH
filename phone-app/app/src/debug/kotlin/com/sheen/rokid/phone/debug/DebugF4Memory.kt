package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.memory.normalize
import java.io.File
import java.security.MessageDigest

/**
 * Debug-only F4 memory backup → seed → restore driver (Phase 7 design §3.3).
 *
 * The F4 experiment needs one pre-registered memory (`FACT / 播放周杰伦的稻香`) in the **production** store, and it must
 * put the owner's real document back afterwards byte for byte. This receiver is the only way in:
 *
 * ```
 * adb -s <phone> shell am broadcast -a edith.DEBUG_MEMORY_SEED -n com.sheen.rokid.phone/.DebugF4Memory --es mode backup
 * adb -s <phone> shell am broadcast -a edith.DEBUG_MEMORY_SEED -n com.sheen.rokid.phone/.DebugF4Memory --es mode seed
 * adb -s <phone> shell am broadcast -a edith.DEBUG_MEMORY_SEED -n com.sheen.rokid.phone/.DebugF4Memory --es mode restore
 * ```
 *
 * The order matters and is enforced rather than merely documented: **seed refuses when no backup file exists**. It also
 * refuses before the destructive delete when that file cannot be decoded, because a seed with no usable way back would
 * destroy the very document the experiment promised to restore. `restore` reads the wrapper, writes the exact original
 * bytes back (or deletes the key when the backup recorded "no document"), then reads the key again and reports success
 * only if the bytes match.
 *
 * Only counts, ids and SHA-256 values reach the log - never the memory text. The receiver exists **only in the debug
 * source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast` can reach it; a release build
 * contains no receiver. [HostCompositionRoot.memoryStoreForProbe] remains in `src/main` as an `internal` accessor, but
 * it has no release caller - only this debug receiver uses it.
 */
class DebugF4Memory : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED) return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_BACKUP -> backup(context, storeFor(context))
            MODE_SEED -> seed(context, storeFor(context))
            MODE_RESTORE -> restore(context, storeFor(context))
            else -> refuse(MODE_REFUSED, "UNKNOWN_MODE")
        }
    }

    /**
     * The exact namespace handle the plugin's [MemoryLog] writes through.
     *
     * The composition accessor is `internal` and debug-only; a build with no storage wiring (or a failed composition
     * build) answers `null`, which every verb reports as `NO_STORE` rather than pretending it backed something up.
     */
    private fun storeFor(context: Context): StorageHandle? =
        runCatching { (context.applicationContext as? EdithApplication)?.composition?.memoryStoreForProbe() }.getOrNull()

    private fun backup(context: Context, store: StorageHandle?) {
        if (store == null) return refuse(MODE_BACKUP, "NO_STORE")
        val raw = try {
            store.read(MemoryLog.KEY)
        } catch (e: Exception) {
            return refuse(MODE_BACKUP, "READ_FAILED")
        }
        val file = backupFile(context) ?: return refuse(MODE_BACKUP, "NO_EXTERNAL_DIR")
        val encoded = F4MemoryBackup.encode(raw)
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(encoded)
        } catch (e: Exception) {
            return refuse(MODE_BACKUP, "WRITE_FAILED")
        }
        P3Log.line(
            "P3_F4MEM mode=backup present=${raw != null} bytes=${raw?.size ?: 0} " +
                "sha=${F4MemoryBackup.sha256(raw ?: ByteArray(0))}",
        )
    }

    private fun seed(context: Context, store: StorageHandle?) {
        if (store == null) return refuse(MODE_SEED, "NO_STORE")
        val file = backupFile(context)
        if (file == null || !file.isFile) return refuse(MODE_SEED, "NO_BACKUP")
        // Decode before deleting: an unreadable backup is not a way back, so seeding stops while the real document is
        // still in place instead of clearing it and discovering too late that the original cannot be restored.
        try {
            F4MemoryBackup.decode(file.readBytes())
        } catch (e: Exception) {
            return refuse(MODE_SEED, "BACKUP_INVALID")
        }
        try {
            store.delete(MemoryLog.KEY)
        } catch (e: Exception) {
            return refuse(MODE_SEED, "DELETE_FAILED")
        }
        val log = MemoryLog(store, normalizer = ::normalize)
        val entry = try {
            log.record(MemoryKind.FACT, SEED_TEXT, MemorySource.EXPLICIT)
        } catch (e: Exception) {
            return refuse(MODE_SEED, "WRITE_FAILED")
        }
        if (entry == null) return refuse(MODE_SEED, "WRITE_FAILED")
        val entries = try {
            log.entries().size
        } catch (e: Exception) {
            -1
        }
        P3Log.line("P3_F4MEM mode=seed id=${entry.id} entries=$entries")
    }

    private fun restore(context: Context, store: StorageHandle?) {
        if (store == null) return refuse(MODE_RESTORE, "NO_STORE")
        val file = backupFile(context)
        if (file == null || !file.isFile) return refuse(MODE_RESTORE, "NO_BACKUP")
        val encoded = try {
            file.readBytes()
        } catch (e: Exception) {
            return refuse(MODE_RESTORE, "READ_FAILED")
        }
        val raw = try {
            F4MemoryBackup.decode(encoded)
        } catch (e: Exception) {
            return refuse(MODE_RESTORE, "BACKUP_INVALID")
        }
        try {
            if (raw == null) store.delete(MemoryLog.KEY) else store.write(MemoryLog.KEY, raw)
        } catch (e: Exception) {
            return refuse(MODE_RESTORE, if (raw == null) "DELETE_FAILED" else "WRITE_FAILED")
        }
        // §3.7 requires the restore to be **verified**, not merely attempted: re-read the key and compare the exact
        // bytes. A mismatch is reported as a refusal, so the campaign cannot record a successful restore that did not
        // happen.
        val after = try {
            store.read(MemoryLog.KEY)
        } catch (e: Exception) {
            return refuse(MODE_RESTORE, "VERIFY_READ_FAILED")
        }
        val verified = if (raw == null) after == null else after != null && after.contentEquals(raw)
        if (!verified) return refuse(MODE_RESTORE, "VERIFY_FAILED")
        val entries = try {
            MemoryLog(store).entries().size
        } catch (e: Exception) {
            -1
        }
        P3Log.line(
            "P3_F4MEM mode=restore bytes=${raw?.size ?: 0} " +
                "sha=${F4MemoryBackup.sha256(raw ?: ByteArray(0))} entries=$entries",
        )
    }

    private fun backupFile(context: Context): File? =
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, BACKUP_FILE) }

    private fun refuse(mode: String, reason: String) {
        P3Log.line("P3_F4MEM mode=$mode refused reason=$reason")
    }

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION_SEED = "edith.DEBUG_MEMORY_SEED"

        /** The verb selector: `backup`, `seed` or `restore`. */
        const val EXTRA_MODE = "mode"

        const val MODE_BACKUP = "backup"
        const val MODE_SEED = "seed"
        const val MODE_RESTORE = "restore"

        /** Logged when the `mode` extra is absent or unknown; never treated as one of the three verbs. */
        private const val MODE_REFUSED = "REFUSED"

        /** Design §1 R4: the pre-registered command sentence. It is the only text this receiver ever writes. */
        const val SEED_TEXT = "播放周杰伦的稻香"

        /** Where the original document's wrapper lives, under the app's external files directory. */
        const val BACKUP_FILE = "f4-memory-backup.bin"
    }
}

/**
 * The backup file's format: one marker byte, then the document's bytes exactly as the store returned them.
 *
 * The marker is what separates **"there was no document"** (`MemoryLog.KEY` was absent, `read` answered `null`) from
 * **"there was a document and it was empty"** (a zero-length value). Restoring the first must delete the key; restoring
 * the second must write an empty value. A raw-bytes file could not tell those apart, and guessing would either invent a
 * document the owner never had or silently drop an empty one.
 *
 * [decode] **refuses** a malformed wrapper instead of reading it as absent: "corrupt" treated as "no document" would
 * make `restore` delete the owner's memory. The receiver turns that refusal into `refused reason=BACKUP_INVALID` and
 * writes nothing.
 */
object F4MemoryBackup {

    private const val MARKER_ABSENT: Byte = 0
    private const val MARKER_PRESENT: Byte = 1

    /** Wraps [bytes], or the absent marker when there was no document. */
    fun encode(bytes: ByteArray?): ByteArray {
        if (bytes == null) return byteArrayOf(MARKER_ABSENT)
        return ByteArray(bytes.size + 1).also { encoded ->
            encoded[0] = MARKER_PRESENT
            bytes.copyInto(encoded, destinationOffset = 1)
        }
    }

    /**
     * Unwraps an encoded document: `null` for the absent marker, otherwise the exact original bytes (which may be
     * empty).
     *
     * @throws IllegalArgumentException when the wrapper is empty, has an unknown marker, or claims absence while
     * carrying payload - all cases where continuing could destroy the real document.
     */
    fun decode(encoded: ByteArray): ByteArray? {
        require(encoded.isNotEmpty()) { "F4_BACKUP_EMPTY" }
        return when (encoded[0]) {
            MARKER_ABSENT -> {
                require(encoded.size == 1) { "F4_BACKUP_ABSENT_MARKER_HAS_PAYLOAD" }
                null
            }
            MARKER_PRESENT -> encoded.copyOfRange(1, encoded.size)
            else -> throw IllegalArgumentException("F4_BACKUP_UNKNOWN_MARKER")
        }
    }

    /** Lower-case hex SHA-256 of [bytes]; the only form in which document content reaches the log. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
