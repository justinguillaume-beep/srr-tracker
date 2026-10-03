package com.srrtracker.detect

fun encodePips(pips: List<DiceDetector.PipMark>): String =
    pips.joinToString(";") { "${it.x},${it.y},${it.r},${it.die}" }

fun decodePips(raw: String?): List<DiceDetector.PipMark> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';').mapNotNull { part ->
        val bits = part.split(',')
        if (bits.size != 4) return@mapNotNull null
        try {
            DiceDetector.PipMark(bits[0].toDouble(), bits[1].toDouble(), bits[2].toDouble(), bits[3].toInt())
        } catch (_: NumberFormatException) {
            null
        }
    }
}
