package com.hank.ascentswitch

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.floor

@Suppress("unused")
class MapAuditWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLogger.init(applicationContext)
        AppLogger.d(TAG, "MapAuditWorker started execution...")

        val externalStorage = Environment.getExternalStorageDirectory()
        @Suppress("SdCardPath")
        val candidateMapDirs = listOf(
            File("/sdcard/offline/maps/"),
            File(externalStorage, "offline/maps"),
            File("/storage/self/primary/offline/maps/"),
            File("/storage/emulated/0/offline/maps/"),
        )

        val elevationDir = File(applicationContext.filesDir, "elevation")
        if (!elevationDir.exists()) {
            elevationDir.mkdirs()
        }

        var mapFiles = emptyArray<File>()
        var activeMapDir: File? = null

        for (dir in candidateMapDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { _, name -> name.endsWith(".map", ignoreCase = true) }
                if (!files.isNullOrEmpty()) {
                    mapFiles = files
                    activeMapDir = dir
                    break
                }
            }
        }

        val requiredTileNames = mutableSetOf<String>()
        val tileToMapName = mutableMapOf<String, String>()

        if ((activeMapDir != null) && mapFiles.isNotEmpty()) {
            AppLogger.d(TAG, "Found ${mapFiles.size} active .map files in ${activeMapDir.absolutePath}")
            for (mapFile in mapFiles) {
                val mapName = mapFile.nameWithoutExtension.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val bbox = MapsforgeHeaderParser.parseBoundingBox(mapFile)
                if (bbox != null) {
                    val minLatFloor = floor(bbox.minLat).toInt()
                    val maxLatFloor = floor(bbox.maxLat).toInt()
                    val minLonFloor = floor(bbox.minLon).toInt()
                    val maxLonFloor = floor(bbox.maxLon).toInt()

                    AppLogger.d(TAG, "Parsed map $mapName: Bounds=($minLatFloor..$maxLatFloor, $minLonFloor..$maxLonFloor)")

                    for (lat in minLatFloor..maxLatFloor) {
                        for (lon in minLonFloor..maxLonFloor) {
                            val tileName = HgtReader.getTileFilename(lat.toDouble(), lon.toDouble())
                            requiredTileNames.add(tileName)
                            tileToMapName[tileName] = mapName
                        }
                    }
                }
            }
        } else {
            // Fallback default region bounds if no maps are present
            val defaultBounds = listOf(
                MapBoundingBox(37.0, -109.0, 41.0, -102.0), // Colorado
                MapBoundingBox(37.0, -114.0, 42.0, -109.0), // Utah
            )
            for ((minLat, minLon, maxLat, maxLon) in defaultBounds) {
                val minLatFloor = floor(minLat).toInt()
                val maxLatFloor = floor(maxLat).toInt()
                val minLonFloor = floor(minLon).toInt()
                val maxLonFloor = floor(maxLon).toInt()

                for (lat in minLatFloor..maxLatFloor) {
                    for (lon in minLonFloor..maxLonFloor) {
                        val tileName = HgtReader.getTileFilename(lat.toDouble(), lon.toDouble())
                        requiredTileNames.add(tileName)
                        tileToMapName[tileName] = "Active Region"
                    }
                }
            }
        }

        AppLogger.d(TAG, "Map audit found ${mapFiles.size} installed maps requiring ${requiredTileNames.size} tiles.")

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        val missingTiles = requiredTileNames.filter { tileName ->
            val tileFile = File(elevationDir, tileName)
            val legacyFile = File(applicationContext.filesDir, tileName)
            val externalFile = File(externalStorage, "AscentSwitch/$tileName")
            val downloadsFile = File(downloadsDir, tileName)
            val isCached = ((tileFile.exists() && tileFile.length() > 0) ||
                (legacyFile.exists() && legacyFile.length() > 0) ||
                (externalFile.exists() && externalFile.length() > 0) ||
                (downloadsFile.exists() && downloadsFile.length() > 0))
            !isCached
        }

        val totalMissing = missingTiles.size
        var completedCount = 0

        AppLogger.d(TAG, "Map audit found $totalMissing missing tiles to download out of ${requiredTileNames.size} total required.")
        setProgress(workDataOf("downloaded" to completedCount, "total" to totalMissing, "activeMap" to "Scanning Maps"))

        // Download missing tiles in controlled parallel batches of 3
        if (missingTiles.isNotEmpty()) {
            coroutineScope {
                val dispatcher = Dispatchers.IO.limitedParallelism(3)
                missingTiles.chunked(3).forEach { chunk ->
                    val jobs = chunk.map { tileName ->
                        launch(dispatcher) {
                            val mapName = tileToMapName[tileName] ?: "Offline Maps"
                            AppLogger.d(TAG, "Downloading tile $tileName for map $mapName")
                            val success = TileDownloader.downloadTile(tileName, elevationDir)
                            if (success) {
                                synchronized(this@MapAuditWorker) {
                                    completedCount++
                                }
                                setProgress(
                                    workDataOf(
                                        "downloaded" to completedCount,
                                        "total" to totalMissing,
                                        "activeMap" to mapName,
                                        "activeTile" to tileName,
                                    ),
                                )
                            }
                            AppLogger.d(TAG, "Tile download result for $tileName: success=$success")
                        }
                    }
                    jobs.joinAll()
                }
            }
        }

        // Remove orphaned .hgt tiles no longer present in installed map bounds
        if (requiredTileNames.isNotEmpty()) {
            val cachedElevationFiles = elevationDir.listFiles { _, name ->
                name.endsWith(".hgt", ignoreCase = true)
            } ?: emptyArray()

            for (cachedFile in cachedElevationFiles) {
                if (!requiredTileNames.contains(cachedFile.name)) {
                    AppLogger.d(TAG, "Removing orphaned elevation tile no longer in active maps: ${cachedFile.name}")
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

        AppLogger.d(TAG, "MapAuditWorker complete. Available tiles: ${requiredTileNames.size}")
        return Result.success()
    }

    companion object {
        private const val TAG = "MapAuditWorker"
    }
}
