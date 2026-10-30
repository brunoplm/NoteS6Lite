package com.notes6lite.app.domain.model

data class TextElement(
    val id: Long = System.currentTimeMillis(),
    var text: String,
    var x: Float,
    var y: Float,
    var fontSize: Float = 22f,
    var color: Int = 0xFF111827.toInt()
)
