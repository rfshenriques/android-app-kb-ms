package app.remotekeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Invisible activity the widget button opens. Does the next setup step, then closes itself. */
class ActionActivity : Activity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // The keyboard picker can only be shown once the window has focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        when (intent.getStringExtra("do")) {
            "activate" -> {
                val s = Setup.read(this)
                if (!s.enabled) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    finish()
                } else if (Setup.activate(this)) {
                    Setup.refreshWidgets(this)
                    finish()
                } else {
                    picker()
                }
            }
            "restore" -> if (Setup.restore(this)) { Setup.refreshWidgets(this); finish() } else picker()
            else -> finish()
        }
    }

    private fun picker() {
        Setup.imm(this).showInputMethodPicker()
        window.decorView.postDelayed({ finish() }, 400)
    }
}
