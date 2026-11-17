package app.remotekeyboard

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

const val PORT = 8080

object Pin {
    private const val PREFS = "rk"
    private const val KEY = "pin"

    fun get(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY, null) ?: regenerate(ctx)
    }

    fun regenerate(ctx: Context): String {
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pin).apply()
        return pin
    }
}

object Net {
    /** Private IPv4 addresses of this device (Wi-Fi, hotspot, ethernet). */
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!! }
    } catch (e: Exception) {
        emptyList()
    }
}
