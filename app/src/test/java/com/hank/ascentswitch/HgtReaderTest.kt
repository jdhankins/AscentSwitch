package com.hank.ascentswitch

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

class HgtReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGetElevationAtCoordinate_missingFile_returnsZero() {
        val folder = tempFolder.newFolder()
        val elevation = HgtReader.getElevationAtCoordinate(39.54, -105.12, folder)
        assertEquals(0.0, elevation, 0.001)
    }

    @Test
    fun testGetElevationAtCoordinate_validHgtFile() {
        val folder = tempFolder.newFolder()
        val hgtFile = File(folder, "N39W106.hgt")

        val totalBytes = (3601 * 3601 * 2).toLong()
        RandomAccessFile(hgtFile, "rw").use { raf ->
            raf.setLength(totalBytes)

            val floorLat = floor(39.54).toInt() // 39
            val floorLon = floor(-105.12).toInt() // -106

            val latFrac = 39.54 - floorLat // 0.54
            val lonFrac = -105.12 - floorLon // 0.88

            val row = ((1.0 - latFrac) * 3600).toInt() // 1656
            val col = (lonFrac * 3600).toInt() // 3168
            val offset = ((row * 3601) + col) * 2

            raf.seek(offset.toLong())
            raf.writeShort(1425)
        }

        val elevation = HgtReader.getElevationAtCoordinate(39.54, -105.12, folder)
        assertEquals(1425.0, elevation, 0.001)
    }

    @Test
    fun testGetElevationAtCoordinate_voidValue_returnsZero() {
        val folder = tempFolder.newFolder()
        val hgtFile = File(folder, "N39W106.hgt")

        val totalBytes = (3601 * 3601 * 2).toLong()
        RandomAccessFile(hgtFile, "rw").use { raf ->
            raf.setLength(totalBytes)

            val floorLat = floor(39.54).toInt()
            val floorLon = floor(-105.12).toInt()

            val latFrac = 39.54 - floorLat
            val lonFrac = -105.12 - floorLon

            val row = ((1.0 - latFrac) * 3600).toInt()
            val col = (lonFrac * 3600).toInt()
            val offset = ((row * 3601) + col) * 2

            raf.seek(offset.toLong())
            raf.writeShort(-32768)
        }

        val elevation = HgtReader.getElevationAtCoordinate(39.54, -105.12, folder)
        assertEquals(0.0, elevation, 0.001)
    }
}
