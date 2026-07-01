package io.shayne.fogvisitor

import kotlin.math.roundToInt

object PolylineCodec {

    fun encode(coords: List<List<Double>>): String {
        val result = StringBuilder()
        var previousLat = 0
        var previousLng = 0

        coords.forEach { point ->
            val lng = (point[0] * 1e5).roundToInt()
            val lat = (point[1] * 1e5).roundToInt()

            encodeValue(lat - previousLat, result)
            encodeValue(lng - previousLng, result)

            previousLat = lat
            previousLng = lng
        }

        return result.toString()
    }

    fun calculateBbox(coords: List<List<Double>>): List<Double>? {
        if (coords.isEmpty()) return null

        var minLng = coords.first()[0]
        var minLat = coords.first()[1]
        var maxLng = coords.first()[0]
        var maxLat = coords.first()[1]

        coords.drop(1).forEach { point ->
            minLng = minOf(minLng, point[0])
            minLat = minOf(minLat, point[1])
            maxLng = maxOf(maxLng, point[0])
            maxLat = maxOf(maxLat, point[1])
        }

        return listOf(
            minLng.toFixed6(),
            minLat.toFixed6(),
            maxLng.toFixed6(),
            maxLat.toFixed6()
        )
    }

    private fun encodeValue(value: Int, output: StringBuilder) {
        var current = if (value < 0) (value shl 1).inv() else value shl 1
        while (current >= 0x20) {
            output.append(((0x20 or (current and 0x1f)) + 63).toChar())
            current = current shr 5
        }
        output.append((current + 63).toChar())
    }

    private fun Double.toFixed6(): Double = String.format("%.6f", this).toDouble()
}
