package com.zoesoc

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Presión de cada llanta en PSI (null = sin dato). */
data class Tires(val fl: Double?, val fr: Double?, val rl: Double?, val rr: Double?)

data class ZoeData(
    val soc: Double? = null,            // SOC del tablero (USOC)
    val realSoc: Double? = null,        // SOC real de la batería
    val soh: Double? = null,            // estado de salud
    val tempC: Double? = null,          // temperatura promedio de la batería
    val currentA: Double? = null,       // corriente de la batería
    val rangeKm: Int? = null,           // autonomía que muestra el carro
    val v12: Double? = null,            // batería de 12 V
    val cellDiffMv: Int? = null,        // diferencia entre la celda más alta y la más baja
    val plugged: Boolean? = null,       // cable de carga conectado
    val chargingKw: Double? = null,     // potencia de carga
    val availKwh: Double? = null,       // energía disponible
    val avgConsumption: Double? = null, // consumo promedio del carro (kWh/100 km)
    val tires: Tires? = null,
    val extTemp: Double? = null,        // temperatura exterior
    val cabinTemp: Double? = null,      // temperatura dentro del carro
    val climOn: Boolean? = null,        // climatizador funcionando
    val lightsOn: Boolean? = null,      // luces (null = no se pudo leer)
    val speedKmh: Double? = null,       // velocidad (solo con la app abierta)
    val powerKw: Double? = null,        // potencia instantánea (+ consume, − regenera)
    val time: Long = System.currentTimeMillis(),
    val fastTime: Long = 0L,
) {
    val displaySoc: Double? get() = soc ?: realSoc
    val charging: Boolean get() = plugged == true && (chargingKw ?: 0.0) > 0.3

    /** Consumo instantáneo en kWh/100 km (solo andando a más de 5 km/h). */
    val instantConsumption: Double?
        get() {
            val s = speedKmh ?: return null
            val p = powerKw ?: return null
            if (s < 5) return null
            return p / s * 100
        }

    /** Minutos aproximados para llegar a [target] % cargando. */
    fun minutesTo(target: Double): Int? {
        val s = displaySoc ?: return null
        val kw = chargingKw ?: return null
        if (!charging || s >= target || kw <= 0.3) return null
        val cap = availKwh?.takeIf { s > 2 }?.let { it / (s / 100) } ?: (41.0 * (soh ?: 100.0) / 100)
        val need = cap * (target - s) / 100
        return (need / kw * 60).roundToInt()
    }
}

/**
 * Lecturas para Zoe fase 1 (ZE40 / R90), tomadas de los CSV de CanZE.
 * Peticiones de diagnóstico (solo lectura):
 *  LBC 79B/7BB: 2101 USOC (288-319 ×0.0001) y corriente (16-31, (x−5000)×0.1) · 2103 SOC real (192-207 ×0.01)
 *               2104 temp. promedio (600-607, x−40) · 2161 SOH (72-79 ×0.5) · 2141/2142 voltaje de celdas (×0.001)
 *  EVC 7E4/7EC: 223451 autonomía (últimos 2 bytes) · 222005 batería 12 V (24-39 ×0.01)
 * Tramas libres (solo se escuchan):
 *  654 bit 2 enchufe conectado, 52-61 ×0.1 consumo promedio · 42E 56-63 ×0.3 potencia de carga
 *  427 49-57 ×0.1 energía disponible · 673 presiones ×13.725 mbar (FL 40, FR 32, RL 24, RR 16)
 *  5D7 0-15 ×0.01 velocidad · 1FD 48-55 (x−80) potencia kW
 *  656 48-55 (x−40) temperatura exterior · 42A 48-50 modo del clima
 *  5DE byte 0 bit 0x04 luces (dato del Smart EQ, misma plataforma; no está en CanZE)
 * Clima 744/764: 2121 26-35 (x−400)×0.1 temperatura interior
 */
class ZoeReader(private val elm: Elm327, private val log: (String) -> Unit) {
    private var soh: Double? = null
    private var sohAt = 0L
    private var cellDiff: Int? = null
    private var cellAt = 0L
    private var tires: Tires? = null
    private var avgCons: Double? = null
    private var avail: Double? = null
    private var ext: Double? = null
    private var clim: Boolean? = null
    private var lights: Boolean? = null
    private var turn = 0
    // Últimos valores buenos, para que no parpadeen si una lectura falla.
    private var lastTemp: Double? = null
    private var lastRange: Int? = null
    private var lastV12: Double? = null
    private var lastCabin: Double? = null

    private inline fun <T> tryOr(name: String, f: () -> T): T? = try {
        f()
    } catch (e: ElmException) {
        log("$name: ${e.message}")
        null
    }

    private inline fun <T> quiet(f: () -> T): T? = try { f() } catch (e: ElmException) { null }

    fun read(): ZoeData {
        var answered = 0
        val now = System.currentTimeMillis()

        var usoc: Double? = null
        var current: Double? = null
        tryOr("2101") {
            val b = elm.request(LBC_TX, LBC_RX, "2101")
            answered++
            usoc = Bits.field(b, 288, 319, 0.0001, 0.0)
            current = Bits.field(b, 16, 31, 0.1, 5000.0)
        }
        var real = tryOr("2103") { Bits.field(elm.request(LBC_TX, LBC_RX, "2103"), 192, 207, 0.01, 0.0) }
        if (real != null) answered++
        val tempRead = tryOr("2104") { Bits.field(elm.request(LBC_TX, LBC_RX, "2104"), 600, 607, 1.0, 40.0) }
        if (tempRead != null) { answered++; lastTemp = tempRead }
        val temp = tempRead ?: lastTemp
        if (soh == null || now - sohAt > SLOW_MS) {
            tryOr("2161") { Bits.field(elm.request(LBC_TX, LBC_RX, "2161"), 72, 79, 0.5, 0.0) }?.let {
                soh = it; sohAt = now; answered++
            }
        }
        if (cellDiff == null || now - cellAt > SLOW_MS) {
            tryOr("celdas") { cellSpread() }?.let { cellDiff = it; cellAt = now }
        }
        var range = tryOr("223451") {
            val b = elm.request(EVC_TX, EVC_RX, "223451")
            if (b.size < 5) throw ElmException("respuesta corta")
            Bits.get(b, (b.size - 2) * 8, b.size * 8 - 1).toInt()
        }
        if (range != null) answered++
        tryOr("222005") { Bits.field(elm.request(EVC_TX, EVC_RX, "222005"), 24, 39, 0.01, 0.0) }
            ?.takeIf { it in 5.0..20.0 }?.let { lastV12 = it }
        val v12 = lastV12

        if (answered == 0) throw ElmException("el carro no responde (¿apagado?)")

        tryOr("2121") {
            Bits.field(elm.request(HVAC_TX, HVAC_RX, "2121"), 26, 35, 0.1, 400.0)
        }?.takeIf { it in -40.0..80.0 }?.let { lastCabin = it }
        val cabin = lastCabin

        // Tramas libres: si alguna no llega, se conserva el último valor.
        var plugged: Boolean? = null
        tryOr("654") {
            val f = elm.freeFrame("654", 1200)
            plugged = Bits.get(f, 2, 2) == 1L
            val a = Bits.field(f, 52, 61, 0.1, 0.0)
            if (a in 1.0..60.0) avgCons = a
        }
        var chg: Double? = null
        if (plugged == true) chg = tryOr("42E") { Bits.field(elm.freeFrame("42E", 600), 56, 63, 0.3, 0.0) }
        // Luces: si la trama no existe en este carro, queda null y se dibujan encendidas.
        quiet { lights = Bits.get(elm.freeFrame("5DE", 500), 5, 5) == 1L }
        // Las demás tramas lentas se turnan, una por ciclo, para que cada lectura sea corta.
        when (turn++ % 4) {
            0 -> tryOr("673") {
                val f = elm.freeFrame("673", 1200)
                val t = Tires(psi(f, 40), psi(f, 32), psi(f, 24), psi(f, 16))
                if (t.fl != null || t.fr != null || t.rl != null || t.rr != null) tires = t
            }
            1 -> tryOr("656") {
                val e = Bits.field(elm.freeFrame("656", 1200), 48, 55, 1.0, 40.0)
                if (e in -40.0..70.0) ext = e
            }
            2 -> tryOr("427") {
                val e = Bits.field(elm.freeFrame("427", 600), 49, 57, 0.1, 0.0)
                if (e in 0.5..60.0) avail = e
            }
            else -> tryOr("42A") { clim = Bits.get(elm.freeFrame("42A", 600), 48, 50) != 0L }
        }

        if (usoc != null && (usoc!! < 0 || usoc!! > 100)) { log("USOC fuera de rango: $usoc"); usoc = null }
        if (real != null && (real < 0 || real > 100)) { log("SOC real fuera de rango: $real"); real = null }
        if (range != null && range > 1000) range = null
        if (range != null) lastRange = range else range = lastRange

        return ZoeData(
            soc = usoc, realSoc = real, soh = soh, tempC = temp, currentA = current, rangeKm = range,
            v12 = v12, cellDiffMv = cellDiff, plugged = plugged, chargingKw = chg,
            availKwh = avail, avgConsumption = avgCons, tires = tires,
            extTemp = ext, cabinTemp = cabin, climOn = clim, lightsOn = lights,
        )
    }

    /** Lecturas rápidas para la app abierta: velocidad y potencia. */
    fun fast(): Pair<Double?, Double?> {
        val sp = quiet { Bits.field(elm.freeFrame("5D7", 120), 0, 15, 0.01, 0.0) }?.takeIf { it < 250 }
        val pw = quiet { Bits.field(elm.freeFrame("1FD", 120), 48, 55, 1.0, 80.0) }
        return sp to pw
    }

    /** Presión en PSI desde la trama 673 (13,725 mbar por unidad). */
    private fun psi(f: ByteArray, start: Int): Double? {
        val raw = Bits.get(f, start, start + 7)
        return if (raw == 0L || raw == 255L) null else raw * 13.725 * 0.0145038
    }

    private fun cellSpread(): Int {
        val a = elm.request(LBC_TX, LBC_RX, "2141", 6000)
        val b = elm.request(LBC_TX, LBC_RX, "2142", 6000)
        val cells = ArrayList<Double>()
        for (i in 0 until 62) cells += Bits.field(a, 16 + 16 * i, 31 + 16 * i, 0.001, 0.0)
        for (i in 0 until 34) cells += Bits.field(b, 16 + 16 * i, 31 + 16 * i, 0.001, 0.0)
        val ok = cells.filter { it in 2.5..4.5 }
        if (ok.size < 80) throw ElmException("celdas incompletas (${ok.size})")
        return ((ok.maxOrNull()!! - ok.minOrNull()!!) * 1000).roundToInt()
    }

    companion object {
        const val LBC_TX = "79B"
        const val LBC_RX = "7BB"
        const val EVC_TX = "7E4"
        const val EVC_RX = "7EC"
        const val HVAC_TX = "744"
        const val HVAC_RX = "764"
        const val SLOW_MS = 10 * 60_000L
    }
}

object Colors {
    val GREEN = 0xFF69F0AE.toInt()
    val AMBER = 0xFFFFC107.toInt()
    val RED = 0xFFFF5252.toInt()
    val GRAY = 0xFF5F6670.toInt()

    /** Verde ≥ 30 %, amarillo < 30 %, rojo < 20 %. */
    fun soc(s: Double?): Int = when {
        s == null -> 0xFFFFFFFF.toInt()
        s < 20 -> RED
        s < 30 -> AMBER
        else -> GREEN
    }

    val BLUE = 0xFF64B5F6.toInt()

    /** Temperatura de la batería: azul < 10 °C, verde 10–29, amarillo 30–39, rojo ≥ 40. */
    fun batTemp(t: Double?): Int = when {
        t == null -> 0xFFE9EEF3.toInt()
        t < 10 -> BLUE
        t < 30 -> GREEN
        t < 40 -> AMBER
        else -> RED
    }

    /** Verde hasta 3 PSI por debajo de la recomendada, amarillo hasta 6, rojo más abajo. */
    fun tire(psi: Double?, ref: Int): Int = when {
        psi == null -> GRAY
        psi < ref - 6 -> RED
        psi < ref - 3 -> AMBER
        else -> GREEN
    }
}

object Fmt {
    private val ES = Locale("es", "CO")
    fun f(v: Double, dec: Int): String = String.format(ES, "%.${dec}f", v)

    /** Parte entera y decimal del SOC: 35.43 → ("35", "4"). */
    fun socParts(s: Double): Pair<String, String> {
        val c = s.coerceIn(0.0, 100.0)
        val i = floor(c).toInt()
        val d = floor((c - i) * 10).toInt().coerceIn(0, 9)
        return i.toString() to d.toString()
    }

    fun soc(d: ZoeData?): String = d?.displaySoc?.let { it.roundToInt().toString() + "%" } ?: "--%"
    fun socDec(d: ZoeData?): String = d?.displaySoc?.let { f(it, 1) + " %" } ?: "-- %"

    fun temp(v: Double?): String = v?.let { f(it, 0) + " °C" } ?: "--"

    /** Versión corta para la burbuja: "1 h 05" o "45 min". */
    fun durShort(min: Int): String = if (min >= 60) "${min / 60} h ${String.format(ES, "%02d", min % 60)}" else "$min min"

    fun dur(min: Int): String = if (min >= 60) "${min / 60} h ${String.format(ES, "%02d", min % 60)} min" else "$min min"

    /** Línea de carga: "6,8 kW · 80 % en 1 h 05 min". */
    fun chargeLine(d: ZoeData): String {
        val kw = d.chargingKw?.let { f(it, 1) + " kW" } ?: "Cargando"
        val eta = d.minutesTo(80.0)?.let { " · 80 % en " + dur(it) } ?: d.minutesTo(100.0)?.let { " · 100 % en " + dur(it) } ?: ""
        return kw + eta
    }

    /** Línea corta para la notificación y el widget. */
    fun short(d: ZoeData?): String {
        if (d == null) return ""
        if (d.charging) return "Cargando " + chargeLine(d)
        return d.tempC?.let { "Batería " + f(it, 0) + " °C" } ?: ""
    }
}
