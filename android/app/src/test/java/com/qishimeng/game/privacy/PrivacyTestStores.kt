package com.qishimeng.game.privacy

internal class InMemoryPrivacyPreferences : PrivacyPreferences {
    override var agreed: Boolean = false
}

internal class InMemoryDeviceCodePersistence : DeviceCodePersistence {
    override var deviceCode: String? = null
}
