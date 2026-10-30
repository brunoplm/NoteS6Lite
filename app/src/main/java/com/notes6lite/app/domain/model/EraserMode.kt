package com.notes6lite.app.domain.model

/**
 * Como a borracha apaga:
 *
 * - [STROKE]: borracha de objeto. Ao tocar em um traço, o traço inteiro é
 *   removido da lista de uma vez (rápido para limpar blocos grandes).
 * - [PIXEL]: borracha precisa. Mantém o comportamento clássico com
 *   PorterDuff.Mode.CLEAR na camada de tinta, apagando só o que passa
 *   por baixo da ponta.
 */
enum class EraserMode(val displayName: String) {
    STROKE("Objeto"),
    PIXEL("Precisa");

    fun toggle(): EraserMode = if (this == STROKE) PIXEL else STROKE
}
