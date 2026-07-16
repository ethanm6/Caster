// SPDX-License-Identifier: GPL-3.0-or-later
package app.caster.video

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves a single content:// or file:// video URI over HTTP so the
 * Chromecast can fetch it. Supports Range requests (required for seeking).
 * The file is only reachable under a random path token.
 */
class LocalHttpServer(
    private val context: Context,
    private val videoUri: Uri,
    private val mimeType: String,
    val pathToken: String,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    // The URI never changes for a server instance; the Chromecast issues many
    // range requests, so don't reopen a descriptor per request just for this.
    private val totalLength: Long by lazy { queryLength() }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/video/$pathToken") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        if (totalLength < 0) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot determine file size"
            )
        }

        val rangeHeader = session.headers["range"]
        return try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                serveRange(rangeHeader, totalLength)
            } else {
                val stream = openStreamAt(0) ?: return notFound()
                newFixedLengthResponse(Response.Status.OK, mimeType, stream, totalLength).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveRange(rangeHeader: String, totalLength: Long): Response {
        val range = rangeHeader.removePrefix("bytes=")
        val parts = range.split("-", limit = 2)
        val start = parts[0].toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull() ?: (totalLength - 1)

        if (start >= totalLength || start > end) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, ""
            ).apply { addHeader("Content-Range", "bytes */$totalLength") }
        }

        val clampedEnd = minOf(end, totalLength - 1)
        val contentLength = clampedEnd - start + 1

        val stream = openStreamAt(start) ?: return notFound()

        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mimeType, stream, contentLength
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$clampedEnd/$totalLength")
        }
    }

    /**
     * Opens the video positioned at [offset]. Seeks via the file channel when
     * the provider hands out a real file descriptor; pipe-backed streams fall
     * back to skip(), which reads and discards everything up to the offset.
     */
    private fun openStreamAt(offset: Long): InputStream? {
        if (offset > 0) {
            try {
                context.contentResolver.openFileDescriptor(videoUri, "r")?.let { pfd ->
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    try {
                        stream.channel.position(offset)
                        return CountingStream(stream)
                    } catch (e: IOException) {
                        stream.close() // not seekable after all
                    }
                }
            } catch (e: Exception) {
                // Provider won't open as a plain fd; use the skipping path.
            }
        }
        val stream = context.contentResolver.openInputStream(videoUri) ?: return null
        skipFully(stream, offset)
        return CountingStream(stream)
    }

    /** Adds everything the Chromecast actually reads to [bytesServed]; skips don't count. */
    private class CountingStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int =
            delegate.read().also { if (it >= 0) bytesServed.incrementAndGet() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) bytesServed.addAndGet(it.toLong()) }

        override fun skip(n: Long): Long = delegate.skip(n)
        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    private fun queryLength(): Long = try {
        context.contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    companion object {
        const val PORT = 8642

        /** Total bytes served to the Chromecast, ever-increasing; readers take deltas. */
        val bytesServed = AtomicLong()
    }
}
