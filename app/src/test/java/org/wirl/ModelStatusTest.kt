package org.wirl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelStatusTest {
    private lateinit var dir: File
    private lateinit var store: ModelStore
    private val model = WhisperModel.FAST

    @Before fun setUp() {
        dir = Files.createTempDirectory("whisper").toFile()
        store = ModelStore(dir)
        ModelDownloads.clear()
    }

    @After fun tearDown() {
        ModelDownloads.clear()
        dir.deleteRecursively()
    }

    @Test fun aModelWithNothingOnDiskIsNotDownloaded() {
        assertEquals(ModelState.NotDownloaded, ModelStatus.of(store, model))
    }

    @Test fun aCompleteModelFileIsReady() {
        // Ready means the right size on disk. The checksum runs when a download finishes.
        store.fileFor(model.spec).writeBytes(ByteArray(model.sizeBytes.toInt()))
        assertEquals(ModelState.Ready, ModelStatus.of(store, model))
    }

    @Test fun aPartialFileShowsItsPercentage() {
        // A quarter of the file, rounded down: the size isn't divisible by four, so this shows 24%.
        store.partialFor(model.spec).writeBytes(ByteArray((model.sizeBytes / 4).toInt()))
        assertEquals(ModelState.Partial(24), ModelStatus.of(store, model))
    }

    @Test fun aDownloadInProgressShowsItsPercentageAndWinsOverAPartialFile() {
        store.partialFor(model.spec).writeBytes(ByteArray(10))
        ModelDownloads.start(model)
        ModelDownloads.update(model, 42)
        assertEquals(ModelState.Downloading(42), ModelStatus.of(store, model))
    }

    @Test fun statusFollowsProgressAndFinishing() {
        ModelDownloads.start(model)
        assertEquals(ModelState.Downloading(0), ModelStatus.of(store, model))
        assertTrue("a change of whole percent redraws", ModelDownloads.update(model, 1))
        assertFalse("the same percent doesn't", ModelDownloads.update(model, 1))
        // The download finishes: the file is complete and the progress is cleared.
        store.fileFor(model.spec).writeBytes(ByteArray(model.sizeBytes.toInt()))
        ModelDownloads.finish(model)
        assertEquals(ModelState.Ready, ModelStatus.of(store, model))
    }

    @Test fun deletingAModelReturnsItToNotDownloaded() {
        store.fileFor(model.spec).writeBytes(ByteArray(model.sizeBytes.toInt()))
        store.partialFor(model.spec).writeBytes(ByteArray(5))
        store.delete(model.spec)
        assertEquals(ModelState.NotDownloaded, ModelStatus.of(store, model))
    }

    @Test fun otherModelsAreNotAffectedByOneDownload() {
        ModelDownloads.start(model)
        assertEquals(ModelState.NotDownloaded, ModelStatus.of(store, WhisperModel.ACCURATE))
    }

    @Test fun percentagesStayBetweenZeroAndOneHundred() {
        assertEquals(0, ModelStatus.percent(5, 0))
        assertEquals(50, ModelStatus.percent(50, 100))
        assertEquals(100, ModelStatus.percent(150, 100))
    }

    @Test fun downloadsOnlyStartOnAnUnmeteredNetworkOrWithMobileDataAllowed() {
        assertEquals(DownloadCheck.ALLOWED, DownloadPolicy.check(online = true, unmetered = true, mobileDataAllowed = false))
        assertEquals(DownloadCheck.NEEDS_MOBILE_DATA, DownloadPolicy.check(online = true, unmetered = false, mobileDataAllowed = false))
        assertEquals(DownloadCheck.ALLOWED, DownloadPolicy.check(online = true, unmetered = false, mobileDataAllowed = true))
        assertEquals(DownloadCheck.NO_NETWORK, DownloadPolicy.check(online = false, unmetered = false, mobileDataAllowed = true))
    }
}
