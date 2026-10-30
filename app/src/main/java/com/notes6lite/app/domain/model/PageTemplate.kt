package com.notes6lite.app.domain.model

private const val A4_WIDTH = 1654
private const val A4_HEIGHT = 2339

/**
 * Templates de folha da página:
 * - [BLANK]: Folha lisa / em branco.
 * - [RULED]: Folha pautada com linhas horizontais e margem.
 * - [GRID]: Folha quadriculada para matemática e notas estruturadas.
 * - [DOTTED]: Folha pontilhada estilo bullet journal.
 *
 * Podem ser combinados com [ColorScheme.LIGHT] ou [ColorScheme.DARK].
 */
enum class PageTemplate(
    val displayName: String,
    val pageWidth: Int = A4_WIDTH,
    val pageHeight: Int = A4_HEIGHT,
    val isInfinite: Boolean = false
) {
    BLANK("Em branco"),
    RULED("Pautada"),
    GRID("Quadriculada"),
    DOTTED("Pontilhada");

    companion object {
        const val DEFAULT_WIDTH = A4_WIDTH
        const val DEFAULT_HEIGHT = A4_HEIGHT

        // Aliases para compatibilidade retroativa
        val A4: PageTemplate get() = GRID
        val PRESENTATION_16_9: PageTemplate get() = BLANK
        val INFINITE: PageTemplate get() = BLANK

        fun fromName(name: String?): PageTemplate = when (name) {
            "BLANK" -> BLANK
            "RULED" -> RULED
            "GRID" -> GRID
            "DOTTED" -> DOTTED
            else -> GRID
        }
    }
}
