package com.hank.ascentswitch

import java.io.File
import java.io.RandomAccessFile

data class MapBoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

@Suppress("unused")
object MapsforgeHeaderParser {

    @Suppress("unused")
    fun parseBoundingBox(mapFile: File): MapBoundingBox? {
        if ((!mapFile.exists()) || (!mapFile.isFile) || (mapFile.length() < 60)) return null

        return try {
            RandomAccessFile(mapFile, "r").use { raf ->
                // Mapsforge binary signature is exactly 20 bytes (e.g. "mapsforge binary OSM")
                val headerBytes = ByteArray(20)
                raf.readFully(headerBytes)
                val headerStr = String(headerBytes, Charsets.UTF_8)
                if (!headerStr.startsWith("mapsforge")) {
                    return null
                }

                // Skip Header Size (4), File Version (4), File Size (8), Creation Date (8) = 24 bytes
                raf.skipBytes(24)

                val minLatMicro = raf.readInt()
                val minLonMicro = raf.readInt()
                val maxLatMicro = raf.readInt()
                val maxLonMicro = raf.readInt()

                MapBoundingBox(
                    minLat = minLatMicro / 1_000_000.0,
                    minLon = minLonMicro / 1_000_000.0,
                    maxLat = maxLatMicro / 1_000_000.0,
                    maxLon = maxLonMicro / 1_000_000.0,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
