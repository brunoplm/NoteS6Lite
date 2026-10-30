package com.notes6lite.app.domain.model

/**
 * Estilo visual da folha. O estilo é independente do template de página:
 * um A4 pode ser pautado, quadriculado, em branco ou escuro.
 */
enum class SheetStyle(
    val displayName: String,
    val isDark: Boolean
) {
    BLANK("Em branco", false),
    RULED("Pautada", false),
    GRID("Quadriculada", false),
    DARK("Dark Mode", true);

    companion object {
        fun fromName(name: String?): SheetStyle =
            values().firstOrNull { it.name == name } ?: BLANK
    }
}
