package com.notes6lite.app.domain.model

/**
 * Traço vetorial persistível.
 *
 * A geometria é mantida como uma lista de pontos com pressão. O contorno
 * variável (Path preenchido) é reconstruído sob demanda pelo renderizador,
 * mantendo o domínio independente de android.graphics.
 */
data class Stroke(
    val points: List<InkPoint>,
    val color: Int,
    val baseWidth: Float,
    val mode: ToolMode,
    val penType: PenType = PenType.BALLPOINT
)
