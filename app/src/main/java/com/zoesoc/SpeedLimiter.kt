package com.zoesoc

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Limitador de velocidad (solo avisa, no frena nada).
 * Modos: AUTO (50 en ciudad, 80 en carretera), 50 fijo, 80 fijo, apagado.
 * Automático: pasa a 80 si va a más de 65 km/h durante 20 s; vuelve a 50 si va a menos de 40 durante 2 min.
 * Aviso: un solo "tic tic" al pasar el límite por 2 km/h durante 2 s; se rearma al bajar 2 km/h del límite.
 */
class SpeedLimiter(private val prefs: Prefs) {
    companion object {
        const val AUTO = 0
        const val FIX50 = 1
        const val FIX80 = 2
        const val OFF = 3
    }

    var mode: Int = prefs.limitMode.coerceIn(0, 3)
        private set
    private var auto = 50
    private var highSince = 0L
    private var lowSince = 0L
    private var overSince = 0L
    private var armed = true
    var over = false
        private set
    private var tone: ToneGenerator? = null

    /** Límite activo (null = apagado). */
    val limit: Int?
        get() = when (mode) {
            AUTO -> auto
            FIX50 -> 50
            FIX80 -> 80
            else -> null
        }

    /** Toque en la señal: Auto → 50 → 80 → apagado → Auto. */
    fun cycle() {
        mode = (mode + 1) % 4
        prefs.limitMode = mode
        over = false; armed = true; overSince = 0L
    }

    fun update(speed: Double?, now: Long = System.currentTimeMillis()) {
        val s = speed ?: run { over = false; return }
        // Ciudad o carretera (solo en automático)
        if (s > 65) { if (highSince == 0L) highSince = now } else highSince = 0L
        if (s < 40) { if (lowSince == 0L) lowSince = now } else lowSince = 0L
        if (highSince != 0L && now - highSince >= 20_000L && auto != 80) { auto = 80; over = false; armed = true; overSince = 0L }
        if (lowSince != 0L && now - lowSince >= 120_000L && auto != 50) { auto = 50; armed = true; overSince = 0L }

        val lim = limit ?: run { over = false; return }
        if (s > lim + 2) {
            if (overSince == 0L) overSince = now
            if (now - overSince >= 2_000L) {
                over = true
                if (armed) { armed = false; tic() }
            }
        } else {
            overSince = 0L
            if (s <= lim) over = false
            if (s <= lim - 2) armed = true
        }
    }

    private fun tic() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        } catch (e: Exception) { }
    }
}
