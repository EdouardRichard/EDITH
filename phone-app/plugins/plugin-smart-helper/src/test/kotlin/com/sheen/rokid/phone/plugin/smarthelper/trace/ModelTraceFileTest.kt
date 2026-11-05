package com.sheen.rokid.phone.plugin.smarthelper.trace

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The debug trace's file writer: UTF-8, append, one record per line, and a failure to write can never fail a turn.
 *
 * The formatter already escapes newlines inside a record (`ModelTraceTest`), but the writer is the last line of defence
 * for the "one line per run" contract: a caller that hands it a raw newline must still produce one readable record
 * rather than corrupt the file's line-oriented shape for every later run.
 */
class ModelTraceFileTest {

    private fun tempDir(): File = Files.createTempDirectory("edith-model-trace-test").toFile()

    @Test
    fun `records append to the one file as UTF-8 lines`() {
        val dir = tempDir()
        val write = ModelTraceFile.writer(dir)

        write("""{"turnId":"one","finalText":"第一轮"}""")
        write("""{"turnId":"two","finalText":"第二轮"}""")

        val file = File(dir, ModelTraceFile.FILE_NAME)
        assertEquals(
            listOf("""{"turnId":"one","finalText":"第一轮"}""", """{"turnId":"two","finalText":"第二轮"}"""),
            file.readLines(Charsets.UTF_8),
            "an existing trace file must be appended to, never truncated",
        )
    }

    @Test
    fun `a raw newline handed to the writer stays one record`() {
        val dir = tempDir()
        val write = ModelTraceFile.writer(dir)

        write("first\nsecond")
        write("third")

        val file = File(dir, ModelTraceFile.FILE_NAME)
        assertEquals(listOf("first\\nsecond", "third"), file.readLines(Charsets.UTF_8))
    }

    @Test
    fun `a null files directory is a safe no-op`() {
        // Production passes a real Context.filesDir; a JVM/unit-test fake may answer null. Tracing must never fail the
        // composition that constructs it, so the null directory degrades to the plugin's no-op writer.
        val write = ModelTraceFile.writer(null)

        write("""{"turnId":"none"}""")
    }

    @Test
    fun `a files directory that does not exist yet cannot fail the caller`() {
        val dir = File(tempDir(), "nested/files")
        val write = ModelTraceFile.writer(dir)

        write("""{"turnId":"one"}""")

        val file = File(dir, ModelTraceFile.FILE_NAME)
        assertTrue(file.exists(), "the writer must create the private files directory it was handed")
    }
}
