package com.hank.ascentswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class MapsforgeHeaderParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseBoundingBox_validMapsforgeHeader() {
        val mapFile = tempFolder.newFile("test_region.map")

        RandomAccessFile(mapFile, "rw").use { raf ->
            // Write 20-byte magic header: "mapsforge binary OSM"
            raf.write("mapsforge binary OSM".toByteArray(Charsets.UTF_8))
            raf.writeInt(100) // Header size
            raf.writeInt(5)   // File version
            raf.writeLong(100000) // File size
            raf.writeLong(1600000000) // Creation date

            // Min/Max Lat/Lon in microdegrees: 37°N to 41°N, -109°W to -102°W
            raf.writeInt(37_000_000)   // minLat
            raf.writeInt(-109_000_000) // minLon
            raf.writeInt(41_000_000)   // maxLat
            raf.writeInt(-102_000_000) // maxLon
        }

        val bbox = MapsforgeHeaderParser.parseBoundingBox(mapFile)
        assertNotNull(bbox)
        assertEquals(37.0, bbox!!.minLat, 0.0001)
        assertEquals(-109.0, bbox.minLon, 0.0001)
        assertEquals(41.0, bbox.maxLat, 0.0001)
        assertEquals(-102.0, bbox.maxLon, 0.0001)
    }
}
