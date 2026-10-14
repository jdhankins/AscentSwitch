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
            // Write 20-byte magic string: "mapsforge binary header"
            raf.write("mapsforge binary header".toByteArray(Charsets.UTF_8))
            raf.writeInt(100) // Header size
            raf.writeInt(3)   // File version
            raf.writeLong(100000) // File size
            raf.writeLong(1600000000) // Creation date

            // Min/Max Lat/Lon in microdegrees: 39°N to 40°N, 106°W to 105°W
            raf.writeInt(39_000_000)   // minLat
            raf.writeInt(-106_000_000) // minLon
            raf.writeInt(40_000_000)   // maxLat
            raf.writeInt(-105_000_000) // maxLon
        }

        val bbox = MapsforgeHeaderParser.parseBoundingBox(mapFile)
        assertNotNull(bbox)
        assertEquals(39.0, bbox!!.minLat, 0.0001)
        assertEquals(-106.0, bbox.minLon, 0.0001)
        assertEquals(40.0, bbox.maxLat, 0.0001)
        assertEquals(-105.0, bbox.maxLon, 0.0001)
    }
}
