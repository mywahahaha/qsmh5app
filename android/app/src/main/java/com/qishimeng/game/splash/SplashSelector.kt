package com.qishimeng.game.splash

import kotlin.math.abs
import kotlin.math.ln

data class SplashAsset(val resourceId: Int, val width: Int, val height: Int) {
    companion object {
        private val namePattern = Regex("splash_(?:landscape|portrait)_(\\d+)x(\\d+)")

        fun fromResourceName(resourceId: Int, name: String): SplashAsset? {
            val match = namePattern.matchEntire(name) ?: return null
            val width = match.groupValues[1].toIntOrNull() ?: return null
            val height = match.groupValues[2].toIntOrNull() ?: return null
            return if (resourceId != 0 && width > 0 && height > 0) SplashAsset(resourceId, width, height) else null
        }
    }
}

object SplashSelector {
    fun select(assets: List<SplashAsset>, width: Int, height: Int): SplashAsset? {
        if (width <= 0 || height <= 0) return null
        val valid = assets.filter { it.width > 0 && it.height > 0 }
        val matching = valid.filter { (it.width > it.height) == (width > height) }
        val ratio = width.toDouble() / height
        return matching.ifEmpty { valid }.minWithOrNull(
            compareBy<SplashAsset> { abs(ln((it.width.toDouble() / it.height) / ratio)) }
                .thenByDescending { it.width.toLong() * it.height }
        )
    }
}

object SplashDisplayPolicy {
    fun delayUntilDismiss(elapsedMillis: Long, contentReady: Boolean): Long =
        ((if (contentReady) 1_200L else 5_000L) - elapsedMillis).coerceAtLeast(0L)
}
