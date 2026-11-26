package com.zoesoc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Último dato leído y estado de conexión, compartido entre el servicio, la burbuja, el widget y la app. */
object ZoeState {
    @Volatile var data: ZoeData? = null
        private set
    @Volatile var status: String = "Detenido"
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun publish(d: ZoeData?, s: String) {
        if (d != null) data = d
        status = s
        fire()
    }

    /** Vuelve a notificar sin cambiar nada (ej. al cambiar una opción). */
    fun touch() = fire()

    fun log(s: String) {
        Log.d("ZoeSOC", s)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 150) logLines.removeFirst()
        }
        fire()
    }

    fun logText(last: Int = 40): String = synchronized(logLines) { logLines.toList().takeLast(last).joinToString("\n") }

    private fun fire() {
        main.post { listeners.forEach { it() } }
    }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("zoe", Context.MODE_PRIVATE)

    var address: String?
        get() = p.getString("addr", null)
        set(v) = p.edit().putString("addr", v).apply()
    var deviceName: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()
    var overlay: Boolean
        get() = p.getBoolean("overlay", true)
        set(v) = p.edit().putBoolean("overlay", v).apply()
    var autostart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()
    var intervalSec: Int
        get() = p.getInt("interval", 15)
        set(v) = p.edit().putInt("interval", v).apply()
    var compact: Boolean
        get() = p.getBoolean("compact", false)
        set(v) = p.edit().putBoolean("compact", v).apply()
    var bubbleX: Int
        get() = p.getInt("bx", 40)
        set(v) = p.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = p.getInt("by", 120)
        set(v) = p.edit().putInt("by", v).apply()
}
