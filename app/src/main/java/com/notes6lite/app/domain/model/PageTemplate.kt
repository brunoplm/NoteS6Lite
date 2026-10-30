package com.notes6lite.app.domain.model

/**
 * Formatos de página suportados pelo NoteCanvasView.
 *
 * As dimensões estão em pixels de página (espaço de coordenadas do canvas).
 * O Canvas Infinito não possui limites físicos: pageWidth/pageHeight são 0
 * e o fundo é desenhado apenas na área visível do viewport.
 */
enum class PageTemplate(
    val displayName: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val isInfinite: Boolean = false
) {
    A4("A4", 1654, 2339),
    PRESENTATION_16_9("Apresentação 16:9", 2339, 1316),
    INFINITE("Canvas Infinito", 0, 0, isInfinite = true);

    val aspectRatio: Float
        get() = if (isInfinite) 0f else pageWidth.toFloat() / pageHeight.toFloat()

    companion object {
        fun fromName(name: String?): PageTemplate =
            values().firstOrNull { it.name == name } ?: A4
    }
}
