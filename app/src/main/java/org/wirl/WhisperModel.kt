package org.wirl

import java.io.File
import java.security.MessageDigest

/**
 * The on-device Whisper models the user can choose. Each is downloaded from the whisper.cpp repository on Hugging Face,
 * never bundled in the APK. Size and SHA-256 come from that repository, and a download must match both.
 */
enum class WhisperModel(
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** True if the model understands languages other than English. */
    val multilingual: Boolean,
) {
    FAST("Fast", "ggml-base.en-q5_1.bin", 59_721_011L,
        "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", multilingual = false),
    ACCURATE("Accurate", "ggml-small.en-q5_1.bin", 190_098_681L,
        "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", multilingual = false),
    MULTILINGUAL("Multilingual", "ggml-base-q5_1.bin", 59_707_625L,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", multilingual = true);

    val url: String get() = "$BASE_URL/$fileName"

    /** What the downloader needs to fetch and check this model. */
    val spec: ModelSpec get() = ModelSpec(label, fileName, url, sizeBytes, sha256)

    companion object {
        const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        /** The model used when none has been chosen. */
        val DEFAULT = FAST

        fun fromKey(key: String?): WhisperModel = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** One downloadable file: where it comes from, and what a complete copy must look like. */
data class ModelSpec(
    val label: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Where downloaded models are kept in app storage, and whether a file is there and complete. */
class ModelStore(private val dir: File) {
    fun fileFor(model: ModelSpec): File = File(dir, model.fileName)

    /** The partial download. It survives an interruption, so the next attempt can resume it. */
    fun partialFor(model: ModelSpec): File = File(dir, model.fileName + ".part")

    /** Ready means the file is there with the expected size. The full checksum runs when a download finishes. */
    fun isReady(model: ModelSpec): Boolean {
        val file = fileFor(model)
        return file.isFile && file.length() == model.sizeBytes
    }

    /** Removes the model and any partial download. */
    fun delete(model: ModelSpec) {
        fileFor(model).delete()
        partialFor(model).delete()
    }
}

object Checksum {
    /** The SHA-256 of [file] as lowercase hex. Reads in chunks, so large models don't need to fit in memory. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
