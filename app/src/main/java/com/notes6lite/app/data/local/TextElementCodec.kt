package com.notes6lite.app.data.local

import com.notes6lite.app.domain.model.TextElement
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializador e desserializador leve de TextElement sem bibliotecas externas pesadas.
 * Utiliza o parser JSON nativo do Android (org.json).
 */
object TextElementCodec {

    fun encode(elements: List<TextElement>): String {
        if (elements.isEmpty()) return ""
        val array = JSONArray()
        for (el in elements) {
            val obj = JSONObject()
            obj.put("id", el.id)
            obj.put("text", el.text)
            obj.put("x", el.x.toDouble())
            obj.put("y", el.y.toDouble())
            obj.put("fontSize", el.fontSize.toDouble())
            obj.put("color", el.color)
            array.put(obj)
        }
        return array.toString()
    }

    fun decode(json: String?): List<TextElement> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val list = ArrayList<TextElement>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TextElement(
                        id = obj.optLong("id", System.currentTimeMillis() + i),
                        text = obj.getString("text"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        fontSize = obj.optDouble("fontSize", 20.0).toFloat(),
                        color = obj.optInt("color", android.graphics.Color.BLACK)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
