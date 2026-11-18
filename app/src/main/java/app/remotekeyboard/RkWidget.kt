package app.remotekeyboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: shows state, PIN and address, and one button for the next step. */
class RkWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, RkWidget::class.java))
            ids.forEach { m.updateAppWidget(it, build(ctx)) }
        }

        private fun build(ctx: Context): RemoteViews {
            val s = Setup.read(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            val live = s.selected && s.server
            v.setTextViewText(R.id.wState, when {
                !s.enabled -> "Not set up"
                !s.selected -> "Off"
                s.clients > 0 -> "iPhone connected" + if (s.mouse) " · mouse on" else ""
                else -> "Ready" + if (s.mouse) " · mouse on" else " · mouse off"
            })
            v.setTextViewText(R.id.wPin, if (live) s.pin else "PIN ------")
            v.setTextViewText(R.id.wAddr, when {
                !live -> "Tap Activate to start"
                s.addresses.isEmpty() -> "No network: join Wi-Fi or a hotspot"
                else -> "http://${s.addresses.first()}:$PORT"
            })

            val action = if (s.selected) "restore" else "activate"
            v.setTextViewText(R.id.wBtn, when {
                !s.enabled -> "Enable keyboard"
                s.selected -> "Switch back to my keyboard"
                else -> "Activate"
            })
            val act = Intent(ctx, ActionActivity::class.java)
                .putExtra("do", action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            v.setOnClickPendingIntent(R.id.wBtn, PendingIntent.getActivity(ctx, 1, act, flags))
            v.setOnClickPendingIntent(R.id.wRoot,
                PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), flags))
            return v
        }
    }
}
