package org.wirl

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads a Whisper model into [store]. Bytes go to a .part file as they arrive, so an interrupted download keeps what
 * it has, and the next attempt asks the server for the rest with a Range header. When the file is complete it's checked
 * against the model's size and SHA-256. A file that doesn't match is deleted, so a corrupt model is never loaded.
 */
class ModelDownloader(private val http: OkHttpClient, private val store: ModelStore) {
    class CorruptModelException(message: String) : IOException(message)

    /**
     * Makes [model] ready. Does nothing if it already is. [url] defaults to the official location, and tests point it at
     * a local server. [onProgress] receives (bytes so far, total bytes).
     */
    fun download(model: ModelSpec, url: String = model.url, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        if (store.isReady(model)) return
        store.fileFor(model).parentFile?.mkdirs()
        val part = store.partialFor(model)
        var offset = if (part.isFile) part.length() else 0L

        if (offset != model.sizeBytes) {
            val request = Request.Builder().url(url).apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    // The partial file doesn't fit what the server has. Start over on the next attempt.
                    part.delete()
                    throw IOException("The partial download didn't match the server. Try again.")
                }
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                // A server that ignores Range sends the whole file again, so the partial file starts over.
                val resuming = response.code == 206 && offset > 0
                if (!resuming) offset = 0
                val body = response.body ?: throw IOException("Download returned no data")
                FileOutputStream(part, resuming).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var written = offset
                        onProgress(written, model.sizeBytes)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onProgress(written, model.sizeBytes)
                        }
                    }
                }
            }
        }
        verifyAndInstall(model, part)
    }

    private fun verifyAndInstall(model: ModelSpec, part: File) {
        val sizeOk = part.length() == model.sizeBytes
        if (!sizeOk || Checksum.sha256(part) != model.sha256) {
            part.delete()
            throw CorruptModelException(
                "The ${model.label} model didn't match the expected file. It was deleted, so try the download again.",
            )
        }
        val target = store.fileFor(model)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the ${model.label} model")
    }

    /** Checks a model that's already on disk. A file that fails is deleted, so the next use downloads it again. */
    fun verifyInstalled(model: ModelSpec): Boolean {
        val file = store.fileFor(model)
        if (!file.isFile) return false
        val intact = file.length() == model.sizeBytes && Checksum.sha256(file) == model.sha256
        if (!intact) file.delete()
        return intact
    }
}
