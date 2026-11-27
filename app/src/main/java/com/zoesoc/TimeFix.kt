package com.zoesoc

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Hora correcta para el reloj de la app, aunque el Tbox la pierda al apagarse (no tiene pila de reloj).
 * Fuentes: la hora de internet (si hay datos) y la del GPS. La app no cambia la hora del sistema.
 */
object TimeFix {
    @Volatile private var offset: Long? = null
    @Volatile private var fromInternet = false

    val synced: Boolean get() = offset != null

    fun now(): Long = System.currentTimeMillis() + (offset ?: 0L)

    /** Hora del GPS (UTC). La de internet tiene prioridad si ya llegó. */
    fun fromGps(utcMillis: Long) {
        if (fromInternet || utcMillis < 1_700_000_000_000L) return
        offset = utcMillis - System.currentTimeMillis()
    }

    /** Pide la hora a internet en segundo plano (encabezado "Date" de una página). */
    fun fetchInternet(onDone: () -> Unit = {}) {
        if (fromInternet) return
        thread(isDaemon = true, name = "hora-internet") {
            for (u in listOf("https://www.google.com", "https://www.cloudflare.com")) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.requestMethod = "HEAD"
                    c.connectTimeout = 4000
                    c.readTimeout = 4000
                    c.connect()
                    val d = c.date
                    c.disconnect()
                    if (d > 1_700_000_000_000L) {
                        offset = d - System.currentTimeMillis()
                        fromInternet = true
                        onDone()
                        return@thread
                    }
                } catch (e: Exception) {
                    // sin internet: se intenta más tarde
                }
            }
        }
    }
}
