package com.zoesoc

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Carro visto desde arriba (frente arriba), dibujado en un espacio de 60 × 116:
 * llantas de colores según la presión, batería en el piso que se llena según el SOC, faros y stops.
 */
object CarArt {
    const val W = 60f
    const val H = 116f

    private val body = Path().apply {
        moveTo(18f, 4f); quadTo(30f, 1f, 42f, 4f); quadTo(50f, 7f, 51f, 16f)
        lineTo(52f, 96f); quadTo(52f, 108f, 42f, 110f); lineTo(18f, 110f)
        quadTo(8f, 108f, 8f, 96f); lineTo(9f, 16f); quadTo(10f, 7f, 18f, 4f); close()
    }
    private val windshield = Path().apply {
        moveTo(14f, 26f); quadTo(30f, 20f, 46f, 26f); lineTo(45f, 31f); lineTo(15f, 31f); close()
    }
    private val rearGlass = Path().apply {
        moveTo(15f, 86f); lineTo(45f, 86f); lineTo(46f, 94f); quadTo(30f, 98f, 14f, 94f); close()
    }
    private val heads = Path().apply {
        moveTo(11f, 10f); quadTo(13f, 5f, 21f, 4.5f); lineTo(22f, 7.5f); quadTo(15f, 8f, 12.5f, 11f); close()
        moveTo(49f, 10f); quadTo(47f, 5f, 39f, 4.5f); lineTo(38f, 7.5f); quadTo(45f, 8f, 47.5f, 11f); close()
    }
    private val tails = Path().apply {
        moveTo(10f, 101f); quadTo(11f, 107f, 18f, 109f); lineTo(19f, 106f); quadTo(14f, 105f, 12.5f, 100f); close()
        moveTo(50f, 101f); quadTo(49f, 107f, 42f, 109f); lineTo(41f, 106f); quadTo(46f, 105f, 47.5f, 100f); close()
    }
    /** Rayo dentro de la batería (12 × 22 desplazado). */
    private val bolt = Path().apply {
        moveTo(32f, 44f); lineTo(22f, 58f); lineTo(28f, 58f); lineTo(25f, 70f); lineTo(35f, 53f); lineTo(29f, 53f); close()
    }

    private val tireP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9DEE3.toInt() }
    private val glassP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C333B.toInt() }
    private val glassLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0xFF2C333B.toInt()
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f; color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND
    }
    private val packEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA3AD.toInt() }
    private val packFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val packLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF3A4048.toInt()
    }
    private val boltP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.AMBER }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.5f; color = 0xFF8A929B.toInt()
    }
    private val headGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val tailP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tailGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF3B3B }

    /** Luces encendidas: según el carro si se pudo leer; si no (o si se eligió), siempre. */
    fun lightsOn(d: ZoeData?, mode: Int): Boolean = mode == 1 || (d?.lightsOn ?: true)

    /**
     * tires = colores [del. izq., del. der., tras. izq., tras. der.].
     * packAlpha hace el latido al cargar; bolt dibuja el rayo sobre la batería.
     */
    fun draw(
        c: Canvas, left: Float, top: Float, height: Float,
        soc: Double?, socColor: Int, tires: IntArray,
        showPack: Boolean = true, packAlpha: Int = 255, lights: Boolean = true, bolt: Boolean = false,
    ) {
        val k = height / H
        c.save()
        c.translate(left, top)
        c.scale(k, k)
        val tp = arrayOf(floatArrayOf(3f, 18f), floatArrayOf(50f, 18f), floatArrayOf(3f, 79f), floatArrayOf(50f, 79f))
        for (i in 0 until 4) {
            tireP.color = tires[i]
            c.drawRoundRect(tp[i][0], tp[i][1], tp[i][0] + 7f, tp[i][1] + 15f, 2.5f, 2.5f, tireP)
        }
        c.drawPath(body, bodyP)
        if (showPack) {
            val l = 16f; val r = 44f; val t = 34f; val b = 80f
            c.drawRoundRect(l, t, r, b, 3f, 3f, packEmpty)
            val s = soc
            if (s != null) {
                val y = b - (b - t) * (s.coerceIn(0.0, 100.0) / 100.0).toFloat()
                packFill.color = socColor
                packFill.alpha = packAlpha
                c.drawRoundRect(l, y, r, b, 3f, 3f, packFill)
            }
            c.drawRoundRect(l, t, r, b, 3f, 3f, packLine)
            if (bolt) {
                boltP.alpha = packAlpha
                c.drawPath(this.bolt, boltP)
                c.drawPath(this.bolt, edgeP)
            }
        }
        c.drawPath(windshield, glassP); c.drawPath(windshield, glassLine)
        c.drawPath(rearGlass, glassP); c.drawPath(rearGlass, glassLine)
        c.drawPath(body, outline)
        if (lights) {
            c.drawOval(10f, 3f, 24f, 11f, headGlow); c.drawOval(36f, 3f, 50f, 11f, headGlow)
            c.drawOval(8f, 102f, 22f, 110f, tailGlow); c.drawOval(38f, 102f, 52f, 110f, tailGlow)
        }
        headP.color = if (lights) 0xFFFFF6C8.toInt() else 0xFFB8BEC5.toInt()
        tailP.color = if (lights) 0xFFFF3B3B.toInt() else 0x8CFF3B3B.toInt()
        c.drawPath(heads, headP)
        c.drawPath(heads, edgeP)
        c.drawPath(tails, tailP)
        c.restore()
    }

    fun tireColors(d: ZoeData?, ref: Int): IntArray {
        val t = d?.tires
        return intArrayOf(
            Colors.tire(t?.fl, ref), Colors.tire(t?.fr, ref), Colors.tire(t?.rl, ref), Colors.tire(t?.rr, ref),
        )
    }
}
