package com.zoesoc

/** Error de protocolo (el carro no respondió, respuesta rara). No requiere reconectar. */
class ElmException(msg: String) : Exception(msg)

/**
 * Manejo del ELM327 en modo CAN 11 bits 500 kbps (ISO 15765-4), que es el del Zoe fase 1.
 * Solo se envían comandos de lectura (servicio 0x21 / 0x22) y la apertura de sesión 10C0.
 */
class Elm327(private val t: Transport, private val log: (String) -> Unit) {
    private var ecu = ""
    private var mode = "diag" // "diag" = peticiones ISO-TP; "mon:XXX" = escuchando una trama libre

    fun cmd(c: String, timeoutMs: Long = 2500): String {
        t.clearInput()
        t.write(c + "\r")
        return t.readUntilPrompt(timeoutMs)
    }

    fun init() {
        cmd("ATZ", 5000)
        Thread.sleep(300)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6", "ATCAF1")) {
            val r = cmd(c)
            if (!r.contains("OK")) log("$c → ${clean(r)}")
        }
        log("Scanner: ${clean(cmd("ATI"))}")
        ecu = ""
        mode = "diag"
    }

    private fun select(tx: String, rx: String) {
        if (mode != "diag") {
            cmd("ATCAF1")
            mode = "diag"
            if (ecu == tx) { cmd("ATCRA$rx"); return } // el encabezado sigue configurado
        }
        if (ecu == tx) return
        for (c in listOf("ATSH$tx", "ATCRA$rx", "ATFCSH$tx", "ATFCSD300000", "ATFCSM1")) {
            val r = cmd(c)
            if (!r.contains("OK")) log("$c → ${clean(r)}")
        }
        ecu = tx
        // Sesión de diagnóstico estándar; si no responde no pasa nada.
        cmd("10C0", 3000)
    }

    /** Envía una petición (ej. "2101") al módulo tx y devuelve la respuesta completa en bytes, empezando por el SID+0x40. */
    fun request(tx: String, rx: String, req: String, timeoutMs: Long = 4000): ByteArray {
        select(tx, rx)
        val b = parse(cmd(req, timeoutMs))
        if (b.isEmpty()) throw ElmException("sin respuesta a $req")
        val first = b[0].toInt() and 0xFF
        val sid = req.substring(0, 2).toInt(16)
        if (first == 0x7F) throw ElmException("el módulo rechazó $req")
        if (first != sid + 0x40) throw ElmException("respuesta inesperada a $req")
        return b
    }

    /**
     * Escucha una trama libre del bus (las que los módulos emiten solos, ej. 5D7 = velocidad)
     * durante unos milisegundos y devuelve la última recibida. Solo escucha, no envía nada al carro.
     */
    fun freeFrame(id: String, listenMs: Long = 250): ByteArray {
        if (mode != "mon:$id") {
            if (!mode.startsWith("mon")) cmd("ATCAF0") // solo al venir de peticiones
            cmd("ATCRA$id")
            mode = "mon:$id"
        }
        t.clearInput()
        t.write("ATMA\r")
        Thread.sleep(listenMs)
        t.write("\r") // cualquier carácter detiene el monitoreo
        val r = t.readUntilPrompt(2500)
        val frames = r.split('\r', '\n')
            .map { it.trim().replace(" ", "") }
            .filter { FRAME_LINE.matches(it) && it.length % 2 == 0 }
        val last = frames.lastOrNull { it.length == 16 } ?: frames.lastOrNull()
            ?: throw ElmException("sin trama $id")
        return ByteArray(last.length / 2) { last.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun clean(r: String) = r.replace('\r', ' ').replace('\n', ' ').trim()

    companion object {
        private val HEX_LINE = Regex("^([0-9A-Fa-f]+:)?[0-9A-Fa-f]+$")
        private val FRAME_LINE = Regex("^[0-9A-Fa-f]{4,16}$")
        private val ERRORS = listOf("NODATA", "CANERROR", "ERROR", "STOPPED", "UNABLE", "BUFFERFULL", "?")

        /** Convierte la salida del ELM (con ATS0/ATH0/ATCAF1) a bytes; arma las respuestas multi-trama "0:... 1:...". */
        fun parse(raw: String): ByteArray {
            val lines = raw.split('\r', '\n').map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
            for (l in lines) {
                val u = l.uppercase()
                for (e in ERRORS) if (u.contains(e)) throw ElmException(l)
            }
            val hexLines = lines.filter { HEX_LINE.matches(it) }
            val multi = hexLines.any { it.contains(':') }
            var len = -1
            val sb = StringBuilder()
            for (l in hexLines) {
                if (multi && !l.contains(':')) {
                    if (len < 0 && l.length <= 3) len = l.toInt(16)
                    continue
                }
                sb.append(l.substringAfter(':'))
            }
            val hex = if (sb.length % 2 == 1) sb.substring(0, sb.length - 1) else sb.toString()
            var bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            if (len in 1..bytes.size) bytes = bytes.copyOf(len)
            return bytes
        }
    }
}

/** Extracción de bits al estilo de los CSV de CanZE: bit 0 = bit más alto del primer byte de la respuesta. */
object Bits {
    fun get(data: ByteArray, start: Int, end: Int): Long {
        if (start < 0 || end / 8 >= data.size) throw ElmException("respuesta corta (${data.size} bytes)")
        var v = 0L
        for (i in start..end) {
            val bit = (data[i / 8].toInt() shr (7 - i % 8)) and 1
            v = (v shl 1) or bit.toLong()
        }
        return v
    }

    /** valor = (crudo - offset) * escala, igual que CanZE. */
    fun field(data: ByteArray, start: Int, end: Int, scale: Double, offset: Double): Double =
        (get(data, start, end) - offset) * scale
}
