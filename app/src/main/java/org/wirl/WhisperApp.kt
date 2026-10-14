package org.wirl

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/** The Whisper pieces that need the app: where models are kept, and the downloader. */
object WhisperApp {
    fun store(context: Context): ModelStore = ModelStore(File(context.filesDir, "whisper"))

    fun downloader(context: Context): ModelDownloader = ModelDownloader(OkHttpClient(), store(context))
}
