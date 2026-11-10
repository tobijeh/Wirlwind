package org.wirl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** The Cantonese model: its pinned download, and how an archive model is checked, unpacked, cancelled and resumed. */
class CantoneseModelTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    private val model = ByteArray(5_000) { (it * 7 % 251).toByte() }
    private val tokens = "<blk> 0\n嘅 1\n".toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A .tar.bz2 laid out like the official one: a top folder, the model, tokens, and test recordings that are skipped. */
    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, bytes) in files) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private val wanted = mapOf(
        "sv/model.int8.onnx" to model,
        "sv/tokens.txt" to tokens,
        "sv/test_wavs/yue.wav" to ByteArray(100),
    )

    private fun spec(bytes: ByteArray) = ModelSpec(
        "Cantonese", "sv.tar.bz2", server.url("/sv.tar.bz2").toString(), bytes.size.toLong(), sha256(bytes),
        listOf(UnpackedFile("sv/model.int8.onnx", "model.int8.onnx", model.size.toLong()), UnpackedFile("sv/tokens.txt", "tokens.txt", tokens.size.toLong())),
    )

    private fun downloader() = ModelDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), ModelStore(dir))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("models").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test fun theCantoneseModelIsTheOfficialSenseVoiceInt8Release() {
        val m = WhisperModel.CANTONESE
        assertEquals(ModelEngine.SENSE_VOICE, m.engine)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
            m.url,
        )
        assertEquals(165_783_878L, m.sizeBytes)
        assertEquals("7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63", m.sha256)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), m.unpacked.map { it.name })
        assertTrue(m.spec.isArchive)
        assertEquals("the saved choice uses the enum name", WhisperModel.CANTONESE, WhisperModel.fromKey("CANTONESE"))
        assertEquals("yue", WhisperPrompt.languageFor(m))
        assertEquals("zh", WhisperPrompt.languageFor(WhisperModel.MANDARIN))
        assertEquals("en", WhisperPrompt.languageFor(WhisperModel.ACCURATE))
        // The Whisper models are unchanged.
        assertEquals(ModelEngine.WHISPER, WhisperModel.FAST.engine)
        assertFalse(WhisperModel.FAST.spec.isArchive)
    }

    @Test fun anArchiveIsCheckedUnpackedAndDeleted() {
        val bytes = archive(wanted)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val spec = spec(bytes)
        downloader().download(spec)

        val store = ModelStore(dir)
        assertTrue(store.isReady(spec))
        assertArrayEquals(model, File(store.dirFor(spec), "model.int8.onnx").readBytes())
        assertArrayEquals(tokens, File(store.dirFor(spec), "tokens.txt").readBytes())
        assertFalse("test recordings aren't kept", File(store.dirFor(spec), "yue.wav").exists())
        assertFalse("the archive is deleted", store.fileFor(spec).exists())
        assertFalse(store.partialFor(spec).exists())
        assertTrue(downloader().verifyInstalled(spec))

        store.delete(spec)
        assertFalse(store.dirFor(spec).exists())
        assertFalse(store.isReady(spec))
    }

    @Test fun anArchiveMissingAFileIsRejected() {
        val bytes = archive(wanted - "sv/tokens.txt")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val spec = spec(bytes)
        assertThrows(ModelDownloader.CorruptModelException::class.java) { downloader().download(spec) }
        assertFalse(ModelStore(dir).isReady(spec))
        assertFalse(File(ModelStore(dir).dirFor(spec).path + ".tmp").exists())
    }

    @Test fun aWrongChecksumIsRejectedBeforeUnpacking() {
        val bytes = archive(wanted)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val spec = spec(bytes).copy(sha256 = "0".repeat(64))
        assertThrows(ModelDownloader.CorruptModelException::class.java) { downloader().download(spec) }
        assertFalse(ModelStore(dir).dirFor(spec).exists())
    }

    @Test fun cancellingKeepsThePartialFileForResume() {
        val bytes = archive(wanted + ("sv/big.bin" to ByteArray(400_000).also { java.util.Random(1).nextBytes(it) }))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val spec = spec(bytes)
        var received = 0L
        assertThrows(ModelDownloader.CancelledException::class.java) {
            downloader().download(spec, cancelled = { received > 0 }) { done, _ -> received = done }
        }
        val partial = ModelStore(dir).partialFor(spec)
        assertTrue(partial.isFile && partial.length() in 1 until bytes.size)
        assertFalse(ModelStore(dir).isReady(spec))
    }

    @Test fun cantoneseChunksJoinWithoutSpacesAndEnglishKeepsItsSpaces() {
        val a = ChunkAssembler()
        a.add(0, "呢几个字都表达唔到")
        a.add(1, "我想讲嘅意思")
        a.add(2, "THE MEETING IS AT FIVE")
        a.add(3, "我哋到时见")
        assertEquals("呢几个字都表达唔到我想讲嘅意思 THE MEETING IS AT FIVE 我哋到时见", a.text())
        val b = ChunkAssembler()
        b.add(0, "好，")
        b.add(1, "冇问题。")
        assertEquals("好，冇问题。", b.text())
        val english = ChunkAssembler()
        english.add(0, "Hello there")
        english.add(1, "how are you")
        assertEquals("Hello there how are you", english.text())
    }

    @Test fun sensevoiceTimingIsItsOwnStage() {
        val lines = DictationTiming(whisper = WhisperTiming(5, 120, 300, 2, 1, stage = "sensevoice")).lines()
        assertEquals("sensevoice model wait: 5 ms", lines[0])
        assertEquals("sensevoice final chunk: 120 ms", lines[1])
        assertEquals("sensevoice total: 300 ms, 2 chunks (1 during recording)", lines[2])
        assertEquals("whisper model wait: 5 ms", DictationTiming(whisper = WhisperTiming(5, null, 1, 1, 0)).lines()[0])
    }

    @Test fun cantoneseAndMandarinShareOneDownload() {
        val c = WhisperModel.CANTONESE
        val m = WhisperModel.MANDARIN
        assertEquals(c.spec.copy(label = ""), m.spec.copy(label = ""))
        assertEquals(listOf(m), c.sharesFilesWith)
        assertEquals(listOf(c), m.sharesFilesWith)
        assertTrue(WhisperModel.FAST.sharesFilesWith.isEmpty())
        // One download shows on both cards, and cancelling either cancels it.
        ModelDownloads.clear()
        ModelDownloads.start(c)
        ModelDownloads.update(c, 30)
        assertEquals(30, ModelDownloads.percentOf(m))
        ModelDownloads.cancel(m)
        assertTrue(ModelDownloads.isCancelled(c))
        ModelDownloads.clear()
    }

    @Test fun googleFollowsTheModelsLanguage() {
        assertEquals("yue-Hant-HK", WhisperModel.CANTONESE.language.googleTag("en-US"))
        assertEquals("zh-CN", WhisperModel.MANDARIN.language.googleTag("en-US"))
        assertEquals("en-AU", WhisperModel.FAST.language.googleTag("en-AU"))
        assertEquals("en-US", WhisperModel.ACCURATE.language.googleTag("de-DE"))
    }

}
