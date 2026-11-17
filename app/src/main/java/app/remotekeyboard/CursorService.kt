package app.remotekeyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Draws a mouse cursor overlay and turns trackpad messages into gestures.
 * Android has no non-root API for a real pointer, so:
 *   click  -> short tap at the cursor
 *   drag   -> a touch stroke that follows the cursor (down ... up)
 *   scroll -> a touch stroke moved by the scroll deltas (like a finger swipe)
 *   right  -> Back
 * Everything runs on the main thread.
 */
class CursorService : AccessibilityService() {

    companion object {
        @Volatile var instance: CursorService? = null
    }

    private enum class Mode { NONE, DRAG, SCROLL }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursor: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var x = 0f          // logical cursor position (used for clicks and gestures)
    private var y = 0f
    private var vx = 0f         // displayed position, eases toward x/y once per frame
    private var vy = 0f
    private var animating = false
    private val frame = Choreographer.FrameCallback {
        animating = false
        vx += (x - vx) * 0.6f
        vy += (y - vy) * 0.6f
        if (Math.abs(x - vx) < 0.4f && Math.abs(y - vy) < 0.4f) { vx = x; vy = y } else scheduleFrame()
        placeCursor()
    }
    private fun scheduleFrame() {
        if (!animating) { animating = true; Choreographer.getInstance().postFrameCallback(frame) }
    }
    private var screenW = 1
    private var screenH = 1
    private var density = 1f

    private val finger = Finger()
    private var mode = Mode.NONE
    private var sx = 0f // scroll finger position
    private var sy = 0f
    private val endScroll = Runnable {
        if (mode == Mode.SCROLL) { finger.end(); mode = Mode.NONE }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        updateScreen()
        x = screenW / 2f
        y = screenH / 2f
        vx = x; vy = y
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideCursor()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideCursor()
        instance = null
        super.onDestroy()
    }

    // ---- public API (main thread) ---------------------------------------------

    fun setCursorVisible(on: Boolean) = if (on) showCursor() else hideCursor()

    fun moveBy(dx: Float, dy: Float) {
        updateScreen()
        x = (x + dx).coerceIn(0f, screenW - 1f)
        y = (y + dy).coerceIn(0f, screenH - 1f)
        showCursor()
        scheduleFrame()          // one layout update per display frame, however fast messages arrive
        if (mode == Mode.DRAG) finger.moveTo(x, y)
    }

    fun click() {
        if (finger.active) return
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
        dispatchGesture(g, null, null)
    }

    fun rightClick() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun down() {
        if (finger.active) return
        mode = Mode.DRAG
        finger.begin(x, y)
    }

    /** Press and hold at the cursor: a long press (context menu); moving afterwards drags. */
    fun hold() {
        if (finger.active) return
        mode = Mode.DRAG
        finger.begin(x, y, 650)
    }

    fun up() {
        if (mode == Mode.DRAG) { finger.end(); mode = Mode.NONE }
    }

    /** dx/dy: how far the virtual finger should move (content follows the finger). */
    fun scroll(dx: Float, dy: Float) {
        if (mode == Mode.DRAG) return
        updateScreen()
        if (mode == Mode.NONE) {
            if (finger.active) return
            mode = Mode.SCROLL
            sx = x; sy = y
            finger.begin(sx, sy)
        }
        val m = 12f * density
        val nx = sx + dx
        val ny = sy + dy
        if (nx < m || nx > screenW - m || ny < m || ny > screenH - m) {
            // finger would leave the screen: lift it, the next scroll restarts at the cursor
            main.removeCallbacks(endScroll)
            finger.end(); mode = Mode.NONE
            return
        }
        sx = nx; sy = ny
        finger.moveTo(sx, sy)
        main.removeCallbacks(endScroll)
        main.postDelayed(endScroll, 120)
    }

    // ---- cursor overlay -------------------------------------------------------

    private fun updateScreen() {
        val w = wm ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val b = w.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }

    private fun showCursor() {
        if (cursor != null) return
        val v = CursorView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        lp = params
        cursor = v
        vx = x; vy = y
        placeCursor()
        try { wm?.addView(v, params) } catch (e: Exception) { cursor = null }
    }

    private fun hideCursor() {
        Choreographer.getInstance().removeFrameCallback(frame)
        animating = false
        cursor?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
        cursor = null
    }

    private fun placeCursor() {
        val v = cursor ?: return
        val p = lp ?: return
        p.x = (vx - density).toInt()
        p.y = (vy - density).toInt()
        try { wm?.updateViewLayout(v, p) } catch (e: Exception) {}
    }

    private class CursorView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path().apply {
            moveTo(1 * d, 1 * d); lineTo(1 * d, 19 * d); lineTo(5.5f * d, 14.8f * d); lineTo(9 * d, 22 * d)
            lineTo(12 * d, 20.6f * d); lineTo(8.6f * d, 13.6f * d); lineTo(14.5f * d, 13.2f * d); close()
        }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((20 * d).toInt(), (28 * d).toInt())
        override fun onDraw(c: Canvas) { c.drawPath(arrow, fill); c.drawPath(arrow, edge) }
    }

    // ---- one continuous touch stroke -------------------------------------------

    /**
     * A single virtual finger. begin() puts it down, moveTo() queues a target (only the
     * latest target is kept while a gesture is in flight), end() lifts it.
     */
    private inner class Finger {
        var active = false
            private set
        private var stroke: GestureDescription.StrokeDescription? = null
        private val last = PointF()
        private var pending: PointF? = null
        private var busy = false
        private var ending = false

        fun begin(px: Float, py: Float, holdMs: Long = 1) {
            if (active) return
            active = true; ending = false; pending = null; stroke = null
            last.set(px, py)
            val path = Path().apply { moveTo(px, py) }
            dispatch(GestureDescription.StrokeDescription(path, 0, holdMs, true), final = false)
        }

        fun moveTo(px: Float, py: Float) {
            if (!active || ending) return
            pending = PointF(px, py)
            pump()
        }

        fun end() {
            if (!active || ending) return
            ending = true
            pump()
        }

        private fun pump() {
            val s = stroke ?: return
            if (busy) return
            val to = pending ?: (if (ending) PointF(last.x, last.y) else return)
            val path = Path().apply { moveTo(last.x, last.y); lineTo(to.x, to.y) }
            val next = s.continueStroke(path, 0, 10, !ending)
            last.set(to)
            pending = null
            dispatch(next, final = ending)
        }

        private fun dispatch(s: GestureDescription.StrokeDescription, final: Boolean) {
            busy = true
            val g = GestureDescription.Builder().addStroke(s).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    busy = false
                    if (final) reset() else { stroke = s; pump() }
                }
                override fun onCancelled(d: GestureDescription?) { reset() }
            }, null)
            if (!ok) reset()
        }

        private fun reset() {
            active = false; busy = false; ending = false; stroke = null; pending = null
            mode = Mode.NONE
        }
    }
}
