package app.remotekeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Setup screen: enable the keyboard, select it, and see the address + PIN. */
class MainActivity : Activity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        info = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, pad) }

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        val intro = TextView(this).apply {
            textSize = 16f
            text = "1. Enable \"Remote Keyboard\" in the keyboard list.\n" +
                "2. Tap a text field, then choose it as the active keyboard.\n" +
                "3. For the trackpad/mouse, enable the \"Remote Keyboard mouse\" accessibility service.\n" +
                "   (Android 13+: if it is greyed out, open App info > menu > Allow restricted settings first.)\n" +
                "4. On the iPhone, open the address below in Safari and Add to Home Screen."
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(intro)
            addView(button("1. Enable keyboard") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addView(button("2. Choose keyboard") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })
            addView(button("3. Enable mouse (accessibility)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(info)
            addView(button("New PIN") {
                Pin.regenerate(this@MainActivity)
                refresh()
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ips = Net.addresses()
        val addr = if (ips.isEmpty()) "No network found. Join Wi-Fi or turn on a hotspot."
        else ips.joinToString("\n") { "http://$it:$PORT" }
        val mouse = if (CursorService.instance != null) "on" else "off (enable it for the trackpad)"
        info.text = "Address (iPhone):\n$addr\n\nPIN: ${Pin.get(this)}\nMouse service: $mouse"
    }
}
