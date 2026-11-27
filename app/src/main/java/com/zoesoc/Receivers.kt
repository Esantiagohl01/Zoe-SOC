package com.zoesoc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews

/** Widget de pantalla de inicio (si el launcher del Carlinkit los soporta). */
class SocWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        push(ctx)
    }

    companion object {
        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SocWidget::class.java))
            if (ids.isEmpty()) return
            val d = ZoeState.data
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.wSoc, Fmt.soc(d))
            val v = d?.displaySoc
            rv.setTextColor(
                R.id.wSoc,
                when {
                    v == null -> Color.WHITE
                    v < 20 -> 0xFFFF5252.toInt()
                    v < 30 -> 0xFFFFC107.toInt()
                    else -> 0xFF69F0AE.toInt()
                },
            )
            val st = ZoeState.status
            rv.setTextViewText(R.id.wSub, if (st == "OK") Fmt.short(d) else st.take(28))
            val pi = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.wRoot, pi)
            mgr.updateAppWidget(ids, rv)
        }
    }
}

/** Arranca el servicio al encender la caja. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (p.autostart && p.address != null) {
            try {
                ZoeService.start(ctx)
            } catch (e: Exception) {
                Log.w("ZoeSOC", "No pude arrancar al encender: ${e.message}")
            }
        }
    }
}
