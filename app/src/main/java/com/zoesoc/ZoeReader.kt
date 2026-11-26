package com.zoesoc

import java.util.Locale

data class ZoeData(
    val soc: Double? = null,       // SOC del tablero (USOC)
    val realSoc: Double? = null,   // SOC real de la batería
    val soh: Double? = null,       // estado de salud promedio
    val tempC: Double? = null,     // temperatura promedio de la batería
    val currentA: Double? = null,  // corriente de la batería
    val rangeKm: Int? = null,      // autonomía que muestra el carro
    val time: Long = System.currentTimeMillis(),
) {
    val displaySoc: Double? get() = soc ?: realSoc
}

/**
 * Lecturas para Zoe fase 1 (ZE40 / R90). Direcciones y posiciones tomadas de los CSV de CanZE:
 *  LBC (batería) pide 79B, responde 7BB
 *   2101 bits 288-319 ×0.0001 → USOC %      2101 bits 16-31 (crudo-5000)×0.1 → corriente A
 *   2103 bits 192-207 ×0.01  → SOC real %   2104 bits 600-607 crudo-40 → temp. promedio °C
 *   2161 bits 72-79  ×0.5    → SOH %
 *  EVC pide 7E4, responde 7EC
 *   223451 últimos 2 bytes → autonomía km
 */
class ZoeReader(private val elm: Elm327, private val log: (String) -> Unit) {
    private var soh: Double? = null
    private var sohAt = 0L

    fun read(): ZoeData {
        var answered = 0
        var usoc: Double? = null
        var current: Double? = null
        var realSoc: Double? = null
        var temp: Double? = null
        var range: Int? = null

        try {
            val b = elm.request(LBC_TX, LBC_RX, "2101"); answered++
            usoc = Bits.field(b, 288, 319, 0.0001, 0.0)
            current = Bits.field(b, 16, 31, 0.1, 5000.0)
        } catch (e: ElmException) { log("2101: ${e.message}") }

        try {
            val b = elm.request(LBC_TX, LBC_RX, "2103"); answered++
            realSoc = Bits.field(b, 192, 207, 0.01, 0.0)
        } catch (e: ElmException) { log("2103: ${e.message}") }

        try {
            val b = elm.request(LBC_TX, LBC_RX, "2104"); answered++
            temp = Bits.field(b, 600, 607, 1.0, 40.0)
        } catch (e: ElmException) { log("2104: ${e.message}") }

        val now = System.currentTimeMillis()
        if (soh == null || now - sohAt > 10 * 60_000L) {
            try {
                val b = elm.request(LBC_TX, LBC_RX, "2161"); answered++
                soh = Bits.field(b, 72, 79, 0.5, 0.0)
                sohAt = now
            } catch (e: ElmException) { log("2161: ${e.message}") }
        }

        try {
            val b = elm.request(EVC_TX, EVC_RX, "223451"); answered++
            if (b.size < 5) throw ElmException("respuesta corta")
            range = Bits.get(b, (b.size - 2) * 8, b.size * 8 - 1).toInt()
        } catch (e: ElmException) { log("223451: ${e.message}") }

        if (answered == 0) throw ElmException("el carro no responde (¿apagado?)")

        if (usoc != null && (usoc < 0 || usoc > 100)) { log("USOC fuera de rango: $usoc"); usoc = null }
        if (realSoc != null && (realSoc < 0 || realSoc > 100)) { log("SOC real fuera de rango: $realSoc"); realSoc = null }
        if (range != null && range > 1000) range = null

        return ZoeData(usoc, realSoc, soh, temp, current, range)
    }

    companion object {
        const val LBC_TX = "79B"
        const val LBC_RX = "7BB"
        const val EVC_TX = "7E4"
        const val EVC_RX = "7EC"
    }
}

object Fmt {
    private fun f(v: Double, dec: Int) = String.format(Locale.US, "%.${dec}f", v)

    fun soc(d: ZoeData?): String = d?.displaySoc?.let { f(it, 0) + "%" } ?: "--%"

    /** Línea corta para la burbuja y el widget. */
    fun short(d: ZoeData?): String {
        if (d == null) return ""
        val p = mutableListOf<String>()
        d.rangeKm?.let { p += "$it km" }
        d.tempC?.let { p += f(it, 0) + "°C" }
        d.soh?.let { p += "SOH " + f(it, 0) + "%" }
        return p.joinToString(" · ")
    }

    /** Detalle completo para la pantalla de la app. */
    fun details(d: ZoeData?): String {
        if (d == null) return "Sin datos todavía"
        val p = mutableListOf<String>()
        d.soc?.let { p += "SOC tablero: " + f(it, 1) + " %" }
        d.realSoc?.let { p += "SOC real: " + f(it, 1) + " %" }
        d.soh?.let { p += "SOH: " + f(it, 1) + " %" }
        d.tempC?.let { p += "Temp. batería: " + f(it, 0) + " °C" }
        d.currentA?.let { p += "Corriente: " + f(it, 1) + " A" }
        d.rangeKm?.let { p += "Autonomía: $it km" }
        val age = (System.currentTimeMillis() - d.time) / 1000
        p += "Actualizado hace $age s"
        return p.joinToString("\n")
    }
}
