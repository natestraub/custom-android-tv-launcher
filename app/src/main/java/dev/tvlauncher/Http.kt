package dev.tvlauncher

import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTP GETs for weather and Continue Watching art. Blocking; off the main thread only. */
internal object Http {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 15_000

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (responseCode != HttpURLConnection.HTTP_OK) {
                disconnect()
                throw java.io.IOException("HTTP $responseCode for $url")
            }
        }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
