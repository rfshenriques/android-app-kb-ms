package app.remotekeyboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

data class Status(
    val enabled: Boolean,      // keyboard switched on in the keyboard list
    val selected: Boolean,     // keyboard is the active one
    val mouse: Boolean,        // accessibility (mouse) service on
    val server: Boolean,       // web server running
    val clients: Int,          // connected iPhones
    val addresses: List<String>,
    val pin: String,
    val canSwitch: Boolean,    // WRITE_SECURE_SETTINGS granted: can switch keyboards silently
)

object Setup {
    private const val PREFS = "rk"
    private const val PREV = "prev_ime"

    fun imm(ctx: Context) = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    fun read(ctx: Context): Status {
        val pkg = ctx.packageName
        val cr = ctx.contentResolver
        val enabled = imm(ctx).enabledInputMethodList.any { it.packageName == pkg }
        val def = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val a11y = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return Status(
            enabled = enabled,
            selected = def.startsWith("$pkg/"),
            mouse = a11y.contains("$pkg/") || CursorService.instance != null,
            server = KeyboardService.running,
            clients = KeyboardService.clients.get(),
            addresses = Net.addresses(),
            pin = Pin.get(ctx),
            canSwitch = canSwitch(ctx),
        )
    }

    fun canSwitch(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Make Remote Keyboard the active keyboard. Returns false if the app has no permission to do it silently. */
    fun activate(ctx: Context): Boolean {
        if (!canSwitch(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (cur != null && !cur.startsWith(ctx.packageName + "/")) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREV, cur).apply()
            }
            val ours = ComponentName(ctx, KeyboardService::class.java).flattenToShortString()
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, ours)
        } catch (e: SecurityException) {
            false
        }
    }

    /** Go back to the keyboard that was active before activate(). */
    fun restore(ctx: Context): Boolean {
        if (!canSwitch(ctx)) return false
        val prev = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREV, null) ?: return false
        return try {
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, prev)
        } catch (e: SecurityException) {
            false
        }
    }

    fun refreshWidgets(ctx: Context) = RkWidget.refresh(ctx.applicationContext)
}
