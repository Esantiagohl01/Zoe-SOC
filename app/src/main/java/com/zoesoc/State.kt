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
    /** Qué sensor va en cada posición (DI, DD, TI, TD): 4 dígitos, p. ej. "0123" = de fábrica. */
    var tireMap: String
        get() = p.getString("tireMap", "0123")?.takeIf { TireMap.valid(it) } ?: "0123"
        set(v) = p.edit().putString("tireMap", v).apply()
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
    /** Limitador: 0 automático, 1 = 50, 2 = 80, 3 = apagado. */
    var limitMode: Int
        get() = p.getInt("limitMode", 0)
        set(v) = p.edit().putInt("limitMode", v).apply()
    /** Pantalla de la app: 0 Digital, 1 Detalle. */
    /** Prueba: hoja 3 con navegación de Google Maps. */
    var navPage: Boolean
        get() = p.getBoolean("navPage", false)
        set(v) = p.edit().putBoolean("navPage", v).apply()
    var page: Int
        get() = p.getInt("page2", 0)
        set(v) = p.edit().putInt("page2", v).apply()

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
    /** Última carga terminada: día ("26 nov") y kWh cargados. */
    var lastChgDay: String?
        get() = p.getString("lastChgDay", null)
        set(v) = p.edit().putString("lastChgDay", v).apply()
    var lastChgKwh: Float
        get() = p.getFloat("lastChgKwh", 0f)
        set(v) = p.edit().putFloat("lastChgKwh", v).apply()
    /** Km y kWh recorridos desde la última carga (se reinician al terminar de cargar). */
    var sinceKm: Float
        get() = p.getFloat("sinceKm", 0f)
        set(v) = p.edit().putFloat("sinceKm", v).apply()
    var sinceKwh: Float
        get() = p.getFloat("sinceKwh", 0f)
        set(v) = p.edit().putFloat("sinceKwh", v).apply()
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
                val cal = java.util.Calendar.getInstance()
                p.lastChgDay = "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(java.util.Calendar.MONTH)]}"
                p.lastChgKwh = p.chgKwh
                p.sinceKm = 0f; p.sinceKwh = 0f
            }
            p.chgStartTime = 0L
        }
        // Km y kWh desde la última carga, sumando lo que avanzan los contadores de viaje.
        if (!d.charging) {
            val km = d.tripKm; val kwh = d.tripKwh
            val lk = lastKm; val le = lastKwh
            if (km != null && lk != null && km >= lk && km - lk < 2.0) p.sinceKm = p.sinceKm + (km - lk).toFloat()
            if (kwh != null && le != null && kwh >= le && kwh - le < 1.0) p.sinceKwh = p.sinceKwh + (kwh - le).toFloat()
            lastKm = km; lastKwh = kwh
        }
    }

    private var lastKm: Double? = null
    private var lastKwh: Double? = null
    private val MONTHS = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    fun startHour(p: Prefs): String? =
        if (p.chgStartTime == 0L) null else SimpleDateFormat("HH:mm", Locale.US).format(Date(p.chgStartTime))
}

/**
 * Viaje desde que se prendió el carro: diferencia de los contadores de viaje del carro
 * respecto al valor que tenían al arrancar. Si el contador se reinicia en el tablero, se acumula lo que llevaba.
 */
object ZoeTrip {
    private var baseKm: Double? = null
    private var lastKm: Double? = null
    private var accKm = 0.0
    private var baseKwh: Double? = null
    private var lastKwh: Double? = null
    private var accKwh = 0.0

    @Synchronized fun reset() {
        baseKm = null; lastKm = null; accKm = 0.0
        baseKwh = null; lastKwh = null; accKwh = 0.0
    }

    @Synchronized fun apply(d: ZoeData): ZoeData {
        val km = d.tripRawKm?.let { raw ->
            if (baseKm == null) baseKm = raw
            val l = lastKm
            if (l != null && raw < l - 0.05) { accKm += l - (baseKm ?: l); baseKm = raw }
            lastKm = raw
            accKm + raw - (baseKm ?: raw)
        }
        val kwh = d.tripRawKwh?.let { raw ->
            if (baseKwh == null) baseKwh = raw
            val l = lastKwh
            if (l != null && raw < l - 0.01) { accKwh += l - (baseKwh ?: l); baseKwh = raw }
            lastKwh = raw
            accKwh + raw - (baseKwh ?: raw)
        }
        return d.copy(tripKm = km, tripKwh = kwh)
    }
}

/**
 * Potencia de los últimos 15 minutos para la gráfica de la hoja 2.
 * Se guarda en cajitas de 2 s (promedio), 450 en total; las vacías quedan como hueco.
 */
object PowerLog {
    const val BIN_MS = 2_000L
    const val BINS = 450
    private val sum = DoubleArray(BINS)
    private val cnt = IntArray(BINS)
    private val binId = LongArray(BINS) { -1L }

    @Synchronized fun add(kw: Double, now: Long = System.currentTimeMillis()) {
        val id = now / BIN_MS
        val i = (id % BINS).toInt()
        if (binId[i] != id) { binId[i] = id; sum[i] = 0.0; cnt[i] = 0 }
        sum[i] += kw; cnt[i]++
    }

    /** Valores de la más vieja a la más nueva (null = sin dato). */
    @Synchronized fun series(now: Long = System.currentTimeMillis()): Array<Double?> {
        val last = now / BIN_MS
        return Array(BINS) { k ->
            val id = last - (BINS - 1 - k)
            val i = (id % BINS).toInt()
            if (binId[i] == id && cnt[i] > 0) sum[i] / cnt[i] else null
        }
    }
}

/** Modo noche que la Tbox recibe del R-Link (prueba: si cambia solo, el fondo podría seguirlo). */
object NightMode {
    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun describe(ctx: Context): String {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val sys = when (um?.nightMode) {
            android.app.UiModeManager.MODE_NIGHT_YES -> "sí"
            android.app.UiModeManager.MODE_NIGHT_NO -> "no"
            android.app.UiModeManager.MODE_NIGHT_AUTO -> "auto"
            else -> "?"
        }
        return (if (isNight(ctx)) "sí" else "no") + " (sistema: $sys)"
    }
}
