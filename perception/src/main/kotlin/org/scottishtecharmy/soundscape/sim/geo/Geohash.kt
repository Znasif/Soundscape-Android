package org.scottishtecharmy.soundscape.sim.geo

/**
 * Standard base32 Geohash encoder, decoder, and 8-neighbour calculator (precision 7 by default, ~153 m x 153 m cell).
 * Pure Kotlin with zero external dependencies.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val BITS = intArrayOf(16, 8, 4, 2, 1)

    /**
     * Encodes `(latitude, longitude)` into a geohash string of length [precision] (default `7`).
     */
    fun encode(latitude: Double, longitude: Double, precision: Int = 7): String {
        require(precision in 1..12) { "Geohash precision must be in 1..12" }
        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = normalizeLon(longitude)

        var isEven = true
        var bit = 0
        var ch = 0
        val sb = StringBuilder(precision)

        while (sb.length < precision) {
            if (isEven) {
                val mid = 0.5 * (minLon + maxLon)
                if (lon >= mid) {
                    ch = ch or BITS[bit]
                    minLon = mid
                } else {
                    maxLon = mid
                }
            } else {
                val mid = 0.5 * (minLat + maxLat)
                if (lat >= mid) {
                    ch = ch or BITS[bit]
                    minLat = mid
                } else {
                    maxLat = mid
                }
            }
            isEven = !isEven
            if (bit < 4) {
                bit++
            } else {
                sb.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }

    /**
     * Decodes a [geohash] string into its bounding box [BoundingBox].
     */
    fun decodeBounds(geohash: String): BoundingBox {
        require(geohash.isNotEmpty()) { "Geohash must not be empty" }
        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        var isEven = true

        for (c in geohash.lowercase()) {
            val cd = BASE32.indexOf(c)
            require(cd >= 0) { "Invalid geohash character: '$c'" }
            for (mask in BITS) {
                if (isEven) {
                    val mid = 0.5 * (minLon + maxLon)
                    if ((cd and mask) != 0) {
                        minLon = mid
                    } else {
                        maxLon = mid
                    }
                } else {
                    val mid = 0.5 * (minLat + maxLat)
                    if ((cd and mask) != 0) {
                        minLat = mid
                    } else {
                        maxLat = mid
                    }
                }
                isEven = !isEven
            }
        }
        return BoundingBox(minLon = minLon, minLat = minLat, maxLon = maxLon, maxLat = maxLat)
    }

    /**
     * Decodes a [geohash] string into its center [LatLon].
     */
    fun decode(geohash: String): LatLon {
        val b = decodeBounds(geohash)
        return LatLon(
            latitude = 0.5 * (b.minLat + b.maxLat),
            longitude = 0.5 * (b.minLon + b.maxLon)
        )
    }

    /**
     * Returns the 8 surrounding geohash cells (`N, NE, E, SE, S, SW, W, NW`) at the same precision as [geohash].
     */
    fun neighbours(geohash: String): List<String> {
        val b = decodeBounds(geohash)
        val dLat = b.maxLat - b.minLat
        val dLon = b.maxLon - b.minLon
        val cLat = 0.5 * (b.minLat + b.maxLat)
        val cLon = 0.5 * (b.minLon + b.maxLon)
        val p = geohash.length

        val offsets = listOf(
            1 to 0,   // N
            1 to 1,   // NE
            0 to 1,   // E
            -1 to 1,  // SE
            -1 to 0,  // S
            -1 to -1, // SW
            0 to -1,  // W
            1 to -1   // NW
        )
        return offsets.map { (latStep, lonStep) ->
            val nLat = (cLat + latStep * dLat).coerceIn(-89.999999, 89.999999)
            val nLon = normalizeLon(cLon + lonStep * dLon)
            encode(nLat, nLon, p)
        }
    }

    private fun normalizeLon(lon: Double): Double {
        var x = ((lon + 180.0) % 360.0)
        if (x < 0.0) x += 360.0
        return x - 180.0
    }
}
