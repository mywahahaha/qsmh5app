package com.qishimeng.game.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

object StartupBackgroundLoader {
    fun load(url: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        val parsedUrl = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsedUrl.protocol !in setOf("http", "https")) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openConnection(parsedUrl).use { connection ->
            connection.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidthPx.coerceAtLeast(1),
                targetHeightPx.coerceAtLeast(1)
            )
        }
        return openConnection(parsedUrl).use { connection ->
            connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun openConnection(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }
}
