package com.zoesoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

/** Servicio en primer plano: mantiene la conexión con el Vgate y consulta el carro cada N segundos. */
class ZoeService : Service() {
    private var worker: Thread? = null
    @Volatile private var transport: Transport? = null
    private lateinit var overlay: Overlay
    private lateinit var prefs: Prefs
    private val listener: () -> Unit = { onState() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = Overlay(this)
        createChannel()
        try {
            val n = notification("Iniciando…", "")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NID, n)
            }
        } catch (e: Exception) {
            ZoeState.log("No pude iniciar el servicio: ${e.message}")
            stopSelf()
            return
        }
        running = true
        ZoeState.addListener(listener)
        worker = thread(name = "zoe-worker") { loop() }
        onState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        ZoeState.removeListener(listener)
        worker?.interrupt()
        transport?.close()
        overlay.hide()
        ZoeState.publish(null, "Detenido")
        SocWidget.push(this)
        super.onDestroy()
    }

    private fun loop() {
        while (running) {
            val addr = prefs.address
            if (addr == null) {
                ZoeState.publish(null, "Elige el scanner en la app")
                if (!pause(5000)) break
                continue
            }
            var t: Transport? = null
            try {
                ZoeState.publish(null, "Conectando…")
                t = Connector.open(this, addr) { ZoeState.log(it) }
                transport = t
                val elm = Elm327(t) { ZoeState.log(it) }
                elm.init()
                val reader = ZoeReader(elm) { ZoeState.log(it) }
                ZoeState.publish(null, "Conectado")
                var cur: ZoeData? = null
                var lastSlow = 0L
                while (running) {
                    // Lectura completa cada N segundos.
                    if (cur == null || System.currentTimeMillis() - lastSlow >= prefs.intervalSec * 1000L) {
                        try {
                            val prev = cur
                            val d = reader.read().copy(
                                speedKmh = prev?.speedKmh, powerKw = prev?.powerKw, fastTime = prev?.fastTime ?: 0L,
                            )
                            cur = d
                            lastSlow = System.currentTimeMillis()
                            History.onData(this, d)
                            ZoeState.publish(d, "OK")
                        } catch (e: ElmException) {
                            cur = null
                            ZoeState.publish(null, "Sin datos: ${e.message}")
                            if (!pause(20_000)) break
                            continue
                        }
                    }
                    // Con la app abierta: velocidad y potencia seguido. Si no, solo espera.
                    val c = cur
                    if (ZoeState.appVisible && c != null) {
                        val (sp, pw) = reader.fast()
                        val u = c.copy(
                            speedKmh = sp ?: c.speedKmh, powerKw = pw ?: c.powerKw,
                            fastTime = if (sp != null || pw != null) System.currentTimeMillis() else c.fastTime,
                        )
                        cur = u
                        ZoeState.publish(u, "OK")
                        if (!pause(200)) break
                    } else {
                        if (!pause(1000)) break
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                ZoeState.log("Conexión: ${e.message}")
                ZoeState.publish(null, "Reconectando…")
                t?.close()
                transport = null
                if (!pause(10_000)) break
            } finally {
                t?.close()
                transport = null
            }
        }
    }

    /** Espera; devuelve false si el servicio se detuvo. */
    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        running
    } catch (e: InterruptedException) {
        false
    }

    private var lastNotice = ""

    private fun onState() {
        val d = ZoeState.data
        if (prefs.overlay) {
            overlay.show()
            overlay.update(d, ZoeState.status)
        } else {
            overlay.hide()
        }
        // El widget y la notificación solo se tocan cuando cambia la batería o el estado (no con cada velocidad).
        val title = "Zoe " + Fmt.socDec(d)
        val sub = Fmt.short(d).ifEmpty { ZoeState.status }
        val key = "$title|$sub|${ZoeState.status}"
        if (key == lastNotice) return
        lastNotice = key
        SocWidget.push(this)
        getSystemService(NotificationManager::class.java)?.notify(NID, notification(title, sub))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Lectura del Zoe", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun notification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val NID = 1
        const val CHANNEL = "zoe"
        @Volatile var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ZoeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ZoeService::class.java))
        }
    }
}
