package com.notes6lite.app.data.local

import com.notes6lite.app.domain.model.InkPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialização binária compacta dos pontos de um traço.
 * Layout: [int count][float x][float y][float pressure] * count (little endian)
 */
object StrokeCodec {

    private const val HEADER_BYTES = 4
    private const val POINT_BYTES = 12

    fun encode(points: List<InkPoint>): ByteArray {
        val buffer = ByteBuffer
            .allocate(HEADER_BYTES + points.size * POINT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(points.size)
        for (point in points) {
            buffer.putFloat(point.x)
            buffer.putFloat(point.y)
            buffer.putFloat(point.pressure)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<InkPoint> {
        if (bytes.size < HEADER_BYTES) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val declaredCount = buffer.getInt()
        val available = (bytes.size - HEADER_BYTES) / POINT_BYTES
        val count = declaredCount.coerceIn(0, available)
        val points = ArrayList<InkPoint>(count)
        repeat(count) {
            points.add(
                InkPoint(
                    x = buffer.getFloat(),
                    y = buffer.getFloat(),
                    pressure = buffer.getFloat()
                )
            )
        }
        return points
    }
}
