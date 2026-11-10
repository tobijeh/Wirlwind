package org.wirl

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * The Cantonese model: SenseVoice through sherpa-onnx. It works on finished audio, so it gets the same chunks Whisper does
 * (cut at pauses by [PauseDetector], about 5 to 10 seconds each), one at a time, and [ChunkAssembler] joins the text in
 * spoken order. It runs in the app's process, like Whisper, through [ModelHolder].
 *
 * Settings, chosen by testing the model's own Cantonese recordings and Cantonese mixed with English:
 * - language "yue" rather than "auto": the same on mixed Cantonese and English (English words stay English, in place),
 *   and it can't misread a short Cantonese clip as Mandarin.
 * - use_itn on: the model's own number and punctuation formatting.
 * The model writes simplified characters without punctuation; Haiku's Cantonese mode ([CleanupPrompt.CANTONESE_MODE])
 * turns that into traditional characters with Hong Kong style punctuation.
 */
object SenseVoice {
    const val LANGUAGE = "yue"
    const val USE_ITN = true
    const val SAMPLE_RATE = 16_000

    /** The model file and tokens inside the unpacked model folder. */
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
}

/** One SenseVoice recognizer. Like Whisper, it's freed only between transcriptions: [close] waits for one in progress. */
class SenseVoiceTranscriber private constructor(private var recognizer: OfflineRecognizer?) : SpeechTranscriber {
    /** [language] and [prompt] are ignored: the language is fixed when the model loads, and SenseVoice takes no prompt. */
    @Synchronized
    override fun transcribe(pcm: FloatArray, language: String, prompt: String?): String {
        val current = checkNotNull(recognizer) { "The model isn't loaded" }
        val stream = current.createStream()
        try {
            stream.acceptWaveform(pcm, SenseVoice.SAMPLE_RATE)
            current.decode(stream)
            return current.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        /** Loads the model in [dir]. Returns null if it can't be loaded, which usually means the files are damaged. */
        fun load(dir: File, threads: Int): SenseVoiceTranscriber? = runCatching {
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, SenseVoice.MODEL_FILE).path,
                        language = SenseVoice.LANGUAGE,
                        useInverseTextNormalization = SenseVoice.USE_ITN,
                    ),
                    tokens = File(dir, SenseVoice.TOKENS_FILE).path,
                    numThreads = threads,
                    provider = "cpu",
                ),
            )
            SenseVoiceTranscriber(OfflineRecognizer(null, config))
        }.getOrNull()
    }
}

/** Whether sherpa-onnx's native library loads. Never throws. */
object SherpaLibrary {
    const val NAME = "sherpa-onnx-jni"

    val loads: Boolean by lazy { runCatching { System.loadLibrary(NAME) }.isSuccess }
}

/** The native library a model needs. */
object ModelLibraries {
    fun loads(model: WhisperModel): Boolean = when (model.engine) {
        ModelEngine.WHISPER -> WhisperLibrary.loads
        ModelEngine.SENSE_VOICE -> SherpaLibrary.loads
    }
}
