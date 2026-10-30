package com.notes6lite.app.domain.model

/**
 * Modo de exibição do documento/PDF:
 * - [CONTINUOUS]: Rolagem vertical contínua estilo livro digital / PDF reader.
 * - [SINGLE_PAGE]: Exibição de uma página por vez com navegação pelas setas.
 */
enum class DocumentViewMode(val displayName: String) {
    CONTINUOUS("Rolo"),
    SINGLE_PAGE("Página");

    fun toggle(): DocumentViewMode =
        if (this == CONTINUOUS) SINGLE_PAGE else CONTINUOUS
}
