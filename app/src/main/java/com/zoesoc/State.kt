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

    /** true mientras la pantalla de la app está abierta: activa las lecturas rápidas (velocidad, potencia). */
    @Volatile var appVisible = false

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
    var refPsi: Int
        get() = p.getInt("refPsi", 35)
        set(v) = p.edit().putInt("refPsi", v).apply()
    var compact: Boolean
        get() = p.getBoolean("compact", false)
        set(v) = p.edit().putBoolean("compact", v).apply()
    var bubbleX: Int
        get() = p.getInt("bx", 40)
        set(v) = p.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = p.getInt("by", 120)
        set(v) = p.edit().putInt("by", v).apply()

    /** Historial del SOH: un punto por día, "aaaa-mm-dd=83.0;...". */
    var sohHistory: List<Pair<String, Double>>
        get() = (p.getString("sohHist", "") ?: "").split(';').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2) kv[1].toDoubleOrNull()?.let { v -> kv[0] to v } else null
        }
        set(v) = p.edit().putString("sohHist", v.joinToString(";") { "${it.first}=${it.second}" }).apply()

    /** 0 = luces según el carro (si no se leen, encendidas); 1 = siempre encendidas. */
    var lightsMode: Int
        get() = p.getInt("lightsMode", 0)
        set(v) = p.edit().putInt("lightsMode", v).apply()
    /** Pantalla de la app: 0 Cabina, 1 Digital, 2 Clásico, 3 Detalle. */
    var page: Int
        get() = p.getInt("page", 1)
        set(v) = p.edit().putInt("page", v).apply()

    // Sesión de carga en curso.
    var chgStartTime: Long
        get() = p.getLong("chgStartTime", 0L)
        set(v) = p.edit().putLong("chgStartTime", v).apply()
    var chgStartSoc: Float
        get() = p.getFloat("chgStartSoc", 0f)
        set(v) = p.edit().putFloat("chgStartSoc", v).apply()
    var chgKwh: Float
        get() = p.getFloat("chgKwh", 0f)
        set(v) = p.edit().putFloat("chgKwh", v).apply()
    var chgLastTs: Long
        get() = p.getLong("chgLastTs", 0L)
        set(v) = p.edit().putLong("chgLastTs", v).apply()

    /** Últimas cargas, la más reciente primero: "dd/MM · 22 → 80 %". */
    var chargeHistory: List<String>
        get() = (p.getString("chgHist", "") ?: "").split('|').filter { it.isNotBlank() }
        set(v) = p.edit().putString("chgHist", v.joinToString("|")).apply()
}

/** Guarda el SOH diario y lleva la sesión de carga (inicio, kWh cargados, historial). */
object History {
    fun onData(ctx: Context, d: ZoeData) {
        val p = Prefs(ctx)
        d.soh?.let { soh ->
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val list = p.sohHistory
            if (list.lastOrNull()?.first != day) p.sohHistory = (list + (day to soh)).takeLast(365)
        }
        val s = d.displaySoc ?: return
        val now = System.currentTimeMillis()
        if (d.charging) {
            if (p.chgStartTime == 0L) {
                p.chgStartTime = now
                p.chgStartSoc = s.toFloat()
                p.chgKwh = 0f
            } else {
                val hours = (now - p.chgLastTs).coerceIn(0L, 120_000L) / 3_600_000.0
                p.chgKwh = p.chgKwh + ((d.chargingKw ?: 0.0) * hours).toFloat()
            }
            p.chgLastTs = now
        } else if (d.plugged != null && p.chgStartTime != 0L) {
            val start = p.chgStartSoc
            if (s - start >= 1) {
                val day = SimpleDateFormat("dd/MM", Locale.US).format(Date())
                p.chargeHistory = (listOf("$day · ${start.toInt()} → ${s.toInt()} %") + p.chargeHistory).take(5)
            }
            p.chgStartTime = 0L
        }
    }

    fun startHour(p: Prefs): String? =
        if (p.chgStartTime == 0L) null else SimpleDateFormat("HH:mm", Locale.US).format(Date(p.chgStartTime))
}
