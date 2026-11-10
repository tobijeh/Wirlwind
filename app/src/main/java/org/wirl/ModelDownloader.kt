package org.wirl

import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads a speech model into [store]. Bytes go to a .part file as they arrive, so an interrupted or cancelled download
 * keeps what it has, and the next attempt asks the server for the rest with a Range header. When the file is complete it's
 * checked against the model's size and SHA-256. A file that doesn't match is deleted, so a corrupt model is never loaded.
 * An archive model (the Cantonese one) is then unpacked: only its listed files are kept, each checked by size, and the
 * archive is deleted.
 */
class ModelDownloader(private val http: OkHttpClient, private val store: ModelStore) {
    class CorruptModelException(message: String) : IOException(message)

    /** The user cancelled. The partial file is kept, so Resume continues from there. */
    class CancelledException : IOException("Download cancelled")

    /**
     * Makes [model] ready. Does nothing if it already is. [url] defaults to the official location, and tests point it at
     * a local server. [onProgress] receives (bytes so far, total bytes). [cancelled] is checked as bytes arrive.
     */
    fun download(
        model: ModelSpec,
        url: String = model.url,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
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
                            if (cancelled()) throw CancelledException()
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
        if (model.isArchive) {
            unpack(model, part)
            part.delete()
            return
        }
        val target = store.fileFor(model)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the ${model.label} model")
    }

    /**
     * Unpacks the listed files from a checked .tar.bz2 archive into a temporary folder, checks each one's size, then moves
     * the folder into place. Anything else in the archive (test recordings, readme) is skipped.
     */
    private fun unpack(model: ModelSpec, archive: File) {
        val target = store.dirFor(model)
        val temp = File(target.path + ".tmp")
        temp.deleteRecursively()
        temp.mkdirs()
        try {
            archive.inputStream().buffered(1 shl 16).use { raw ->
                TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val wanted = model.unpacked.firstOrNull { it.entry == entry.name.removePrefix("./") } ?: continue
                        File(temp, wanted.name).outputStream().buffered(1 shl 16).use { tar.copyTo(it, 1 shl 16) }
                    }
                }
            }
            val bad = model.unpacked.filter { File(temp, it.name).length() != it.sizeBytes }
            if (bad.isNotEmpty()) {
                throw CorruptModelException(
                    "The ${model.label} model archive was missing ${bad.joinToString { it.name }}. Try the download again.",
                )
            }
            target.deleteRecursively()
            if (!temp.renameTo(target)) throw IOException("Couldn't save the ${model.label} model")
        } catch (e: IOException) {
            temp.deleteRecursively()
            throw e
        }
    }

    /** Checks a model that's already on disk. A file that fails is deleted, so the next use downloads it again. */
    fun verifyInstalled(model: ModelSpec): Boolean {
        if (model.isArchive) {
            // The archive was checked by SHA-256 before it was unpacked; here, each unpacked file is checked by size.
            val intact = store.isReady(model)
            if (!intact) store.delete(model)
            return intact
        }
        val file = store.fileFor(model)
        if (!file.isFile) return false
        val intact = file.length() == model.sizeBytes && Checksum.sha256(file) == model.sha256
        if (!intact) file.delete()
        return intact
    }
}
