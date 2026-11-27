package com.zoesoc

import java.util.Locale
import kotlin.math.abs
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
 *  654 bit 2 enchufe conectado, 52-61 ×0.1 consumo promedio
 *  Potencia de carga = voltaje (EVC 223203, 24-39 ×0.5 V) × corriente de la batería (2101)
 *  427 49-57 ×0.1 energía disponible · 673 presiones ×13.725 mbar (FL 40, FR 32, RL 24, RR 16)
 *  5D7 0-15 ×0.01 velocidad (×1,026 + 1,8 para igualar el tablero) · 1FD 48-55 (x−80) potencia kW
 *  656 48-55 (x−40) temperatura exterior
 *  5DE byte 0 bit 0x04 luces (dato del Smart EQ, misma plataforma; no está en CanZE)
 * Clima 744/764: 2121 26-35 (x−400)×0.1 temperatura interior
 */
class ZoeReader(private val elm: Elm327, private val log: (String) -> Unit) {
    // Último valor bueno de cada dato: si una lectura falla, se conserva el anterior (no parpadea).
    private var usoc: Double? = null
    private var current: Double? = null
    private var real: Double? = null
    private var temp: Double? = null
    private var tempAt = 0L
    private var range: Int? = null
    private var v12: Double? = null
    private var cabin: Double? = null
    private var soh: Double? = null
    private var sohAt = 0L
    private var cellDiff: Int? = null
    private var cellAt = 0L
    private var plugged: Boolean? = null
    private var chg: Double? = null
    private var packV: Double? = null
    private var tires: Tires? = null
    private var avgCons: Double? = null
    private var avail: Double? = null
    private var ext: Double? = null
    private var lights: Boolean? = null

    private var stepIdx = 0
    private var lastSpeed: Double? = null
    private var lastSpeedAt = 0L
    private var turn = 0
    private var answeredInCycle = 0

    private inline fun <T> tryOr(name: String, f: () -> T): T? = try {
        f()
    } catch (e: ElmException) {
        log("$name: ${e.message}")
        null
    }

    private inline fun <T> quiet(f: () -> T): T? = try { f() } catch (e: ElmException) { null }

    /** Una lectura completa (para cuando solo está la burbuja). */
    fun read(): ZoeData {
        while (!step()) { /* sigue con el siguiente pedacito */ }
        return snapshot()
    }

    /**
     * Hace UN pedacito de la lectura (una petición o una trama) y devuelve true cuando se completó una vuelta.
     * Con la app abierta se intercala con la velocidad para que esta no se congele.
     * Al terminar una vuelta sin ninguna respuesta del carro, lanza ElmException.
     */
    fun step(): Boolean {
        val now = System.currentTimeMillis()
        when (stepIdx) {
            0 -> tryOr("2101") {
                val b = elm.request(LBC_TX, LBC_RX, "2101")
                answeredInCycle++
                Bits.field(b, 288, 319, 0.0001, 0.0).takeIf { it in 0.0..100.0 }?.let { usoc = it }
                current = Bits.field(b, 16, 31, 0.1, 5000.0)
            }
            1 -> tryOr("2103") { Bits.field(elm.request(LBC_TX, LBC_RX, "2103"), 192, 207, 0.01, 0.0) }
                ?.let { answeredInCycle++; if (it in 0.0..100.0) real = it }
            2 -> {
                val every = if (chg != null && chg!! > 0.3) 60_000L else SLOW_MS
                if (temp == null || now - tempAt > every) {
                    tryOr("2104") { Bits.field(elm.request(LBC_TX, LBC_RX, "2104"), 600, 607, 1.0, 40.0) }
                        ?.let { answeredInCycle++; temp = it; tempAt = now }
                }
            }
            3 -> {
                if (soh == null || now - sohAt > SLOW_MS) {
                    tryOr("2161") { Bits.field(elm.request(LBC_TX, LBC_RX, "2161"), 72, 79, 0.5, 0.0) }
                        ?.let { soh = it; sohAt = now; answeredInCycle++ }
                } else if (cellDiff == null || now - cellAt > SLOW_MS) {
                    tryOr("celdas") { cellSpread() }?.let { cellDiff = it; cellAt = now }
                }
            }
            4 -> tryOr("223451") {
                val b = elm.request(EVC_TX, EVC_RX, "223451")
                if (b.size < 5) throw ElmException("respuesta corta")
                Bits.get(b, (b.size - 2) * 8, b.size * 8 - 1).toInt()
            }?.let { answeredInCycle++; if (it <= 1000) range = it }
            5 -> {
                tryOr("222005") { Bits.field(elm.request(EVC_TX, EVC_RX, "222005"), 24, 39, 0.01, 0.0) }
                    ?.takeIf { it in 5.0..20.0 }?.let { v12 = it }
                // Voltaje del paquete, solo si está enchufado (para calcular la potencia real de carga).
                if (plugged == true) {
                    tryOr("223203") { Bits.field(elm.request(EVC_TX, EVC_RX, "223203"), 24, 39, 0.5, 0.0) }
                        ?.takeIf { it in 200.0..450.0 }?.let { packV = it }
                }
            }
            6 -> tryOr("2121") { Bits.field(elm.request(HVAC_TX, HVAC_RX, "2121"), 26, 35, 0.1, 400.0) }
                ?.takeIf { it in -40.0..80.0 }?.let { cabin = it }
            7 -> {
                // Enchufe y potencia de carga: solo con el carro detenido (o sin dato de velocidad).
                val moving = (lastSpeed ?: 0.0) > 0.5 && now - lastSpeedAt < 5_000L
                if (moving) {
                    plugged = false; chg = null
                } else {
                    tryOr("654") {
                        val f = elm.freeFrame("654", 700)
                        plugged = Bits.get(f, 2, 2) == 1L
                        val a = Bits.field(f, 52, 61, 0.1, 0.0)
                        if (a in 1.0..60.0) avgCons = a
                    }
                    // Potencia que realmente entra = voltaje × corriente de la batería.
                    // (La trama 42E es la potencia MÁXIMA que acepta la batería, no la que entra.)
                    chg = if (plugged == true) chargePower() else null
                }
            }
            8 -> {
                // Luces: si la trama no existe en este carro, queda null y se dibujan encendidas.
                quiet { lights = Bits.get(elm.freeFrame("5DE", 400), 5, 5) == 1L }
            }
            else -> {
                // Tramas lentas, una por vuelta.
                when (turn++ % 3) {
                    0 -> tryOr("673") {
                        val f = elm.freeFrame("673", 1200)
                        val t = Tires(psi(f, 40), psi(f, 32), psi(f, 24), psi(f, 16))
                        if (t.fl != null || t.fr != null || t.rl != null || t.rr != null) tires = t
                    }
                    1 -> tryOr("656") {
                        val e = Bits.field(elm.freeFrame("656", 800), 48, 55, 1.0, 40.0)
                        if (e in -40.0..70.0) ext = e
                    }
                    else -> tryOr("427") {
                        val e = Bits.field(elm.freeFrame("427", 500), 49, 57, 0.1, 0.0)
                        if (e in 0.5..60.0) avail = e
                    }
                }
            }
        }
        stepIdx++
        if (stepIdx < STEPS) return false
        stepIdx = 0
        val ok = answeredInCycle > 0
        answeredInCycle = 0
        if (!ok) throw ElmException("el carro no responde (¿apagado?)")
        return true
    }

    /** Foto de los últimos valores leídos. */
    fun snapshot(): ZoeData = ZoeData(
        soc = usoc, realSoc = real, soh = soh, tempC = temp, currentA = current, rangeKm = range,
        v12 = v12, cellDiffMv = cellDiff, plugged = plugged, chargingKw = chg,
        availKwh = avail, avgConsumption = avgCons, tires = tires,
        extTemp = ext, cabinTemp = cabin, climOn = null, lightsOn = lights,
    )

    /** Lecturas rápidas para la app abierta: velocidad y potencia. */
    fun fast(): Pair<Double?, Double?> {
        // El velocímetro del tablero marca a propósito un poco más que la velocidad real.
        // Ajuste medido en el carro: real 47 → tablero 50, real 86 → tablero 90.
        val sp = quiet { Bits.field(elm.freeFrame("5D7", 120), 0, 15, 0.01, 0.0) }
            ?.takeIf { it < 250 }?.let { toDash(it) }
        if (sp != null) { lastSpeed = sp; lastSpeedAt = System.currentTimeMillis() }
        val pw = quiet { Bits.field(elm.freeFrame("1FD", 120), 48, 55, 1.0, 80.0) }
        return sp to pw
    }

    /** kW entrando a la batería, desde voltaje y corriente; 0 si no está transfiriendo. */
    private fun chargePower(): Double? {
        val v = packV ?: return null
        val i = current ?: return null
        val kw = abs(v * i) / 1000.0
        return if (kw < 0.8) 0.0 else kw // por debajo de ~0,8 kW es el consumo propio del carro, no carga
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
        const val STEPS = 10
        const val DASH_K = 1.026
        const val DASH_C = 1.8

        /** Velocidad real → velocidad como la muestra el tablero (detenido sigue en 0). */
        fun toDash(real: Double): Double = if (real < 2.0) real else real * DASH_K + DASH_C
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
