package com.notes6lite.app.domain.model

enum class ToolMode {
    PEN,
    HIGHLIGHTER,
    ERASER,
    TEXT;

    companion object {
        fun fromName(name: String?): ToolMode =
            values().firstOrNull { it.name == name } ?: PEN
    }
}
