package com.qishimeng.game.floatball

import androidx.appcompat.app.AppCompatActivity
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.Glide
import com.qishimeng.game.config.FloatingBallConfig
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/** 使用真实多帧 GIF 与本地 HTTP，避免把静态首帧或图片库 mock 当作动画通过。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class FloatingBallImageTest {
    private lateinit var activity: ActivityController<AppCompatActivity>
    private lateinit var host: FrameLayout
    private lateinit var ball: FloatingBallView
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool()

    @Before fun setUp() {
        server = ServerSocket(0)
        val gif = javaClass.getResourceAsStream("/floatball-active.gif")!!.use { it.readBytes() }
        val png = javaClass.getResourceAsStream("/floatball-default.png")!!.use { it.readBytes() }
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute {
                    runCatching { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val active = reader.readLine().split(' ').getOrNull(1) == "/active"
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val bytes = if (active) gif else png
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: ${if (active) "image/gif" else "image/png"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    } }
                }
            }
        }
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().visible()
        host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        host.layout(0, 0, 600, 400)
        val base = "http://127.0.0.1:${server.localPort}"
        ball = FloatingBallView(activity.get(), host,
            FloatingBallConfig(true, "$base/default", "$base/active", 20, 20, null, 0, 0.0, null)) {}
    }

    @After fun tearDown() {
        ball.destroy()
        activity.pause().stop().destroy()
        Glide.tearDown()
        server.close()
        workers.shutdownNow()
    }

    @Test fun activeGifDecodesMultipleFramesAndActuallyAdvances() {
        ball.setActive(true)
        val image = host.getChildAt(0) as ImageView
        await { image.drawable != null }
        assertTrue("激活 GIF 必须保留动画，而非静态 Bitmap", image.drawable is GifDrawable)
        val gif = image.drawable as GifDrawable
        assertEquals(2, gif.frameCount)
        await { gif.frameIndex == 1 }
        assertTrue(gif.isRunning)
        ball.setActive(true)
        assertSame("相同激活状态不得重新加载图标", gif, image.drawable)
    }

    @Test fun hiddenGifStopsAndShowingRestartsWithoutReloadThenDestroyStopsIt() {
        ball.setActive(true)
        val image = host.getChildAt(0) as ImageView
        await { image.drawable != null }
        assertTrue(image.drawable is GifDrawable)
        val gif = image.drawable as GifDrawable
        activity.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("后台期间停止动画", gif.isRunning)
        ball.show(true)
        assertFalse("后台心跳更新显隐不能重启动画", gif.isRunning)
        activity.restart().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("返回前台恢复可见 GIF：drawableVisible=${gif.isVisible}, viewShown=${image.isShown}, size=${image.width}x${image.height}", gif.isRunning)
        ball.show(false)
        assertFalse("隐藏期间停止动画", gif.isRunning)
        activity.pause().stop()
        activity.restart().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("后台返回时不能让隐藏图标恢复播放", gif.isRunning)
        ball.show(true)
        assertSame(gif, image.drawable)
        await { gif.isRunning }
        assertTrue("重新显示时恢复动画", gif.isRunning)
        ball.destroy()
        assertFalse("销毁后停止动画", gif.isRunning)
        assertEquals(0, host.childCount)
    }

    @Test fun switchingToDefaultPngStopsPreviousGif() {
        val image = host.getChildAt(0) as ImageView
        await { image.drawable is BitmapDrawable }
        ball.setActive(true)
        await { image.drawable != null }
        assertTrue(image.drawable is GifDrawable)
        val previous = image.drawable as GifDrawable
        ball.setActive(false)
        await { image.drawable is BitmapDrawable }
        assertFalse("切回 PNG 后旧 GIF 必须停止", previous.isRunning)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        val image = host.getChildAt(0) as? ImageView
        val gif = image?.drawable as? GifDrawable
        fail("等待 GIF 解码或动画帧超时：frame=${gif?.frameIndex}, running=${gif?.isRunning}, drawableVisible=${gif?.isVisible}, viewShown=${image?.isShown}, size=${image?.width}x${image?.height}")
    }
}
