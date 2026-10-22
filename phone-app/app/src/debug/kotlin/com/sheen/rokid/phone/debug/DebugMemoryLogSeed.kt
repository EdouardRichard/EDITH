package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.memory.normalize
import com.sheen.rokid.plugin.StorageHandle
import java.io.File

/**
 * Debug-only synthetic-memory driver for the 0.23 P2 evidence (U-4).
 *
 * The memory page has to be photographed with sentences whose lengths are known: one below the 40-code-point preview
 * cap and one above it. The owner's real document is somebody's actual memories, so it must never be the thing in the
 * screenshot. This receiver therefore replaces the document for the duration of the evidence run and puts the original
 * bytes back afterwards:
 *
 * ```
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_MEMORY_LOG_SEED \
 *     -n com.sheen.rokid.phone/.debug.DebugMemoryLogSeed --es mode backup
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_MEMORY_LOG_SEED \
 *     -n com.sheen.rokid.phone/.debug.DebugMemoryLogSeed --es mode seed
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_MEMORY_LOG_SEED \
 *     -n com.sheen.rokid.phone/.debug.DebugMemoryLogSeed --es mode restore
 * ```
 *
 * The order is enforced rather than documented: **seed refuses when no backup file exists**, and it refuses before the
 * destructive delete when that file cannot be decoded, because a seed with no usable way back would destroy the very
 * document this instrument promises to restore. **A second `backup` refuses when a backup file already exists** (pass
 * `--ez force true` only when replacing it on purpose), and a verified `restore` removes the spent file. `restore` reads
 * the wrapper, writes the exact original bytes back (or deletes the key when the backup recorded "no document"), then
 * reads the key again and reports success only if the bytes match.
 *
 * Only counts, ids and SHA-256 values reach the log - never a memory sentence. It exists **only in the debug source set**
 * (`src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast` can reach it; a release build contains no
 * receiver. The byte wrapper is [F4MemoryBackup], reused rather than copied, so both instruments share the one
 * absent-vs-empty encoding.
 */
class DebugMemoryLogSeed : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED) return
        val store = storeFor(context)
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_BACKUP -> backup(context, store, force)
            MODE_SEED -> seed(context, store)
            MODE_RESTORE -> restore(context, store)
            else -> refuse(MODE_REFUSED, "UNKNOWN_MODE")
        }
    }

    /** The exact namespace handle the plugin's [MemoryLog] writes through; `null` when the composition has none. */
    private fun storeFor(context: Context): StorageHandle? =
        runCatching { (context.applicationContext as? EdithApplication)?.composition?.memoryStoreForProbe() }.getOrNull()

    private fun backup(context: Context, store: StorageHandle?, force: Boolean) {
        if (store == null) return refuse(MODE_BACKUP, "NO_STORE")
        val raw = try {
            store.read(MemoryLog.KEY)
        } catch (e: Exception) {
            return refuse(MODE_BACKUP, "READ_FAILED")
        }
        val file = backupFile(context) ?: return refuse(MODE_BACKUP, "NO_EXTERNAL_DIR")
        // The one way back must never be replaced by a backup of the synthetic document: a second `backup` after `seed`
        // would capture the two evidence sentences, and `restore` would then verify "success" while the owner's real
        // memories are gone. A deliberate replacement has to say so with `--ez force true`.
        if (!mayOverwriteBackup(existing = file.isFile, force = force)) {
            return refuse(MODE_BACKUP, "BACKUP_EXISTS")
        }
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(F4MemoryBackup.encode(raw))
        } catch (e: Exception) {
            return refuse(MODE_BACKUP, "WRITE_FAILED")
        }
        P3Log.line(
            "P3_P2MEM mode=backup present=${raw != null} bytes=${raw?.size ?: 0} " +
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
        val short = try {
            log.record(MemoryKind.FACT, SHORT_TEXT, MemorySource.EXPLICIT)
        } catch (e: Exception) {
            null
        }
        if (short == null) return refuse(MODE_SEED, "WRITE_FAILED")
        val long = try {
            log.record(MemoryKind.PREFERENCE, LONG_TEXT, MemorySource.EXPLICIT)
        } catch (e: Exception) {
            null
        }
        if (long == null) return refuse(MODE_SEED, "WRITE_FAILED")
        val entries = try {
            log.entries().size
        } catch (e: Exception) {
            -1
        }
        P3Log.line("P3_P2MEM mode=seed ids=${short.id},${long.id} entries=$entries")
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
        // Verified, not merely attempted: the evidence is only allowed to claim "the owner's document is back" when the
        // bytes that came out are the bytes that went in.
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
        // A restored backup is spent: leaving it behind is the only remaining way for a later `backup` to capture the
        // synthetic document, and a spent file cannot restore anything the current bytes do not already hold.
        val backupRemoved = runCatching { file.delete() }.getOrDefault(false)
        P3Log.line("P3_P2MEM mode=restore verified=true entries=$entries backupRemoved=$backupRemoved")
    }

    private fun backupFile(context: Context): File? =
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, BACKUP_FILE) }

    private fun refuse(mode: String, reason: String) {
        P3Log.line("P3_P2MEM mode=$mode refused reason=$reason")
    }

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION_SEED = "edith.DEBUG_MEMORY_LOG_SEED"

        /** The verb selector: `backup`, `seed` or `restore`. */
        const val EXTRA_MODE = "mode"

        /** `--ez force true` is the only way to replace an existing backup; without it a second `backup` refuses. */
        const val EXTRA_FORCE = "force"

        const val MODE_BACKUP = "backup"
        const val MODE_SEED = "seed"
        const val MODE_RESTORE = "restore"

        /**
         * Whether a `backup` may write over the file already on disk.
         *
         * Refusing by default is what keeps the one way back honest: after `seed` the current document is the synthetic
         * one, so a second backup would overwrite the owner's bytes with the evidence sentences and `restore` would
         * then report a verified success over lost memories. Replacing the file on purpose needs `force`.
         */
        fun mayOverwriteBackup(existing: Boolean, force: Boolean): Boolean = !existing || force

        /** Logged when the `mode` extra is absent or unknown; never treated as one of the three verbs. */
        private const val MODE_REFUSED = "REFUSED"

        /** The sentence under the preview cap: the screenshot must show it whole. */
        const val SHORT_TEXT = "P2 证据短记忆：周末散步"

        /**
         * The sentence above the preview cap: the screenshot must show `…` in the list and every character in detail.
         *
         * Deliberately uses `、` and not `，`/`,`: the device probe splits its `labels` extra on those separators, so a
         * comma inside the sentence would make the row's a11y label impossible to pass back to the probe verbatim.
         */
        const val LONG_TEXT =
            "P2 证据长记忆：这是一条用于验证记忆列表缩略与全屏详情完整全文的合成记录、" +
                "长度明显超过四十个码点、列表里应当被截断并显示省略号。"

        /** Where the original document's wrapper lives, under the app's external files directory. */
        const val BACKUP_FILE = "p2-memory-backup.bin"
    }
}
