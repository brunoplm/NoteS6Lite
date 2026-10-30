package com.notes6lite.app.domain.model

/**
 * Ponto de tinta no espaço de coordenadas da página.
 *
 * pressure é o valor bruto normalizado da S-Pen (event.getPressure()),
 * já suavizado pelo NoteCanvasView para evitar trepidação no traço.
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f
)
