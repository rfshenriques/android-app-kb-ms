package app.remotekeyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

/** Guided setup: each step shows a check mark when it is done. Refreshes every second. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun tv(id: Int) = findViewById<TextView>(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tv(R.id.btn1).setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        tv(R.id.btn2).setOnClickListener {
            if (!Setup.read(this).enabled) {
                toast("Do step 1 first: enable the keyboard")
            } else if (!Setup.activate(this)) {
                Setup.imm(this).showInputMethodPicker()
            }
        }
        tv(R.id.btn3).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        tv(R.id.btn3b).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        tv(R.id.btnCopy).setOnClickListener {
            val a = Net.addresses().firstOrNull()
            if (a == null) toast("No network found") else { copy("http://$a:$PORT"); toast("Address copied") }
        }
        tv(R.id.btnPin).setOnClickListener { Pin.regenerate(this); refresh(); Setup.refreshWidgets(this) }
        tv(R.id.btnCmd).setOnClickListener {
            copy("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"); toast("Command copied")
        }
        tv(R.id.btn6).setOnClickListener {
            val sharing = ScreenCaptureService.instance?.ready == true
            if (sharing) {
                startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 7)
            }
        }
        tv(R.id.desc5).text = tv(R.id.desc5).text.toString().replace("PKG", packageName)
    }

    @Deprecated("kept simple")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ScreenCaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
        } else if (requestCode == 7) {
            toast("Screen sharing was not allowed")
        }
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Remote Keyboard", text))
    }

    private fun badge(n: Int, done: Boolean, id: Int) {
        val b = tv(id)
        b.text = if (done) "✓" else "$n"
        b.setBackgroundResource(if (done) R.drawable.badge_ok else R.drawable.badge_off)
    }

    private fun refresh() {
        val s = Setup.read(this)
        badge(1, s.enabled, R.id.badge1)
        badge(2, s.selected, R.id.badge2)
        badge(3, s.mouse, R.id.badge3)
        badge(4, s.clients > 0, R.id.badge4)
        badge(5, s.canSwitch, R.id.badge5)
        val sharing = ScreenCaptureService.instance?.ready == true
        badge(6, sharing, R.id.badge6)
        tv(R.id.btn6).text = if (sharing) "Stop screen sharing" else "Allow screen sharing"

        tv(R.id.btn2).text = if (s.selected) "Remote Keyboard is active" else "Choose keyboard"
        tv(R.id.btn3).text = if (s.mouse) "Mouse is on" else "Open accessibility settings"
        tv(R.id.title3).text = if (s.mouse) "Mouse is on" else "Enable the mouse (optional)"

        val addr = if (!s.server) "Finish steps 1 and 2 first (the server runs while the keyboard is active)"
        else if (s.addresses.isEmpty()) "No network: join Wi-Fi or turn on a hotspot"
        else s.addresses.joinToString("\n") { "http://$it:$PORT" }
        tv(R.id.addr).text = addr
        tv(R.id.pin).text = s.pin
        tv(R.id.title4).text = if (s.clients > 0) "iPhone connected" else "Connect your iPhone"

        val summary = when {
            !s.enabled -> "Step 1: enable the keyboard"
            !s.selected -> "Step 2: choose it as the active keyboard"
            s.clients > 0 -> "iPhone connected"
            else -> "Ready. Open the address on your iPhone"
        }
        tv(R.id.summary).text = summary
        tv(R.id.summary).setTextColor(getColor(if (s.enabled && s.selected) R.color.ok else R.color.muted))
    }
}
