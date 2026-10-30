package com.qishimeng.game.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyConsentStoreTest {
    @Test
    fun autoPrivacyShowsUntilUserAgreesAndThenPersists() {
        val store = PrivacyConsentStore(InMemoryPrivacyPreferences())
        assertTrue(store.shouldShowAgreement(privacyAuto = true))
        store.agree()
        assertFalse(store.shouldShowAgreement(privacyAuto = true))
        assertFalse(store.shouldShowAgreement(privacyAuto = false))
    }

    @Test
    fun privacyAutoFalseSavesConsentAndRejectRevokesIt() {
        val store = PrivacyConsentStore(InMemoryPrivacyPreferences())
        assertFalse(store.shouldShowAgreement(privacyAuto = false))
        assertTrue(store.isAgreed())
        store.reject()
        assertFalse(store.isAgreed())
        assertTrue(store.shouldShowAgreement(privacyAuto = true))
    }
}
