package com.qishimeng.game.auth

import java.util.concurrent.atomic.AtomicReference

/** SDK 为进程单例，授权页存续期间只能由一个认证会话持有。 */
internal class AuthSessionGate {
    private val owner = AtomicReference<Any?>(null)

    fun start(onBusy: () -> Unit, operation: (release: () -> Unit) -> Unit) {
        val session = Any()
        if (!owner.compareAndSet(null, session)) {
            onBusy()
            return
        }
        val release = { owner.compareAndSet(session, null); Unit }
        try {
            operation(release)
        } catch (exception: Exception) {
            release()
            throw exception
        }
    }
}
