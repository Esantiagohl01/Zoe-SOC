package com.zoesoc

/**
 * Paleta de colores de noche y de día. Solo cambian colores: posiciones y tamaños son los mismos.
 * Se cambia con el sol/luna junto al engranaje y se recuerda en Prefs.dayMode.
 */
object Theme {
    @Volatile var day = false

    fun load(p: Prefs) { day = p.dayMode }

    private fun c(night: Long, dayC: Long) = (if (day) dayC else night).toInt()

    // Fondo y textos
    val bg get() = c(0xFF0B0E11, 0xFFE9EDF1)
    val text get() = c(0xFFE9EEF3, 0xFF12161A)
    val dim get() = c(0xFF8D97A1, 0xFF5A6470)
    val sub get() = c(0xFFB8C0C8, 0xFF3A434D)       // textos secundarios
    val unit get() = c(0xFFC8D0D8, 0xFF3A434D)      // unidades y líneas de información
    val faint get() = c(0xFF5A636C, 0xFF8A949E)     // ayudas muy discretas
    val panel get() = c(0xFF151A1F, 0xFFFFFFFF)
    val track get() = c(0xFF1F242A, 0xFFCDD3D9)     // barra de progreso de carga
    val powerTrack get() = c(0xFF2A3038, 0xFFCDD3D9)
    val powerFill get() = c(0xFFE6ECF2, 0xFF3A434D) // barra de potencia consumiendo
    val dotOff get() = c(0xFF3A4048, 0xFFB4BCC4)
    val axis get() = c(0xFFFFFFFF, 0xFF12161A)      // ejes de la gráfica
    val sunMoon get() = c(0xFFF2C94C, 0xFF3A434D)

    // Estados
    val green get() = c(0xFF69F0AE, 0xFF1E9E4A)
    val amber get() = c(0xFFFFC107, 0xFFC76A00)
    val red get() = c(0xFFFF5252, 0xFFD0342C)
    val blue get() = c(0xFF64B5F6, 0xFF2B6CC4)
    val gray get() = c(0xFF5F6670, 0xFF8A949E)

    // Gráfica de potencia
    val regen get() = c(0xFF4A90D9, 0xFF2B6CC4)
    val orange get() = c(0xFFF08A24, 0xFFE06A00)
    val hot get() = c(0xFFE04848, 0xFFD0342C)

    // Carro
    val carBody get() = c(0xFFD9DEE3, 0xFFFFFFFF)
    val carOutline get() = c(0xFF8A929B, 0xFF8A949E)
    val carOutlineW get() = if (day) 1.5f else 0.5f
    val packEmpty get() = c(0xFF9AA3AD, 0xFFC5CCD3)

    // Burbuja
    val bubbleBg get() = c(0xA6111418, 0xEBFFFFFF)
    val bubbleSmall get() = c(0xFFB8C0C8, 0xFF3A434D)
    val bubbleText get() = c(0xFFD5DCE3, 0xFF3A434D)
}
