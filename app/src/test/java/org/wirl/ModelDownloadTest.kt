package org.wirl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class ModelDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val content = ByteArray(10_000) { (it * 31 % 251).toByte() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun spec(bytes: ByteArray = content, sha: String = sha256(content)) =
        ModelSpec("Test", "ggml-test.bin", server.url("/model.bin").toString(), bytes.size.toLong(), sha)

    private fun downloader() = ModelDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), ModelStore(dir))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("models").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test fun downloadsAndInstallsAModelThatMatches() {
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val spec = spec()
        val progress = mutableListOf<Long>()
        downloader().download(spec) { done, _ -> progress += done }

        val store = ModelStore(dir)
        assertTrue(store.isReady(spec))
        assertArrayEquals(content, store.fileFor(spec).readBytes())
        assertFalse(store.partialFor(spec).exists())
        assertEquals(content.size.toLong(), progress.last())
    }

    @Test fun anInterruptedDownloadResumesWithARangeRequest() {
        var requests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                // The first response is cut off halfway through the body.
                if (requests == 1) {
                    return MockResponse().setBody(Buffer().write(content))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                val from = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(from, content.size)))
            }
        }
        val spec = spec()

        assertThrows(IOException::class.java) { downloader().download(spec) }
        val partial = ModelStore(dir).partialFor(spec).length()
        assertTrue("some bytes were kept: $partial", partial in 1 until content.size)

        downloader().download(spec)

        assertArrayEquals(content, ModelStore(dir).fileFor(spec).readBytes())
        server.takeRequest()
        assertEquals("bytes=$partial-", server.takeRequest().getHeader("Range"))
    }

    @Test fun aServerThatIgnoresRangeStartsTheFileOver() {
        val spec = spec()
        // A partial file of the right kind, but the server sends the whole model anyway.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(Buffer().write(content))
        }
        ModelStore(dir).partialFor(spec).writeBytes(ByteArray(4_000) { 9 })

        downloader().download(spec)

        assertArrayEquals(content, ModelStore(dir).fileFor(spec).readBytes())
    }

    @Test fun aCorruptDownloadIsDetectedDeletedAndNeverInstalled() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(content.size) { 1 })))
        val spec = spec()

        val error = assertThrows(ModelDownloader.CorruptModelException::class.java) { downloader().download(spec) }

        assertTrue(error.message!!.contains("deleted"))
        assertFalse(ModelStore(dir).fileFor(spec).exists())
        assertFalse(ModelStore(dir).partialFor(spec).exists())
    }

    @Test fun aDownloadOfTheWrongSizeIsCorruptToo() {
        server.enqueue(MockResponse().setBody(Buffer().write(content.copyOf(100))))
        assertThrows(ModelDownloader.CorruptModelException::class.java) { downloader().download(spec()) }
        assertFalse(ModelStore(dir).isReady(spec()))
    }

    @Test fun aModelThatIsAlreadyReadyIsNotDownloadedAgain() {
        val spec = spec()
        ModelStore(dir).fileFor(spec).writeBytes(content)
        downloader().download(spec)
        assertEquals(0, server.requestCount)
    }

    @Test fun aDamagedModelOnDiskIsFoundAndRemoved() {
        val spec = spec()
        val file = ModelStore(dir).fileFor(spec)
        file.writeBytes(ByteArray(content.size) { 2 })
        assertFalse(downloader().verifyInstalled(spec))
        assertFalse("the damaged file is deleted", file.exists())
    }

    @Test fun anIntactModelOnDiskPassesVerification() {
        val spec = spec()
        ModelStore(dir).fileFor(spec).writeBytes(content)
        assertTrue(downloader().verifyInstalled(spec))
        assertTrue(ModelStore(dir).fileFor(spec).exists())
    }
}
