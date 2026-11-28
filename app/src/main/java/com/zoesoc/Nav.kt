package com.zoesoc

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * PRUEBA de navegación (hoja 3). Todo vive en este archivo: si no sirve, se borra este archivo,
 * la entrada del manifest, la casilla en Mantenimiento y drawNav en DashView.
 *
 * Lee la notificación que publica Google Maps mientras navega (giro, distancia, calle, llegada).
 */
object NavState {
    @Volatile var title: String? = null
    @Volatile var text: String? = null
    @Volatile var sub: String? = null
    @Volatile var icon: Bitmap? = null
    @Volatile var time = 0L

    /** Hay una ruta activa (notificación vista hace menos de 2 min y no retirada). */
    val active: Boolean get() = title != null && System.currentTimeMillis() - time < 120_000L

    fun clear() { title = null; text = null; sub = null; icon = null; time = 0L }

    /** Km que faltan, si vienen en algún texto ("7,4 km"). */
    fun remainingKm(): Double? {
        val all = listOfNotNull(sub, text, title).joinToString(" ")
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*km""").findAll(all).lastOrNull() ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    fun permitted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, NavListener::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }
}

class NavListener : NotificationListenerService() {
    companion object {
        val PACKAGES = setOf(
            "com.google.android.apps.maps",          // Google Maps
            "com.google.android.apps.navlite",       // Navigation for Google Maps Go
            "com.google.android.apps.mapslite",      // Google Maps Go
        )
    }

    private var lastLog = ""

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        if (!Prefs(this).navPage) return
        val n = sbn.notification ?: return
        // Solo la notificación fija de navegación (no avisos sueltos de Maps).
        val nav = n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!nav) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim()
        val sub = (e.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: e.getCharSequence(Notification.EXTRA_INFO_TEXT))?.toString()?.trim()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        NavState.title = title?.takeIf { it.isNotBlank() } ?: text
        NavState.text = if (title.isNullOrBlank()) null else text
        NavState.sub = sub
        NavState.icon = bitmap(n)
        NavState.time = System.currentTimeMillis()
        // En el registro, para ajustar el formato si Maps lo manda distinto.
        val line = "Nav: [$title] [$text] [$sub]"
        if (line != lastLog) { lastLog = line; ZoeState.log(line.take(160)) }
        ZoeState.touch()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification ?: return
        val nav = n.category == Notification.CATEGORY_NAVIGATION || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!nav) return
        NavState.clear()
        ZoeState.log("Nav: ruta terminada")
        ZoeState.touch()
    }

    /** Flecha del giro: el ícono grande que pone Maps en la notificación. */
    private fun bitmap(n: Notification): Bitmap? = try {
        val ic: Icon? = n.getLargeIcon()
        val d = ic?.loadDrawable(this)
        (d as? BitmapDrawable)?.bitmap ?: d?.let {
            val w = it.intrinsicWidth.coerceIn(1, 256); val h = it.intrinsicHeight.coerceIn(1, 256)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b); it.setBounds(0, 0, w, h); it.draw(c)
            }
        }
    } catch (e: Exception) { null }
}
