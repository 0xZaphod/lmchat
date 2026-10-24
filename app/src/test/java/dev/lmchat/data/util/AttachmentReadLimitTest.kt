package dev.lmchat.data.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AttachmentReadLimitTest {
    @Test fun exactLimitAndShortReadsAreAccepted() {
        val expected = ByteArray(37) { it.toByte() }
        val shortReads = object : ByteArrayInputStream(expected) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 3))
        }
        assertArrayEquals(expected, AttachmentFactory.readLimited(shortReads, expected.size, "too large"))
    }

    @Test fun unknownLengthInputStopsAfterOneByteBeyondLimit() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                bytes.fill(1, offset, offset + length)
                return length
            }
        }
        try {
            AttachmentFactory.readLimited(endless, 1024, "Document is too large")
            fail("Oversized input accepted")
        } catch (error: AttachmentImportException) {
            assertEquals("Document is too large", error.message)
            assertEquals(1025, consumed)
        }
    }
}
