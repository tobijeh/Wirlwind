package org.wirl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import java.io.File

/** The Whisper pieces that need the app: where models are kept, the downloader, and the network. */
object WhisperApp {
    fun store(context: Context): ModelStore = ModelStore(File(context.filesDir, "whisper"))

    fun downloader(context: Context): ModelDownloader = ModelDownloader(OkHttpClient(), store(context))

    private fun capabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
    }

    fun isOnline(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** True on Wi-Fi or Ethernet, which aren't metered. Mobile data is metered. */
    fun isUnmetered(context: Context): Boolean {
        val caps = capabilities(context) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
