package app.remotekeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Input method that hosts a tiny HTTP + WebSocket server. The iPhone opens the
 * served keyboard page (a PWA) and sends key events, which are injected into
 * whatever text field is focused on this device.
 */
class KeyboardService : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private var server: KbServer? = null
    private var statusView: TextView? = null

    companion object {
        @Volatile var running = false
        val clients = AtomicInteger(0)
    }

    // brute-force protection for the PIN
    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            server = KbServer(PORT).also { it.start(0, true) } // 0 = no socket read timeout
        } catch (e: IOException) {
            server = null
        }
        running = server != null
        refreshStatus()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        clients.set(0)
        Setup.refreshWidgets(this)
        super.onDestroy()
    }

    /** Show the (small) status bar even when a hardware keyboard is attached. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE8EAF0.toInt())
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        statusView = text
        val switchBtn = Button(this).apply {
            this.text = "Switch keyboard"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_btn)
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        refreshStatus()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1D24.toInt())
            setPadding(0, 0, (10 * dp).toInt(), 0)
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchBtn)
        }
    }

    private fun refreshStatus() {
        main.post {
            val ips = Net.addresses()
            val where = if (ips.isEmpty()) "no network (turn on Wi-Fi or a hotspot)"
            else ips.joinToString("  |  ") { "http://$it:$PORT" }
            val state = if (server == null) "server failed to start" else "${clients.get()} connected"
            val mouse = if (CursorService.instance != null) "mouse on" else "mouse off"
            statusView?.text = "Remote keyboard: $state, $mouse\nOpen on iPhone: $where   PIN: ${Pin.get(this)}"
            Setup.refreshWidgets(this)
        }
    }

    // ---- key injection (always called on the main thread) -------------------

    private fun handle(msg: JSONObject) {
        val type = msg.optString("t")
        if (handleMouse(type, msg)) return
        val ic = currentInputConnection ?: return // no focused text field
        when (type) {
            "text" -> ic.commitText(msg.optString("v"), 1)
            "key" -> sendKey(ic, msg.optString("k"), msg.optJSONArray("m")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList())
        }
    }

    /** Trackpad messages go to the accessibility service. Returns true if the message was a mouse one. */
    private fun handleMouse(type: String, msg: JSONObject): Boolean {
        when (type) {
            "move", "click", "rclick", "hold", "down", "up", "scroll", "cursor" -> {}
            else -> return false
        }
        val m = CursorService.instance ?: return true
        val dx = msg.optDouble("dx", 0.0).toFloat().coerceIn(-400f, 400f)
        val dy = msg.optDouble("dy", 0.0).toFloat().coerceIn(-400f, 400f)
        when (type) {
            "move" -> m.moveBy(dx, dy)
            "click" -> m.click()
            "rclick" -> m.rightClick()
            "hold" -> m.hold()
            "down" -> m.down()
            "up" -> m.up()
            "scroll" -> m.scroll(dx, dy)
            "cursor" -> m.setCursorVisible(msg.optBoolean("on", true))
        }
        return true
    }

    private fun sendKey(ic: InputConnection, name: String, mods: List<String>) {
        val code = keyCode(name) ?: return
        var meta = 0
        if ("shift" in mods) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if ("ctrl" in mods) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if ("alt" in mods) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if ("meta" in mods) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun keyCode(name: String): Int? = when (name.uppercase()) {
        "ENTER" -> KeyEvent.KEYCODE_ENTER
        "BACKSPACE" -> KeyEvent.KEYCODE_DEL
        "DELETE" -> KeyEvent.KEYCODE_FORWARD_DEL
        "TAB" -> KeyEvent.KEYCODE_TAB
        "ESC" -> KeyEvent.KEYCODE_ESCAPE
        "SPACE" -> KeyEvent.KEYCODE_SPACE
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
        "END" -> KeyEvent.KEYCODE_MOVE_END
        "PGUP" -> KeyEvent.KEYCODE_PAGE_UP
        "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> if (name.length == 1 && name[0].isLetterOrDigit()) {
            KeyEvent.keyCodeFromString("KEYCODE_" + name.uppercase()).takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
        } else null
    }

    // ---- server ---------------------------------------------------------------

    private inner class KbServer(port: Int) : NanoWSD(port) {

        override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = Client(handshake)

        override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = when (session.uri) {
            "/", "/index.html" -> asset("keyboard.html", "text/html; charset=utf-8")
            "/manifest.webmanifest" -> asset("manifest.webmanifest", "application/manifest+json")
            "/icon.png" -> asset("icon.png", "image/png")
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found"
            )
        }

        private fun asset(name: String, mime: String): NanoHTTPD.Response = try {
            val bytes = assets.open(name).use { it.readBytes() }
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()
            ).also { it.addHeader("Cache-Control", "no-store") }
        } catch (e: IOException) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Missing asset"
            )
        }
    }

    private inner class Client(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        @Volatile private var authed = false

        override fun onOpen() {}

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            if (authed) {
                authed = false
                if (clients.decrementAndGet() <= 0) main.post { CursorService.instance?.setCursorVisible(false) }
                refreshStatus()
            }
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            val msg = try { JSONObject(message.textPayload) } catch (e: Exception) { return }
            when (msg.optString("t")) {
                "ping" -> reply("""{"t":"pong"}""")
                "auth" -> authenticate(msg.optString("pin"))
                "cursor" -> if (authed) {
                    reply("""{"t":"mouse","ok":${CursorService.instance != null}}""")
                    main.post { handle(msg) }
                }
                else -> if (authed) main.post { handle(msg) }
            }
        }

        private fun authenticate(pin: String) {
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) {
                reply("""{"t":"auth","ok":false,"reason":"locked"}""")
                return
            }
            val ok = MessageDigest.isEqual(pin.toByteArray(), Pin.get(this@KeyboardService).toByteArray())
            if (ok) {
                failures = 0
                if (!authed) {
                    authed = true
                    clients.incrementAndGet()
                    refreshStatus()
                }
                reply("""{"t":"auth","ok":true}""")
            } else {
                if (++failures >= 5) {
                    failures = 0
                    lockedUntil = now + 60_000
                }
                reply("""{"t":"auth","ok":false,"reason":"pin"}""")
            }
        }

        private fun reply(json: String) {
            try { send(json) } catch (e: IOException) { /* client gone */ }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
