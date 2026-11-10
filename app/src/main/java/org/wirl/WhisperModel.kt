package org.wirl

import java.io.File
import java.security.MessageDigest

/** Which on-device engine runs a model. */
enum class ModelEngine {
    /** whisper.cpp, built from source (app/src/main/cpp). */
    WHISPER,

    /** SenseVoice through sherpa-onnx (the pinned .aar). Only the Cantonese model. */
    SENSE_VOICE,
}

/**
 * The language a model is for. It decides everything language-related for a dictation, so the screen never shows a
 * language that disagrees with the model: what the model is told ([modelCode]), Google's recognizer ([googleTag]) when
 * it's the fallback or chosen under Advanced, and the cleanup mode.
 */
enum class SpeechLanguage(val modelCode: String, private val fixedGoogleTag: String?, val cleanupMode: CleanupMode) {
    ENGLISH("en", null, CleanupMode.STANDARD),
    CANTONESE("yue", "yue-Hant-HK", CleanupMode.CANTONESE),
    MANDARIN("zh", "zh-CN", CleanupMode.MANDARIN),
    ;

    /** English follows the phone's English (en-GB stays en-GB), otherwise en-US. The others are fixed. */
    fun googleTag(deviceTag: String): String = fixedGoogleTag
        ?: LanguageTag.format(deviceTag).takeIf { it.startsWith("en-") } ?: "en-US"
}

/**
 * The on-device speech models the user can choose, one per language and speed. Each is downloaded, never bundled in the
 * APK, and a download must match its pinned size and SHA-256. The English models are Whisper, from the whisper.cpp
 * repository on Hugging Face. Cantonese and Mandarin are both SenseVoice from the sherpa-onnx releases on GitHub, the
 * same .tar.bz2 archive (checked, then [unpacked] and deleted), so one download serves both. The enum keeps its name
 * and entry names because the saved choice uses them.
 */
enum class WhisperModel(
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val language: SpeechLanguage,
    val engine: ModelEngine = ModelEngine.WHISPER,
    private val downloadUrl: String? = null,
    /** For an archive: the files to keep from it, and their sizes. Empty for a single model file. */
    val unpacked: List<UnpackedFile> = emptyList(),
) {
    FAST("English · Fast", "ggml-base.en-q5_1.bin", 59_721_011L,
        "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", SpeechLanguage.ENGLISH),
    ACCURATE("English · Accurate", "ggml-small.en-q5_1.bin", 190_098_681L,
        "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", SpeechLanguage.ENGLISH),

    /** Cantonese, often mixed with English. SenseVoice int8 (2025-09-09), language "yue". */
    CANTONESE(
        "Cantonese", "$SENSE_VOICE_NAME.tar.bz2", SENSE_VOICE_SIZE, SENSE_VOICE_SHA256, SpeechLanguage.CANTONESE,
        engine = ModelEngine.SENSE_VOICE, downloadUrl = SENSE_VOICE_URL, unpacked = SENSE_VOICE_FILES,
    ),

    /** Mandarin, often mixed with English. The same SenseVoice files as Cantonese, with language "zh". */
    MANDARIN(
        "Mandarin", "$SENSE_VOICE_NAME.tar.bz2", SENSE_VOICE_SIZE, SENSE_VOICE_SHA256, SpeechLanguage.MANDARIN,
        engine = ModelEngine.SENSE_VOICE, downloadUrl = SENSE_VOICE_URL, unpacked = SENSE_VOICE_FILES,
    );

    /** The short label the bubble shows for a moment when dictation switches to this model. */
    val tag: String get() = when (this) {
        FAST -> "EN"
        ACCURATE -> "EN+"
        CANTONESE -> "粵"
        MANDARIN -> "普"
    }

    /** The other models that use the same downloaded files (Cantonese and Mandarin share one). */
    val sharesFilesWith: List<WhisperModel> get() = entries.filter { it != this && it.fileName == fileName }

    val url: String get() = downloadUrl ?: "$BASE_URL/$fileName"

    /** What the downloader needs to fetch and check this model. */
    val spec: ModelSpec get() = ModelSpec(label, fileName, url, sizeBytes, sha256, unpacked)

    companion object {
        const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        /** The model used when none has been chosen. */
        val DEFAULT = FAST

        fun fromKey(key: String?): WhisperModel = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** The archive folder name of the official SenseVoice model, in the sherpa-onnx "asr-models" release. */
private const val SENSE_VOICE_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
private const val SENSE_VOICE_SIZE = 165_783_878L
private const val SENSE_VOICE_SHA256 = "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63"
private const val SENSE_VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SENSE_VOICE_NAME.tar.bz2"
private val SENSE_VOICE_FILES = listOf(
    UnpackedFile("$SENSE_VOICE_NAME/model.int8.onnx", "model.int8.onnx", 237_115_547L),
    UnpackedFile("$SENSE_VOICE_NAME/tokens.txt", "tokens.txt", 315_894L),
)

/** A file kept from a model archive: its path in the archive, the name it's saved under, and its exact size. */
data class UnpackedFile(val entry: String, val name: String, val sizeBytes: Long)

/** One download: where it comes from, and what a complete copy must look like. [unpacked] is set for an archive. */
data class ModelSpec(
    val label: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val unpacked: List<UnpackedFile> = emptyList(),
) {
    val isArchive: Boolean get() = unpacked.isNotEmpty()
}

/** Where downloaded models are kept in app storage, and whether a model is there and complete. */
class ModelStore(private val dir: File) {
    /** The model file, or for an archive model the archive (deleted once it's unpacked). */
    fun fileFor(model: ModelSpec): File = File(dir, model.fileName)

    /** Where an archive model's files are unpacked. */
    fun dirFor(model: ModelSpec): File = File(dir, model.fileName.substringBefore(".tar"))

    /** The partial download. It survives an interruption, so the next attempt can resume it. */
    fun partialFor(model: ModelSpec): File = File(dir, model.fileName + ".part")

    /**
     * Ready means the file is there with the expected size, or for an archive model, every unpacked file is there with
     * its expected size. The full checksum runs when a download finishes.
     */
    fun isReady(model: ModelSpec): Boolean {
        if (model.isArchive) {
            val folder = dirFor(model)
            return model.unpacked.all { File(folder, it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }
        }
        val file = fileFor(model)
        return file.isFile && file.length() == model.sizeBytes
    }

    /** Removes the model, any partial download, and any unpacked files. */
    fun delete(model: ModelSpec) {
        fileFor(model).delete()
        partialFor(model).delete()
        if (model.isArchive) {
            dirFor(model).deleteRecursively()
            File(dirFor(model).path + ".tmp").deleteRecursively()
        }
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
