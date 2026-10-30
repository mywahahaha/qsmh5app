package com.qishimeng.game.splash

import org.junit.Assert.*
import org.junit.Test

class SplashSelectorTest {
    private val assets = listOf(
        SplashAsset(1, 1280, 768), SplashAsset(2, 1624, 750),
        SplashAsset(3, 2208, 1242), SplashAsset(4, 750, 1334)
    )

    @Test fun choosesMatchingOrientationAndClosestAspectRatio() {
        assertEquals(2, SplashSelector.select(assets, 2400, 1080)?.resourceId)
        assertEquals(3, SplashSelector.select(assets, 1920, 1080)?.resourceId)
        assertEquals(4, SplashSelector.select(assets, 1080, 2400)?.resourceId)
    }

    @Test fun prefersHigherResolutionWhenAspectRatiosMatch() {
        assertEquals(2, SplashSelector.select(listOf(
            SplashAsset(1, 1280, 720), SplashAsset(2, 1920, 1080)
        ), 1920, 1080)?.resourceId)
    }

    @Test fun fallsBackToAvailableOrientationAndHandlesMissingResources() {
        assertEquals(4, SplashSelector.select(assets.takeLast(1), 1920, 1080)?.resourceId)
        assertNull(SplashSelector.select(emptyList(), 1920, 1080))
        assertNull(SplashSelector.select(assets, 0, 1080))
    }

    @Test fun readsMappedResourceNamesAndRejectsInvalidDimensions() {
        assertEquals(SplashAsset(5, 1624, 750), SplashAsset.fromResourceName(5, "splash_landscape_1624x750"))
        assertNull(SplashAsset.fromResourceName(5, "ic_launcher"))
        assertNull(SplashAsset.fromResourceName(5, "splash_portrait_0x1334"))
    }

    @Test fun keepsFastStartupVisibleAndBoundsSlowStartup() {
        assertEquals(900L, SplashDisplayPolicy.delayUntilDismiss(300L, contentReady = true))
        assertEquals(0L, SplashDisplayPolicy.delayUntilDismiss(2000L, contentReady = true))
        assertEquals(3000L, SplashDisplayPolicy.delayUntilDismiss(2000L, contentReady = false))
        assertEquals(0L, SplashDisplayPolicy.delayUntilDismiss(6000L, contentReady = false))
    }
}
