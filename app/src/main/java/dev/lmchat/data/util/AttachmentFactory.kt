package dev.lmchat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Writer
import kotlinx.coroutines.CancellationException
import java.util.Base64

class AttachmentImportException(message: String) : java.io.IOException(message)

/**
 * Turns a content-Uri (gallery pick, camera capture, document pick) into an
 * [Attachment] that can be persisted and sent to the model.
 */
object AttachmentFactory {

    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024

    /** Max image dimension sent to vision models (keeps payloads small). */
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val JPEG_QUALITY = 85
    /** Cap extracted document text so a huge file can't blow up the prompt. */
    const val MAX_DOC_CHARS = 120_000

    suspend fun fromImage(context: Context, uri: Uri, displayName: String? = null): Attachment? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readInput(context, uri, MAX_IMAGE_BYTES, "Image", "10 MiB")
                    ?: return@withContext null
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val name = displayName ?: displayNameOf(context, uri) ?: "image"
                val (compressed, mimeType) = downscale(bytes, MAX_IMAGE_DIMENSION, mime)
                Attachment(
                    kind = AttachmentKind.IMAGE,
                    name = name,
                    mimeType = mimeType,
                    base64 = Base64.getEncoder().encodeToString(compressed),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AttachmentImportException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    suspend fun fromDocument(context: Context, uri: Uri): Attachment? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readInput(context, uri, MAX_DOCUMENT_BYTES, "Document", "8 MiB")
                    ?: return@withContext null
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = displayNameOf(context, uri) ?: "document"
                val text = when {
                    mime == "application/pdf" -> DocumentExtractor.extractPdf(bytes)
                    else -> String(bytes, Charsets.UTF_8)
                }
                if (text.isNullOrBlank()) return@withContext null
                val capped = if (text.length > MAX_DOC_CHARS) {
                    text.take(MAX_DOC_CHARS) + "\n\n[Document truncated]"
                } else text
                Attachment(
                    kind = AttachmentKind.DOCUMENT,
                    name = name,
                    mimeType = mime,
                    text = capped,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AttachmentImportException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    /* ---------------- helpers ---------------- */

    private fun readInput(context: Context, uri: Uri, limit: Int, kind: String, limitLabel: String): ByteArray? {
        val message = "$kind is too large. Choose a file of $limitLabel or less."
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index) && cursor.getLong(index) > limit) {
                throw AttachmentImportException(message)
            }
        }
        // Providers may omit or misreport SIZE; enforce the bound on the stream too.
        return context.contentResolver.openInputStream(uri)?.use { readLimited(it, limit, message) }
    }

    internal fun readLimited(input: InputStream, limit: Int, message: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw AttachmentImportException(message)
            output.write(buffer, 0, count)
        }
    }

    private fun displayNameOf(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /** Downscale + re-encode; returns the original bytes if the image is already small. */
    private fun downscale(bytes: ByteArray, maxDim: Int, mime: String): Pair<ByteArray, String> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) throw AttachmentImportException("Couldn't decode that image. Choose a supported image file.")
        val biggest = maxOf(w, h)
        if (biggest <= maxDim) return bytes to mime

        var sample = 1
        while (biggest / sample / 2 > maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw AttachmentImportException("Couldn't decode that image.")
        val scaled: Bitmap = if (maxOf(bitmap.width, bitmap.height) > maxDim) {
            val ratio = maxDim.toFloat() / biggest
            Bitmap.createScaledBitmap(
                bitmap,
                (w * ratio).toInt().coerceAtLeast(1),
                (h * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray() to "image/jpeg"
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}

/**
 * Document text extraction. PDFs go through Apache PDFBox; everything else is
 * decoded as UTF-8 text (covers .txt/.md/.csv/.json/.xml and source files).
 */
object DocumentExtractor {
    const val MAX_PDF_PAGES = 200

    fun extractPdf(bytes: ByteArray): String? = try {
        PDDocument.load(bytes).use { doc ->
            if (doc.numberOfPages > MAX_PDF_PAGES) {
                throw AttachmentImportException("PDF has too many pages. Choose a PDF with $MAX_PDF_PAGES pages or fewer.")
            }
            // Stop extraction as soon as enough text exists, rather than allocating the
            // entire extracted document and truncating it afterwards.
            val output = LimitedTextWriter(AttachmentFactory.MAX_DOC_CHARS + 1)
            try {
                PDFTextStripper().writeText(doc, output)
            } catch (_: TextLimitReached) {
                // The extra character lets the caller mark the attachment as truncated.
            }
            output.toString().takeIf { it.isNotBlank() }
        }
    } catch (error: AttachmentImportException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private class TextLimitReached : java.io.IOException()

    private class LimitedTextWriter(private val limit: Int) : Writer() {
        private val text = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val count = minOf(length, limit - text.length)
            text.append(buffer, offset, count)
            if (count < length) throw TextLimitReached()
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }
}
