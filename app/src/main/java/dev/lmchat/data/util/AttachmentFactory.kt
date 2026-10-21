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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Turns a content-Uri (gallery pick, camera capture, document pick) into an
 * [Attachment] that can be persisted and sent to the model.
 */
object AttachmentFactory {

    /** Max image dimension sent to vision models (keeps payloads small). */
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val JPEG_QUALITY = 85
    /** Cap extracted document text so a huge file can't blow up the prompt. */
    const val MAX_DOC_CHARS = 120_000

    suspend fun fromImage(context: Context, uri: Uri, displayName: String? = null): Attachment? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
            } catch (_: Exception) {
                null
            }
        }

    suspend fun fromDocument(context: Context, uri: Uri): Attachment? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
            } catch (_: Exception) {
                null
            }
        }

    /* ---------------- helpers ---------------- */

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
        if (w <= 0 || h <= 0) return bytes to mime
        val biggest = maxOf(w, h)
        if (biggest <= maxDim) return bytes to mime

        var sample = 1
        while (biggest / sample / 2 > maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return bytes to mime
        val scaled: Bitmap = if (bitmap.width != w || bitmap.height != h) {
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

    fun extractPdf(bytes: ByteArray): String? = try {
        Loader.loadPDF(bytes).use { doc ->
            PDFTextStripper().getText(doc).takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }
}
