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
        lastNight = NightMode.isNight(this)
        ZoeState.log("Modo noche: " + NightMode.describe(this))
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
        ZoeTrip.reset() // el Tbox acaba de arrancar con el carro: viaje nuevo
        ZoeState.addListener(listener)
        worker = thread(name = "zoe-worker") { loop() }
        onState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private var lastNight: Boolean? = null

    /** Registra cada cambio de modo noche (lo puede mandar el R-Link por Android Auto). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val n = NightMode.isNight(this)
        if (n != lastNight) { lastNight = n; ZoeState.log("Modo noche: " + NightMode.describe(this)) }
    }

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
                var speed: Double? = null
                var power: Double? = null
                var fastTime = 0L
                var lastFull = 0L
                var fastTurn = 0
                var resting = false
                var lowCount = 0
                var restStart = 0L
                var lastPowerAt = 0L
                fun withLive(d: ZoeData) = ZoeTrip.apply(
                    d.copy(speedKmh = speed, powerKw = power, fastTime = fastTime, rawTires = d.tires, tires = TireMap.apply(d.tires, prefs.tireMap)),
                )
                while (running) {
                    try {
                        if (ZoeState.appVisible) {
                            if (resting) { // con la app abierta siempre se lee
                                if (System.currentTimeMillis() - restStart > 10 * 60_000L) ZoeTrip.reset()
                                resting = false; lowCount = 0
                            }
                            // Cargando: no se lee velocidad ni potencia (no se usan); solo la batería.
                            val charging = reader.snapshot().charging
                            if (!charging) {
                                // App abierta: velocidad y potencia, y un pedacito de la batería, sin parar.
                                val (sp, pw) = reader.fast()
                                if (sp != null || pw != null) {
                                    speed = sp ?: speed; power = pw ?: power; fastTime = System.currentTimeMillis()
                                    pw?.let { PowerLog.add(it) }
                                }
                            } else {
                                speed = 0.0; power = null
                            }
                            // La batería, cada dos vueltas: menos tráfico, la velocidad sigue fluida (~3 por segundo).
                            val done = if (charging || fastTurn++ % 2 == 0) reader.step() else false
                            val d = withLive(reader.snapshot())
                            if (done) History.onData(this, d)
                            ZoeState.publish(d, "OK")
                            if (!pause(if (charging) 300L else 60L)) break
                        } else {
                            // Modo reposo: el carro está apagado; no se le pregunta nada para que pueda dormirse.
                            if (resting) {
                                if (reader.restCheck()) {
                                    resting = false; lowCount = 0; lastFull = 0L
                                    // Apagado más de 10 minutos: empieza un viaje nuevo.
                                    if (System.currentTimeMillis() - restStart > 10 * 60_000L) ZoeTrip.reset()
                                    ZoeState.log("Fin del reposo: el carro se activó")
                                } else {
                                    if (!waitRest()) break
                                    continue
                                }
                            }
                            // Solo la burbuja grande: lectura completa cada 10 s (cargando, cada 20 s).
                            // Burbuja compacta = modo ahorro: solo SOC, 12 V y enchufe, cada 30 s.
                            val light = prefs.compact
                            val every = when {
                                light -> 3 * FULL_MS
                                reader.snapshot().charging -> 2 * FULL_MS
                                else -> FULL_MS
                            }
                            if (System.currentTimeMillis() - lastFull >= every) {
                                val d = withLive(if (light) reader.light() else reader.read())
                                lastFull = System.currentTimeMillis()
                                History.onData(this, d)
                                // Carro apagado = sin carga y 12 V por debajo de 13 V (el DC-DC ya se apagó).
                                val off = !d.charging && (d.v12 ?: 14.0) < 13.0
                                lowCount = if (off) lowCount + 1 else 0
                                if (lowCount >= 3) {
                                    resting = true
                                    restStart = System.currentTimeMillis()
                                    ZoeState.log("Carro apagado: modo reposo (sin consultas)")
                                    ZoeState.publish(d, "En reposo")
                                } else {
                                    ZoeState.publish(d, "OK")
                                }
                            }
                            // Gráfica de potencia también con la burbuja grande: solo la potencia, cada 2 s (no se dibuja nada).
                            // Con la burbuja compacta no: modo ahorro, como antes.
                            if (!resting && !prefs.compact && !reader.snapshot().charging &&
                                System.currentTimeMillis() - lastPowerAt >= PowerLog.BIN_MS) {
                                lastPowerAt = System.currentTimeMillis()
                                reader.powerOnly()?.let { PowerLog.add(it) }
                            }
                            if (!pause(500)) break
                        }
                    } catch (e: ElmException) {
                        ZoeState.publish(null, "Sin datos: ${e.message}")
                        lastFull = 0L
                        if (!pause(20_000)) break
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

    /** Espera 5 s en reposo (escuchar no transmite nada), saliendo antes si se abre la app. */
    private fun waitRest(): Boolean {
        for (i in 0 until 5) {
            if (ZoeState.appVisible) return true
            if (!pause(1000)) return false
        }
        return true
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
        if (prefs.overlay && !ZoeState.appVisible) {
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
        const val FULL_MS = 10_000L
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
