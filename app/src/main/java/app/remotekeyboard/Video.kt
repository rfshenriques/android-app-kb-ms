package app.remotekeyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/** Somewhere video frames go: one connected iPhone. */
interface Sink {
    val w: Int
    val q: Int
    val fps: Int
    /** Must never block: drop the frame if the phone is behind. */
    fun offer(jpeg: ByteArray)
}

/**
 * Decides where frames come from and keeps the work to a minimum:
 *  - nobody watching: nothing runs
 *  - screen recording allowed (ScreenCaptureService): smooth stream
 *  - otherwise: a screenshot about twice a second (accessibility)
 */
object Video {
    private val sinks = CopyOnWriteArraySet<Sink>()
    private val main = Handler(Looper.getMainLooper())
    private var fallbackOn = false

    fun add(s: Sink) { sinks.add(s); refresh() }
    fun remove(s: Sink) { sinks.remove(s); refresh() }
    fun dispatch(jpeg: ByteArray) { for (s in sinks) s.offer(jpeg) }

    private fun wanted(): IntArray? =
        if (sinks.isEmpty()) null else intArrayOf(sinks.maxOf { it.w }, sinks.maxOf { it.q }, sinks.maxOf { it.fps })

    /** Call whenever the viewers or their settings change. */
    fun refresh() {
        main.post {
            val p = wanted()
            val proj = ScreenCaptureService.instance
            when {
                p == null -> { proj?.stopStreaming(); fallbackOn = false }
                proj != null && proj.ready -> { fallbackOn = false; proj.startStreaming(p[0], p[1], p[2]) }
                else -> { proj?.stopStreaming(); if (!fallbackOn) { fallbackOn = true; main.post(tick) } }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = wanted()
            if (!fallbackOn || p == null) { fallbackOn = false; return }
            val svc = CursorService.instance
            if (svc == null) { main.postDelayed(this, 1500); return }       // mouse service off: nothing to capture with
            val t0 = SystemClock.uptimeMillis()
            svc.capture(p[0], p[1]) { jpeg ->
                if (jpeg != null) dispatch(jpeg)
                val wait = if (jpeg == null) 800L else maxOf(0L, 420L - (SystemClock.uptimeMillis() - t0))
                main.postDelayed(this, wait)
            }
        }
    }
}
