package com.notes6lite.app.domain.model

/**
 * Esquema de cores da folha, independente do padrão desenhado nela.
 *
 * A combinação [PagePattern] x [ColorScheme] gera as 8 variações de folha
 * (em branco/pautada/quadriculada/pontilhada, cada uma clara ou escura).
 */
enum class ColorScheme(
    val displayName: String,
    val isDark: Boolean
) {
    LIGHT("Claro", false),
    DARK("Escuro", true);

    companion object {
        fun fromName(name: String?): ColorScheme =
            values().firstOrNull { it.name == name } ?: LIGHT
    }
}
