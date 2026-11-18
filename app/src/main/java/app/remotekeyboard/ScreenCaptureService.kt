package app.remotekeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Screen recording for the iPhone's live view. It only works while an iPhone is watching, runs on its own
 * background-priority thread (so typing and the trackpad are never made to wait for it), and drops frames
 * instead of queueing them. When the screen does not change, Android delivers no frames and this uses no CPU.
 */
class ScreenCaptureService : Service() {

    companion object {
        @Volatile var instance: ScreenCaptureService? = null
        private const val CHANNEL = "screen"
        const val ACTION_STOP = "app.remotekeyboard.STOP_SHARING"
    }

    @Volatile private var projection: MediaProjection? = null
    val ready get() = projection != null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var params = intArrayOf(0, 0, 0)
    private var lastFrameAt = 0L
    private var bitmap: Bitmap? = null
    private val out = ByteArrayOutputStream(96 * 1024)

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY || display == null) return
            val p = params.copyOf()
            handler?.post { stopStreamingInternal(); startStreamingInternal(p[0], p[1], p[2]) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        startForegroundCompat()
        instance = this
        thread = HandlerThread("rk-capture", Process.THREAD_PRIORITY_BACKGROUND + 2).also { it.start() }
        handler = Handler(thread!!.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { handler?.post { stopStreamingInternal() }; projection = null; stopSelf() }
            }, handler)
        }
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(rotationListener, handler)
        SplitLog.d("screen sharing allowed")
        Video.refresh()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Remote Keyboard: screen sharing is allowed")
            .setContentText("Your iPhone can show this screen live while it is open.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---- streaming (only while someone watches) --------------------------------------

    fun startStreaming(w: Int, q: Int, fps: Int) {
        handler?.post {
            if (display != null && params[0] == w && params[1] == q && params[2] == fps) return@post
            stopStreamingInternal()
            startStreamingInternal(w, q, fps)
        }
    }

    fun stopStreaming() { handler?.post { stopStreamingInternal() } }

    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun startStreamingInternal(w: Int, q: Int, fps: Int) {
        val proj = projection ?: return
        params = intArrayOf(w, q, fps)
        val real = realSize()
        // width a multiple of 16 so every row is tightly packed (no padding to crop)
        val width = (w.coerceIn(320, 1280) / 16) * 16
        val height = ((real[1].toFloat() / real[0] * width).toInt() / 2) * 2
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "rk-view", width, height, real[2], DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        r.setOnImageAvailableListener({ onFrame(it, q, fps) }, handler)
        lastFrameAt = 0
        SplitLog.d("streaming ${width}x$height q=$q fps=$fps")
    }

    private fun stopStreamingInternal() {
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        display = null; reader = null
        bitmap?.recycle(); bitmap = null
    }

    private fun onFrame(r: ImageReader, q: Int, fps: Int) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return   // latest only: stale frames are dropped
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < 1000L / fps.coerceIn(1, 30)) return                  // frame-rate cap
            lastFrameAt = now
            val plane = img.planes[0]
            val padW = plane.rowStride / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != padW || bmp.height != img.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(padW, img.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp!!.copyPixelsFromBuffer(plane.buffer)
            out.reset()
            val src = if (padW != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
            src.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (src !== bmp) src.recycle()
            Video.dispatch(out.toByteArray())
        } catch (e: Exception) {
            // skip this frame
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(rotationListener) } catch (e: Exception) {}
        stopStreamingInternal()
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
        thread?.quitSafely()
        instance = null
        Video.refresh()
        super.onDestroy()
    }
}

/** Tiny logger so the service has no dependency on the UI. */
object SplitLog {
    fun d(s: String) = android.util.Log.d("RemoteKeyboard", s)
}
