package com.zoesoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pantalla principal de la app, pensada en horizontal. Se dibuja en un lienzo virtual de 1000 × 600
 * que se escala a la pantalla. Páginas: 0 Digital, 1 Detalle (se cambian deslizando).
 * Solo se dibuja la página visible y solo cuando llega un dato nuevo (o durante la animación de carga).
 */
class DashView(ctx: Context) : View(ctx) {
    var onSettings: (() -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onAutoKit: (() -> Unit)? = null
    /** true si AutoKit está instalado (se muestra el botón del teléfono). */
    var hasAutoKit = false
    private val prefs = Prefs(ctx)
    var page: Int = prefs.page.coerceIn(0, PAGES - 1)
        private set
    private var data: ZoeData? = null
    private var status = ""

    private val limiter = SpeedLimiter(ctx, prefs)

    fun update(d: ZoeData?, st: String) {
        data = d
        status = st
        limiter.update(if (d?.charging == true) null else speed(d))
        invalidate()
    }

    // ---------- escala ----------
    private val vw = 1000f
    private val vh = 600f
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sc = min(w / vw, h / vh)
        ox = (w - vw * sc) / 2
        oy = (h - vh * sc) / 2
    }

    // ---------- colores y pinturas ----------
    private val bgC = 0xFF0B0E11.toInt()
    private val textC = 0xFFE9EEF3.toInt()
    private val dimC = 0xFF8D97A1.toInt()
    private val subC = 0xFFB8C0C8.toInt()
    private val panelC = 0xFF151A1F.toInt()
    private val trackC = 0xFF1F242A.toInt()
    private val lightC = 0xFFE6ECF2.toInt()
    private val needleC = 0xFFFF5A36.toInt()

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val boltPath = Path().apply {
        moveTo(7f, 0f); lineTo(0f, 13f); lineTo(5.5f, 13f); lineTo(4f, 22f); lineTo(12f, 8f); lineTo(6.5f, 8f); lineTo(9f, 0f); close()
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        tp.textSize = size; tp.color = color; tp.textAlign = align
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, tp)
    }

    private fun tw(s: String, size: Float, bold: Boolean): Float {
        tp.textSize = size; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return tp.measureText(s)
    }

    /** SOC con entero grande y decimal/% pequeños. Devuelve la x donde termina. */
    private fun pct(c: Canvas, s: Double?, x: Float, base: Float, size: Float, color: Int, center: Boolean): Float {
        if (s == null) {
            val w = tw("--%", size, true)
            val x0 = if (center) x - w / 2 else x
            txt(c, "--%", x0, base, size, color, true)
            return x0 + w
        }
        val (i, d) = Fmt.socParts(s)
        val wi = tw(i, size, true); val wd = tw(",$d", size * 0.58f, true); val wp = tw("%", size * 0.62f, true)
        var cx = if (center) x - (wi + wd + wp) / 2 else x
        txt(c, i, cx, base, size, color, true); cx += wi
        txt(c, ",$d", cx, base, size * 0.58f, color, true); cx += wd
        txt(c, "%", cx, base, size * 0.62f, color, true); cx += wp
        return cx
    }

    private fun bolt(c: Canvas, x: Float, top: Float, h: Float, alpha: Int = 255) {
        c.save(); c.translate(x, top); val k = h / 22f; c.scale(k, k)
        fill.color = Colors.AMBER; fill.alpha = alpha
        c.drawPath(boltPath, fill)
        fill.alpha = 255
        c.restore()
    }

    private fun car(c: Canvas, cx: Float, top: Float, h: Float, d: ZoeData?, pulse: Int, showPack: Boolean = true) {
        val w = h * CarArt.W / CarArt.H
        val s = d?.displaySoc
        CarArt.draw(
            c, cx - w / 2, top, h, s, Colors.soc(s), CarArt.tireColors(d, prefs.refPsi),
            showPack = showPack, packAlpha = pulse, lights = CarArt.lightsOn(d, prefs.lightsMode),
            bolt = d?.charging == true,
        )
    }

    // ---------- datos derivados ----------
    private fun live(d: ZoeData?) = d != null && System.currentTimeMillis() - d.fastTime < 10_000
    private fun speed(d: ZoeData?) = if (live(d)) d?.speedKmh else null
    private fun power(d: ZoeData?) = if (live(d)) d?.powerKw else null
    /** Última vez que se tocó la pantalla: cargando, tras 5 min sin tocar, las animaciones se detienen. */
    private var lastTouch = System.currentTimeMillis()
    private fun chargeAnim(d: ZoeData?) = d?.charging == true && System.currentTimeMillis() - lastTouch < 5 * 60_000L

    private fun pulseAlpha(d: ZoeData?): Int {
        if (!chargeAnim(d)) return 255
        val t = (System.currentTimeMillis() % 1600L) / 1600.0
        return (150 + 105 * (0.5 + 0.5 * cos(t * 2 * Math.PI))).toInt()
    }
    private fun eta(d: ZoeData, target: Double): String? = d.minutesTo(target)?.let { Fmt.dur(it) }

    private val tick = Runnable { invalidate() }

    // ---------- dibujo ----------
    override fun onDraw(c: Canvas) {
        c.drawColor(bgC)
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        val d = data
        if (graphBig && page == 1) {
            drawPowerGraph(c, big = true)
        } else {
            if (page == 0) drawDigital(c, d) else drawDetail(c, d)
            drawHeader(c, d)
            drawDots(c)
            drawHome(c)
            drawAutoKit(c)
        }
        c.restore()
        // Un solo temporizador (no se acumulan): animación de carga o segundero del reloj.
        // Un solo temporizador: animación de carga, o cambio de minuto del reloj.
        // Un solo temporizador, solo para animaciones (carga o señal parpadeando).
        removeCallbacks(tick)
        // ~9 cuadros por segundo: se ve suave y la caja trabaja menos de la mitad que a 20.
        if (page == 0 && (chargeAnim(d) || limiter.over)) postDelayed(tick, ANIM_MS)
    }

    private fun drawHeader(c: Canvas, d: ZoeData?) {
        val st = when {
            !ZoeService.running -> "detenido"
            d?.charging == true -> "cargando"
            status == "OK" -> "conectado"
            else -> status
        }
        val title = if (page == 1) "Detalle" else "Zoe · $st"
        txt(c, title.take(40), 24f, 40f, 26f, dimC)
        // Engranaje (abre ajustes)
        val gx = 960f; val gy = 34f
        line.strokeWidth = 5f; line.color = dimC
        c.drawCircle(gx, gy, 11f, line.apply { style = Paint.Style.STROKE })
        line.strokeWidth = 6f
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            c.drawLine(gx + 14 * cos(a).toFloat(), gy + 14 * sin(a).toFloat(), gx + 19 * cos(a).toFloat(), gy + 19 * sin(a).toFloat(), line)
        }
    }

    /** Señal de límite (toque para cambiar: Auto → 50 → 80 → apagado). Parpadea suave si te pasas. */
    private fun drawLimitSign(c: Canvas) {
        val lim = limiter.limit
        val cx = SIGN_X; val cy = SIGN_Y; val r = 44f
        if (lim == null) {
            line.color = 0xFF3A4048.toInt(); line.strokeWidth = 6f
            c.drawCircle(cx, cy, r, line)
            txt(c, "sin", cx, cy - 4, 24f, dimC, align = Paint.Align.CENTER)
            txt(c, "límite", cx, cy + 22, 22f, dimC, align = Paint.Align.CENTER)
            return
        }
        val a = if (limiter.over) {
            val t = (System.currentTimeMillis() % 1000L) / 1000.0
            (120 + 135 * (0.5 + 0.5 * cos(t * 2 * Math.PI))).toInt()
        } else 255
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = a
        c.drawCircle(cx, cy, r, fill)
        line.color = 0xFFE53935.toInt(); line.alpha = a; line.strokeWidth = 11f
        c.drawCircle(cx, cy - 0f, r - 5f, line)
        line.alpha = 255; fill.alpha = 255
        txt(c, lim.toString(), cx, cy + 14, 40f, 0xFF111111.toInt(), true, Paint.Align.CENTER)
        if (limiter.mode == SpeedLimiter.AUTO) txt(c, "AUTO", cx, cy + r + 22, 20f, dimC, true, Paint.Align.CENTER)
    }

    /** Teléfono blanco abajo, al otro lado del carro (a espejo de la casita): abre AutoKit (CarPlay). */
    private fun drawAutoKit(c: Canvas) {
        if (!hasAutoKit || page != 0) return // en Detalle el carro ocupa ese lugar
        val x = PHONE_X; val y = 548f; val k = 1.3f
        fill.color = textC
        c.drawRoundRect(x - 16 * k, y - 26 * k, x + 16 * k, y + 26 * k, 8f * k, 8f * k, fill)
        fill.color = bgC
        c.drawRoundRect(x - 12 * k, y - 19 * k, x + 12 * k, y + 14 * k, 3f * k, 3f * k, fill) // pantalla
        c.drawCircle(x, y + 20 * k, 3f * k, fill)                                          // botón
    }

    /** Casita blanca abajo a la izquierda (más grande, fácil de tocar): vuelve al inicio del Tbox. */
    private fun drawHome(c: Canvas) {
        val x = 54f; val y = 550f; val k = 1.3f
        val house = Path().apply {
            moveTo(x, y - 30 * k); lineTo(x + 27 * k, y - 6 * k); lineTo(x + 18 * k, y - 6 * k); lineTo(x + 18 * k, y + 17 * k)
            lineTo(x - 18 * k, y + 17 * k); lineTo(x - 18 * k, y - 6 * k); lineTo(x - 27 * k, y - 6 * k); close()
        }
        fill.color = textC
        c.drawPath(house, fill)
        fill.color = bgC
        c.drawRoundRect(x - 6 * k, y + 2 * k, x + 6 * k, y + 17 * k, 3f * k, 3f * k, fill) // puerta
    }

    private fun drawDots(c: Canvas) {
        for (i in 0 until PAGES) {
            fill.color = if (i == page) textC else 0xFF3A4048.toInt()
            c.drawCircle(500f + (i - (PAGES - 1) / 2f) * 22f, 588f, 5f, fill)
        }
    }

    /** B · Digital: carro grande con presiones, SOC, velocidad grande y barra de potencia (o la carga). */
    private fun drawDigital(c: Canvas, d: ZoeData?) {
        val charging = d?.charging == true
        // Columna izquierda: reloj analógico arriba y carro más pequeño pegado abajo.
        val colX = 235f
        drawTrip(c, colX, d)
        val carH = 322f
        val carW = carH * CarArt.W / CarArt.H
        val carTop = 574f - carH
        car(c, colX, carTop, carH, d, pulseAlpha(d))
        // Las presiones van pegadas al borde exterior de cada llanta.
        tirePsi(c, d, colX - carW / 2 + carW * 0.5f / CarArt.W - 10f, colX - carW / 2 + carW * 59.5f / CarArt.W + 10f, carTop, carH, 30f)

        val x0 = 470f
        val s = d?.displaySoc
        val end = pct(c, s, x0, 135f, 84f, Colors.soc(s), center = false)
        if (charging) bolt(c, end + 12f, 75f, 56f, pulseAlpha(d))

        val l2 = listOfNotNull(
            d?.extTemp?.let { "Ext. " + Fmt.f(it, 0) + " °C" }, d?.cabinTemp?.let { "Int. " + Fmt.f(it, 0) + " °C" },
        ).joinToString(" · ")
        // Línea 1: autonomía · Batería + temperatura con color
        val lc = 0xFFC8D0D8.toInt()
        var lx = x0
        d?.rangeKm?.let { val t = "$it km"; txt(c, t, lx, 182f, 28f, lc); lx += tw(t, 28f, false) }
        d?.tempC?.let {
            val pre = (if (lx > x0) " · " else "") + "Batería "
            txt(c, pre, lx, 182f, 28f, lc); lx += tw(pre, 28f, false)
            txt(c, Fmt.f(it, 0) + " °C", lx, 182f, 28f, Colors.batTemp(it), true)
        }
        txt(c, l2, x0, 218f, 28f, 0xFFC8D0D8.toInt())

        val bx0 = 470f; val bx1 = 950f
        if (charging && d != null) {
            val kw = d.chargingKw?.let { Fmt.f(it, 1) } ?: "--"
            txt(c, kw, x0, 360f, 100f, Colors.AMBER, true)
            txt(c, "kW entrando", x0 + tw(kw, 100f, true) + 12f, 360f, 34f, dimC)
            // barra de progreso con flujo
            fill.color = trackC
            c.drawRoundRect(bx0, 400f, bx1, 424f, 12f, 12f, fill)
            val fx = bx0 + (bx1 - bx0) * ((s ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
            fill.color = Colors.GREEN
            c.drawRoundRect(bx0, 400f, fx, 424f, 12f, 12f, fill)
            c.save()
            c.clipRect(bx0, 400f, fx, 424f)
            fill.color = 0x59FFFFFF
            val off = if (chargeAnim(d)) (System.currentTimeMillis() % 1000L) / 1000f * 48f else 0f
            var sx = bx0 - 48f + off
            while (sx < fx) { c.drawRect(sx, 400f, sx + 14f, 424f, fill); sx += 48f }
            c.restore()
            val mk = bx0 + (bx1 - bx0) * 0.8f
            line.strokeWidth = 4f; line.color = textC
            c.drawLine(mk, 392f, mk, 432f, line)
            txt(c, eta(d, 80.0)?.let { "80 % en $it" } ?: "Sobre 80 %", x0, 466f, 28f, textC)
            eta(d, 100.0)?.let { txt(c, "100 % en $it", bx1, 466f, 26f, dimC, align = Paint.Align.RIGHT) }
            val since = History.startHour(prefs)?.let { " desde las $it" } ?: ""
            txt(c, "Cargado: " + Fmt.f(prefs.chgKwh.toDouble(), 1) + " kWh" + since, x0, 504f, 26f, dimC)
        } else {
            val sp = speed(d)
            val st = sp?.let { Fmt.f(it, 0) } ?: "--"
            txt(c, st, x0, 345f, 116f, if (limiter.over) Colors.AMBER else textC, true)
            txt(c, "km/h", x0 + tw(st, 116f, true) + 12f, 345f, 34f, dimC)
            drawLimitSign(c)
            // Consumo instantáneo grande, justo debajo de la velocidad (suavizado a 3 s).
            val inst = if (live(d)) smoothInst(d) else null
            val iv = inst?.let { Fmt.f(it, 1) } ?: "--"
            txt(c, iv, x0, 412f, 54f, textC, true)
            txt(c, " kWh/100 km", x0 + tw(iv, 54f, true), 412f, 26f, 0xFFC8D0D8.toInt())
            txt(c, "Instantáneo", bx1, 412f, 26f, dimC, align = Paint.Align.RIGHT)
            val pw = power(d)
            txt(c, "regen", bx0, 466f, 30f, Colors.GREEN, true)
            txt(c, pw?.let { Fmt.f(it, 1) + " kW" } ?: "-- kW", bx1, 466f, 30f, textC, true, Paint.Align.RIGHT)
            fill.color = 0xFF2A3038.toInt()
            c.drawRoundRect(bx0, 484f, bx1, 504f, 10f, 10f, fill)
            // Escala −30 kW (regeneración) a +70 kW: el cero queda al 30 %.
            val zx = bx0 + (bx1 - bx0) * 0.30f
            if (pw != null) {
                val vx = bx0 + (bx1 - bx0) * (((pw + 30) / 100).coerceIn(0.0, 1.0)).toFloat()
                fill.color = if (pw < 0) Colors.GREEN else lightC
                c.drawRoundRect(min(zx, vx), 486f, maxOf(zx, vx), 504f, 9f, 9f, fill)
            }
            line.strokeWidth = 4f; line.color = textC
            c.drawLine(zx, 474f, zx, 514f, line)
            // Promedio: una sola línea discreta al final.
            var px = x0
            txt(c, "Promedio ", px, 560f, 26f, dimC); px += tw("Promedio ", 26f, false)
            val avg = d?.avgConsumption?.let { Fmt.f(it, 1) } ?: "--"
            txt(c, avg, px, 560f, 26f, textC, true); px += tw(avg, 26f, true)
            txt(c, " kWh/100 km", px, 560f, 26f, 0xFFC8D0D8.toInt())
        }
    }

    /** Promedio móvil de los últimos 3 s del consumo instantáneo, para que no salte tanto. */
    private val instBuf = ArrayDeque<Pair<Long, Double>>()
    private var instLastTs = 0L
    private fun smoothInst(d: ZoeData?): Double? {
        val v = d?.instantConsumption
        if (d == null || v == null) { instBuf.clear(); return null }
        val now = System.currentTimeMillis()
        if (d.fastTime != instLastTs) { instLastTs = d.fastTime; instBuf.addLast(d.fastTime to v) }
        while (instBuf.isNotEmpty() && now - instBuf.first().first > 3_000L) instBuf.removeFirst()
        if (instBuf.isEmpty()) return v
        return instBuf.sumOf { it.second } / instBuf.size
    }

    /** Página 2: batería, llantas, cargas y gráfica de potencia de los últimos 15 min. */
    private fun drawDetail(c: Canvas, d: ZoeData?) {
        val lc = 0xFFC8D0D8.toInt()
        // Batería (tabla)
        fill.color = panelC
        c.drawRoundRect(20f, 70f, 360f, 290f, 20f, 20f, fill)
        txt(c, "Batería", 40f, 104f, 22f, dimC)
        fun row(x0: Float, x1: Float, y: Float, l: String, v: String, col: Int = textC) {
            txt(c, l, x0, y, 24f, dimC); txt(c, v, x1, y, 24f, col, true, Paint.Align.RIGHT)
        }
        row(40f, 340f, 142f, "SOH", d?.soh?.let { Fmt.f(it, 1) + " %" } ?: "--", Colors.GREEN)
        row(40f, 340f, 178f, "Dif. celdas", d?.cellDiffMv?.let { "$it mV" } ?: "--", if ((d?.cellDiffMv ?: 0) > 50) Colors.AMBER else textC)
        row(40f, 340f, 214f, "Batería 12 V", d?.v12?.let { Fmt.f(it, 1) + " V" } ?: "--", if ((d?.v12 ?: 13.0) < 12.0) Colors.AMBER else textC)
        row(40f, 340f, 250f, "Temp. batería", Fmt.temp(d?.tempC), Colors.batTemp(d?.tempC))
        row(40f, 340f, 280f, "SOC real", d?.realSoc?.let { Fmt.f(it, 1) + " %" } ?: "--")

        // Llantas
        txt(c, "Llantas (PSI)", 500f, 92f, 22f, dimC, align = Paint.Align.CENTER)
        val h = 180f; val w = h * CarArt.W / CarArt.H
        val top = 106f
        car(c, 500f, top, h, d, 255, showPack = false)
        tirePsi(c, d, 500f - w / 2 + w * 0.5f / CarArt.W - 10f, 500f - w / 2 + w * 59.5f / CarArt.W + 10f, top, h, 28f)

        // Cargas
        fill.color = panelC
        c.drawRoundRect(640f, 70f, 980f, 290f, 20f, 20f, fill)
        txt(c, "Cargas", 660f, 104f, 22f, dimC)
        val state = when {
            d == null || d.plugged == null -> "--"
            d.charging -> "Cargando " + (d.chargingKw?.let { Fmt.f(it, 1) + " kW" } ?: "")
            d.plugged == true -> "Conectado"
            else -> "Desconectado"
        }
        txt(c, state, 960f, 106f, 26f, if (d?.charging == true) Colors.AMBER else textC, true, Paint.Align.RIGHT)
        val day = prefs.lastChgDay
        row(660f, 960f, 170f, "Última · " + (day ?: "--"), if (day != null) "+" + Fmt.f(prefs.lastChgKwh.toDouble(), 1) + " kWh" else "--")
        drawPostToCar(c, 660f, 224f)
        val km = prefs.sinceKm.toDouble()
        txt(c, Fmt.f(km, 0) + " km", 960f, 224f, 24f, textC, true, Paint.Align.RIGHT)
        val prom = if (km >= 1.0) Fmt.f(prefs.sinceKwh / km * 100, 1) + " kWh/100" else "--"
        row(660f, 960f, 274f, "Prom.", prom)

        drawPowerGraph(c)
    }

    /** Ícono "poste · · · · carrito": km desde la última carga. */
    private fun drawPostToCar(c: Canvas, x: Float, base: Float) {
        fill.color = dimC
        c.drawRoundRect(x + 2, base - 26, x + 17, base + 2, 3f, 3f, fill)          // poste
        fill.color = panelC
        c.drawRoundRect(x + 5, base - 22, x + 14, base - 16, 1f, 1f, fill)        // pantallita
        fill.color = dimC
        c.drawRoundRect(x - 1, base + 1, x + 20, base + 5, 2f, 2f, fill)          // base
        for (i in 0 until 4) c.drawCircle(x + 30 + i * 10f, base - 9, 2.5f, fill) // puntos
        val p = Path()
        val cx = x + 69
        p.moveTo(cx, base - 3); p.lineTo(cx + 4, base - 13); p.quadTo(cx + 6, base - 17, cx + 10, base - 17)
        p.lineTo(cx + 22, base - 17); p.quadTo(cx + 26, base - 17, cx + 28, base - 13); p.lineTo(cx + 32, base - 3)
        p.quadTo(cx + 34, base - 3, cx + 34, base); p.lineTo(cx + 34, base + 3); p.lineTo(cx - 2, base + 3)
        p.lineTo(cx - 2, base); p.quadTo(cx - 2, base - 3, cx, base - 3); p.close()
        c.drawPath(p, fill)
        c.drawCircle(cx + 6, base + 4, 3.5f, fill); c.drawCircle(cx + 26, base + 4, 3.5f, fill)
    }

    /** true = la gráfica de potencia ocupa toda la pantalla (se abre tocándola en la hoja 2). */
    private var graphBig = false

    /** Potencia de los últimos 15 min: verde hasta 30 kW, ámbar encima, azul regenerando. Escala +70 a −40. */
    private fun drawPowerGraph(c: Canvas, big: Boolean = false) {
        val gx0 = 40f
        val gx1 = if (big) 920f else 930f
        val yTop = if (big) 100f else 345f
        val yBot = if (big) 500f else 500f
        val lx = 960f
        val ls = if (big) 20f else 16f
        if (big) {
            txt(c, "Potencia · últimos 10 min", 40f, 58f, 28f, dimC)
            line.strokeWidth = 4f; line.color = dimC
            c.drawLine(938f, 34f, 962f, 58f, line); c.drawLine(962f, 34f, 938f, 58f, line)
        } else {
            fill.color = panelC
            c.drawRoundRect(20f, 302f, 980f, 512f, 20f, 20f, fill)
            txt(c, "Potencia · últimos 10 min", 40f, 332f, 22f, dimC)
        }
        // Escala: normalmente +15 a −15; si algo se sale, se abre hasta cubrirlo (máx. +70 / −40), en pasos de 5.
        val v = PowerLog.series()
        val hi = v.filterNotNull().maxOrNull() ?: 0.0
        val lo = v.filterNotNull().minOrNull() ?: 0.0
        val top = maxOf(15.0, Math.ceil(hi / 5) * 5).coerceAtMost(70.0)
        val bot = minOf(-15.0, Math.floor(lo / 5) * 5).coerceAtLeast(-40.0)
        fun y(kw: Double) = (yTop + (top - kw.coerceIn(bot, top)) / (top - bot) * (yBot - yTop)).toFloat()
        val y0 = y(0.0)
        val show15 = top > 15.0
        val y15 = y(15.0)
        // guías
        if (big) {
            line.strokeWidth = 1.5f; line.color = 0x80FFFFFF.toInt()
            c.drawLine(gx0, y(top), gx1, y(top), line)
            c.drawLine(gx0, y(bot), gx1, y(bot), line)
            val tx = (gx0 + gx1) / 2; c.drawLine(tx, yTop, tx, yBot, line)
        }
        // eje vertical a la izquierda
        line.strokeWidth = 2.5f; line.color = 0xFFFFFFFF.toInt()
        c.drawLine(gx0, yTop, gx0, yBot, line)
        if (show15) {
            line.strokeWidth = 2f; line.color = 0xB3FFFFFF.toInt()
            var gx = gx0
            while (gx < gx1) { c.drawLine(gx, y15, minOf(gx + 6f, gx1), y15, line); gx += 14f }
            txt(c, "15", lx, y15 + ls * 0.35f, ls, textC, align = Paint.Align.RIGHT)
        }
        line.strokeWidth = 2.5f; line.color = 0xFFFFFFFF.toInt()
        c.drawLine(gx0, y0, gx1, y0, line)
        fun kwLabel(k: Double) = (if (k < 0) "−" else "+") + Math.round(Math.abs(k))
        txt(c, kwLabel(top), lx, y(top) + ls * 0.35f, ls, textC, align = Paint.Align.RIGHT)
        txt(c, "0", lx, y0 + ls * 0.35f, ls, textC, align = Paint.Align.RIGHT)
        txt(c, kwLabel(bot), lx, y(bot) + ls * 0.35f, ls, textC, align = Paint.Align.RIGHT)
        if (big) {
            txt(c, "−10 min", gx0, 540f, 20f, textC)
            txt(c, "−5", (gx0 + gx1) / 2, 540f, 20f, textC, align = Paint.Align.CENTER)
            txt(c, "ahora", gx1, 540f, 20f, textC, align = Paint.Align.RIGHT)
            txt(c, "Toca para volver", 40f, 580f, 20f, 0xFF5A636C.toInt())
        }

        val n = v.size
        val dx = (gx1 - gx0) / (n - 1)
        line.strokeWidth = if (big) 3f else 2.5f
        for (i in 1 until n) {
            val a = v[i - 1] ?: continue
            val b = v[i] ?: continue
            val mid = (a + b) / 2
            line.color = when { mid < 0 -> BLUE_REGEN; mid <= 15 -> Colors.GREEN; mid <= 32 -> ORANGE_PWR; else -> RED_PWR }
            c.drawLine(gx0 + (i - 1) * dx, y(a), gx0 + i * dx, y(b), line)
        }
        if (v.all { it == null }) txt(c, "Se llena mientras manejas", (gx0 + gx1) / 2, y0 - 14f, 22f, dimC, align = Paint.Align.CENTER)
    }

    /** Bloque "Viaje": km grandes y, debajo, kWh y consumo del viaje (desde que se prendió el carro). */
    private val inkR = android.graphics.Rect()

    /** Borde izquierdo y derecho reales de la tinta (sin espacios ni márgenes de la fuente). */
    private fun ink(str: String, size: Float, bold: Boolean): Pair<Float, Float> {
        tp.textSize = size; tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.getTextBounds(str, 0, str.length, inkR)
        return inkR.left.toFloat() to inkR.right.toFloat()
    }

    /** Texto centrado por su tinta visible, no por el ancho tipográfico. */
    private fun txtInk(c: Canvas, str: String, cx: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val (l, r) = ink(str, size, bold)
        txt(c, str, cx - (l + r) / 2, y, size, color, bold)
    }

    private fun drawTrip(c: Canvas, cx: Float, d: ZoeData?) {
        txtInk(c, "Viaje", cx, 84f, 26f, dimC)
        val km = d?.tripKm
        val kStr = km?.let { Fmt.f(it, 1) } ?: "--"
        val wNum = tw(kStr, 84f, true)
        val gap = tw(" ", 34f, false)
        val (nl, _) = ink(kStr, 84f, true)
        val (_, ur) = ink("km", 34f, false)
        // Centra el grupo "12,3 km" por la tinta: desde el primer trazo del número hasta el final de "km".
        val left = nl
        val right = wNum + gap + ur
        val x = cx - (left + right) / 2
        txt(c, kStr, x, 163f, 84f, textC, true)
        txt(c, "km", x + wNum + gap, 163f, 34f, 0xFFC8D0D8.toInt())
        val kwh = d?.tripKwh
        val cons = if (km != null && kwh != null && km >= 0.5) Fmt.f(kwh / km * 100, 1) else "--"
        val line2 = (kwh?.let { Fmt.f(it, 1) } ?: "--") + " kWh · " + cons + " kWh/100"
        txtInk(c, line2, cx, 208f, 28f, 0xFFC8D0D8.toInt())
    }

    /** Presiones junto a cada llanta, por fuera del carro (izquierda alineada hacia el carro). */
    private fun tirePsi(c: Canvas, d: ZoeData?, xl: Float, xr: Float, top: Float, h: Float, size: Float) {
        val t = d?.tires
        val yF = top + h * 25.5f / CarArt.H + size * 0.35f
        val yR = top + h * 86.5f / CarArt.H + size * 0.35f
        val ref = prefs.refPsi
        fun one(v: Double?, x: Float, y: Float, al: Paint.Align) {
            val col = if (v == null) dimC else Colors.tire(v, ref)
            txt(c, v?.let { Math.round(it).toString() } ?: "--", x, y, size, col, true, al)
        }
        one(t?.fl, xl, yF, Paint.Align.RIGHT); one(t?.fr, xr, yF, Paint.Align.LEFT)
        one(t?.rl, xl, yR, Paint.Align.RIGHT); one(t?.rr, xr, yR, Paint.Align.LEFT)
    }

    // ---------- toques: deslizar cambia de página; engranaje abre ajustes ----------
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 500) {
                go(if (velocityX < 0) 1 else -1)
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val x = (e.x - ox) / sc
            val y = (e.y - oy) / sc
            if (graphBig) { graphBig = false; invalidate(); return true } // cualquier toque cierra la gráfica grande
            when {
                page == 1 && x in 20f..980f && y in 302f..512f && !(x < 120 && y > 500) -> { graphBig = true; invalidate() }
                x > 900 && y < 90 -> onSettings?.invoke()
                page == 0 && data?.charging != true && abs(x - SIGN_X) < 60 && abs(y - SIGN_Y) < 60 -> { limiter.cycle(); invalidate() }
                x < 120 && y > 500 -> onHome?.invoke()
                page == 0 && hasAutoKit && x in (PHONE_X - 55f)..(PHONE_X + 55f) && y > 495 -> onAutoKit?.invoke()
                y > 560 && x in 420f..580f -> go(if (x < 500) -1 else 1) // tocar los puntitos también cambia
            }
            return true
        }
    })

    private fun go(delta: Int) {
        graphBig = false
        page = (page + delta + PAGES) % PAGES
        prefs.page = page
        invalidate()
    }

    companion object {
        const val PAGES = 2
        const val BLUE_REGEN = 0xFF4A90D9.toInt()
        const val ORANGE_PWR = 0xFFF08A24.toInt()
        const val RED_PWR = 0xFFE04848.toInt()
        /** Intervalo de las animaciones (latido de carga, flujo, señal parpadeando). */
        const val ANIM_MS = 110L
        /** Posición del teléfono: a la derecha del carro, a la misma altura que la casita. */
        const val PHONE_X = 418f
        /** Señal del limitador, a la derecha de la velocidad. */
        const val SIGN_X = 892f
        const val SIGN_Y = 305f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val wasIdle = !chargeAnim(data)
            lastTouch = System.currentTimeMillis()
            if (wasIdle) invalidate() // reanuda las animaciones
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
