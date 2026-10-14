package org.wirl

import java.util.concurrent.ConcurrentHashMap

/** What Settings shows for one speech model. */
sealed interface ModelState {
    data object NotDownloaded : ModelState
    /** Some of the file is on disk. Resume continues it. */
    data class Partial(val percent: Int) : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
}

/** Downloads in progress, shared across screens, so reopening Settings still shows them. */
object ModelDownloads {
    private val progress = ConcurrentHashMap<WhisperModel, Int>()

    fun start(model: WhisperModel) {
        progress[model] = 0
    }

    /** Records progress. Returns true if the whole-number percentage changed, so the screen only redraws then. */
    fun update(model: WhisperModel, percent: Int): Boolean = progress.put(model, percent) != percent

    fun finish(model: WhisperModel) {
        progress.remove(model)
    }

    fun percentOf(model: WhisperModel): Int? = progress[model]

    fun isDownloading(model: WhisperModel): Boolean = progress.containsKey(model)

    fun clear() = progress.clear()
}

object ModelStatus {
    /** The status of [model] in [store]. Ready wins, then a download in progress, then a partial file. */
    fun of(store: ModelStore, model: WhisperModel): ModelState = of(
        ready = store.isReady(model.spec),
        downloadingPercent = ModelDownloads.percentOf(model),
        partialBytes = store.partialFor(model.spec).takeIf { it.isFile }?.length() ?: 0L,
        sizeBytes = model.sizeBytes,
    )

    fun of(ready: Boolean, downloadingPercent: Int?, partialBytes: Long, sizeBytes: Long): ModelState = when {
        ready -> ModelState.Ready
        downloadingPercent != null -> ModelState.Downloading(downloadingPercent)
        partialBytes > 0 -> ModelState.Partial(percent(partialBytes, sizeBytes))
        else -> ModelState.NotDownloaded
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
}

/** Whether a download may start now, given the network and the user's mobile data setting. */
enum class DownloadCheck { ALLOWED, NO_NETWORK, NEEDS_MOBILE_DATA }

object DownloadPolicy {
    /**
     * Downloads run on an unmetered connection (Wi-Fi or Ethernet). A metered connection, which is mobile data, is used only
     * when the user has allowed it in Settings.
     */
    fun check(online: Boolean, unmetered: Boolean, mobileDataAllowed: Boolean): DownloadCheck = when {
        !online -> DownloadCheck.NO_NETWORK
        unmetered || mobileDataAllowed -> DownloadCheck.ALLOWED
        else -> DownloadCheck.NEEDS_MOBILE_DATA
    }
}
