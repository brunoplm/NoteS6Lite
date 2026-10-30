package com.notes6lite.app.domain.model

/**
 * Aparência da folha = padrão ([PageTemplate]) + esquema de cores ([ColorScheme]).
 *
 * Qualquer um dos 4 templates ([PageTemplate.BLANK], [PageTemplate.RULED],
 * [PageTemplate.GRID], [PageTemplate.DOTTED]) pode ser renderizado em
 * [ColorScheme.LIGHT] ou [ColorScheme.DARK].
 */
enum class SheetStyle(
    val displayName: String,
    val template: PageTemplate,
    val scheme: ColorScheme
) {
    BLANK("Em branco", PageTemplate.BLANK, ColorScheme.LIGHT),
    RULED("Pautada", PageTemplate.RULED, ColorScheme.LIGHT),
    GRID("Quadriculada", PageTemplate.GRID, ColorScheme.LIGHT),
    DOTTED("Pontilhada", PageTemplate.DOTTED, ColorScheme.LIGHT),
    BLANK_DARK("Em branco (escura)", PageTemplate.BLANK, ColorScheme.DARK),
    RULED_DARK("Pautada (escura)", PageTemplate.RULED, ColorScheme.DARK),
    GRID_DARK("Quadriculada (escura)", PageTemplate.GRID, ColorScheme.DARK),
    DOTTED_DARK("Pontilhada (escura)", PageTemplate.DOTTED, ColorScheme.DARK);

    val pattern: PageTemplate get() = template

    val isDark: Boolean
        get() = scheme.isDark

    companion object {
        private const val LEGACY_DARK = "DARK"

        fun fromName(name: String?): SheetStyle = when (name) {
            LEGACY_DARK -> GRID_DARK
            else -> values().firstOrNull { it.name == name } ?: GRID
        }

        fun of(template: PageTemplate, scheme: ColorScheme): SheetStyle =
            values().firstOrNull { it.template == template && it.scheme == scheme }
                ?: if (scheme.isDark) GRID_DARK else GRID

        fun forScheme(scheme: ColorScheme): List<SheetStyle> =
            values().filter { it.scheme == scheme }
    }
}
