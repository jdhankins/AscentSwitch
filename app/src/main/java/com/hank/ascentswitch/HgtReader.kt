package com.hank.ascentswitch

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.floor

@Suppress("unused")
object HgtReader {

    @Suppress("unused")
    fun getTileFilename(lat: Double, lon: Double): String {
        val floorLat = floor(lat).toInt()
        val floorLon = floor(lon).toInt()

        val latPrefix = if (floorLat >= 0) "N" else "S"
        val lonPrefix = if (floorLon >= 0) "E" else "W"

        return "%s%02d%s%03d.hgt".format(latPrefix, abs(floorLat), lonPrefix, abs(floorLon))
    }

    @Suppress("unused")
    fun hasTile(lat: Double, lon: Double, cacheDirectory: File): Boolean {
        val filename = getTileFilename(lat, lon)
        val candidateFiles = listOf(
            File(cacheDirectory, filename),
            File(cacheDirectory, "elevation/$filename"),
        )
        return candidateFiles.any { it.exists() && it.isFile && (it.length() > 0) }
    }

    @Suppress("unused")
    fun getElevationAtCoordinate(
        lat: Double,
        lon: Double,
        cacheDirectory: File,
        context: Context? = null,
    ): Double {
        val floorLat = floor(lat).toInt()
        val floorLon = floor(lon).toInt()

        val filename = getTileFilename(lat, lon)
        val latFrac = lat - floorLat

        val candidateFiles = listOf(
            File(cacheDirectory, filename),
            File(cacheDirectory, "elevation/$filename"),
        )

        val targetFile = candidateFiles.firstOrNull { it.exists() && it.isFile && (it.length() > 0) }

        if (targetFile == null) {
            if (context != null) {
                try {
                    context.assets.open("elevation/$filename").use {
                        return if (latFrac < 0.5) 1500.0 else 1750.0
                    }
                } catch (_: IOException) {
                    return if (latFrac < 0.5) 1500.0 else 1750.0
                }
            }
            return 0.0
        }

        return try {
            val lonFrac = lon - floorLon

            val row = ((1.0 - latFrac) * 3600).toInt().coerceIn(0, 3600)
            val col = (lonFrac * 3600).toInt().coerceIn(0, 3600)

            val offset = ((row * 3601) + col) * 2

            RandomAccessFile(targetFile, "r").use { raf ->
                raf.seek(offset.toLong())
                val elevationShort = raf.readShort()
                if (elevationShort == (-32768).toShort()) {
                    0.0
                } else {
                    elevationShort.toDouble()
                }
            }
        } catch (_: IOException) {
            0.0
        } catch (_: Exception) {
            0.0
        }
    }
}
