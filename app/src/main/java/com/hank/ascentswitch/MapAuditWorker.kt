package com.hank.ascentswitch

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import kotlin.math.floor

@Suppress("unused")
class MapAuditWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mapDir = File("/storage/self/primary/offline/maps/")
        val elevationDir = File(applicationContext.filesDir, "elevation")

        if (!elevationDir.exists()) {
            elevationDir.mkdirs()
        }

        if (!mapDir.exists() || !mapDir.isDirectory) {
            return Result.success()
        }

        val mapFiles = mapDir.listFiles { _, name ->
            name.endsWith(".map", ignoreCase = true)
        } ?: emptyArray()

        if (mapFiles.isEmpty()) {
            return Result.success()
        }

        val requiredTileNames = mutableSetOf<String>()

        for (mapFile in mapFiles) {
            val bbox = MapsforgeHeaderParser.parseBoundingBox(mapFile)
            if (bbox != null) {
                val minLatFloor = floor(bbox.minLat).toInt()
                val maxLatFloor = floor(bbox.maxLat).toInt()
                val minLonFloor = floor(bbox.minLon).toInt()
                val maxLonFloor = floor(bbox.maxLon).toInt()

                for (lat in minLatFloor..maxLatFloor) {
                    for (lon in minLonFloor..maxLonFloor) {
                        val tileName = HgtReader.getTileFilename(lat.toDouble(), lon.toDouble())
                        requiredTileNames.add(tileName)
                    }
                }
            }
        }

        Log.d(TAG, "Map audit found ${mapFiles.size} installed maps requiring ${requiredTileNames.size} tiles.")

        // 1. Download missing tiles required for installed maps
        for (tileName in requiredTileNames) {
            val isCached = File(elevationDir, tileName).exists() || File(applicationContext.filesDir, tileName).exists()
            if (!isCached) {
                Log.d(TAG, "Map audit downloading missing required tile: $tileName")
                TileDownloader.downloadTile(tileName, elevationDir)
            }
        }

        // 2. Remove orphaned .hgt tiles no longer present in installed map bounds
        if (requiredTileNames.isNotEmpty()) {
            val cachedElevationFiles = elevationDir.listFiles { _, name ->
                name.endsWith(".hgt", ignoreCase = true)
            } ?: emptyArray()

            for (cachedFile in cachedElevationFiles) {
                if (!requiredTileNames.contains(cachedFile.name)) {
                    Log.d(TAG, "Removing orphaned elevation tile no longer in active maps: ${cachedFile.name}")
                    cachedFile.delete()
                }
            }
        }

        val prefs = applicationContext.getSharedPreferences("ascent_switch_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("new_map_requires_sync", false)
            putBoolean("map_audit_last_run_success", true)
            putLong("map_audit_last_run_time", System.currentTimeMillis())
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "MapAuditWorker"
    }
}
