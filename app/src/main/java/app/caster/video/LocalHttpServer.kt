package app.caster.video

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

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

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/video/$pathToken") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val totalLength = queryLength()
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
                val stream = openStream() ?: return notFound()
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

        val stream = openStream() ?: return notFound()
        skipFully(stream, start)

        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mimeType, stream, contentLength
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$clampedEnd/$totalLength")
        }
    }

    private fun openStream(): InputStream? = context.contentResolver.openInputStream(videoUri)

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
    }
}
