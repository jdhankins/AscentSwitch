package com.hank.ascentswitch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlin.math.floor

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(applicationContext)
        checkStoragePermissions()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        val fragment = supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment
        fragment?.populateDownloadedMapsCategory()
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Please grant All Files Access to scan offline maps", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val fragment = supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment
            fragment?.populateDownloadedMapsCategory()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            populateDownloadedMapsCategory()
        }

        fun populateDownloadedMapsCategory() {
            val category = findPreference<PreferenceCategory>("downloaded_maps_category") ?: return
            category.removeAll()

            val context = context ?: return

            // 1. Add Sync & Clear Buttons
            val syncBtn = Preference(context).apply {
                key = "sync_now_button"
                title = "⚡ Sync Elevation Tiles Now"
                summary = "Tap to scan active Karoo maps and download missing tiles over Wi-Fi"
                setOnPreferenceClickListener {
                    triggerManualSync(this)
                    true
                }
            }
            category.addPreference(syncBtn)

            val deleteBtn = Preference(context).apply {
                key = "delete_tiles_button"
                title = "🗑️ Clear All Elevation Tiles"
                summary = "Delete all downloaded .hgt files from local storage for testing"
                setOnPreferenceClickListener {
                    clearAllElevationTiles(context)
                    true
                }
            }
            category.addPreference(deleteBtn)

            // 2. Locate Active Karoo Map Directory with Files
            val externalStorage = Environment.getExternalStorageDirectory()
            @Suppress("SdCardPath")
            val candidateMapDirs = listOf(
                File("/sdcard/offline/maps/"),
                File(externalStorage, "offline/maps"),
                File("/storage/self/primary/offline/maps/"),
                File("/storage/emulated/0/offline/maps/"),
            )

            val cacheDir = context.filesDir
            var mapFiles = emptyArray<File>()

            for (dir in candidateMapDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { _, name -> name.endsWith(".map", ignoreCase = true) }
                    if (!files.isNullOrEmpty()) {
                        mapFiles = files
                        break
                    }
                }
            }

            // 3. Scan for standalone .hgt tiles in local storage
            val hgtDirs = listOf(
                File(cacheDir, "elevation"),
                cacheDir,
                File(externalStorage, "AscentSwitch"),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            )

            val cachedHgtFiles = hgtDirs.asSequence().flatMap { dir ->
                dir.listFiles { _, name -> name.endsWith(".hgt", ignoreCase = true) }?.toList() ?: emptyList()
            }.distinctBy { it.name }.toList()

            if (mapFiles.isEmpty() && cachedHgtFiles.isEmpty()) {
                val emptyPref = Preference(context).apply {
                    title = "No Offline Maps Detected"
                    summary = "Download offline maps in Karoo Settings or copy .hgt files to /sdcard/AscentSwitch/"
                    isSelectable = false
                }
                category.addPreference(emptyPref)
                return
            }

            // Display installed map regions with state-by-state tile breakdown
            for (mapFile in mapFiles) {
                val mapName = mapFile.nameWithoutExtension.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val bbox = MapsforgeHeaderParser.parseBoundingBox(mapFile)

                val mapPref = Preference(context).apply {
                    title = "📍 State: $mapName"
                    isSelectable = false

                    if (bbox != null) {
                        val minLat = floor(bbox.minLat).toInt()
                        val maxLat = floor(bbox.maxLat).toInt()
                        val minLon = floor(bbox.minLon).toInt()
                        val maxLon = floor(bbox.maxLon).toInt()

                        val tiles = mutableListOf<String>()
                        var downloadedCount = 0

                        for (lat in minLat..maxLat) {
                            for (lon in minLon..maxLon) {
                                val tileName = HgtReader.getTileFilename(lat.toDouble(), lon.toDouble())
                                tiles.add(tileName)
                                if (HgtReader.hasTile(lat.toDouble(), lon.toDouble(), cacheDir)) {
                                    downloadedCount++
                                }
                            }
                        }

                        val tileListStr = if (tiles.size <= 5) {
                            tiles.joinToString(", ")
                        } else {
                            "${tiles.asSequence().take(4).joinToString(", ")}... (+${tiles.size - 4} more)"
                        }

                        summary = if (downloadedCount == tiles.size) {
                            "✓ $downloadedCount/$downloadedCount Tiles Downloaded (100% Offline)\n$tileListStr"
                        } else {
                            "⚡ $downloadedCount/${tiles.size} Tiles Downloaded\n$tileListStr"
                        }
                    } else {
                        summary = "Map installed (${mapFile.length() / (1024 * 1024)} MB)"
                    }
                }
                category.addPreference(mapPref)
            }

            // Display individual downloaded .hgt tiles if present
            if (cachedHgtFiles.isNotEmpty()) {
                val tilesPref = Preference(context).apply {
                    title = "📂 Active Topo Tiles (${cachedHgtFiles.size})"
                    summary = cachedHgtFiles.asSequence().take(6).joinToString(", ") { it.name }
                    isSelectable = false
                }
                category.addPreference(tilesPref)
            }
        }

        private fun clearAllElevationTiles(context: Context) {
            val cacheDir = context.filesDir
            val externalStorage = Environment.getExternalStorageDirectory()
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            val targetDirs = listOf(
                File(cacheDir, "elevation"),
                cacheDir,
                File(externalStorage, "AscentSwitch"),
                downloadsDir,
            )

            var deletedCount = 0
            for (dir in targetDirs) {
                val hgtFiles = dir.listFiles { _, name -> name.endsWith(".hgt", ignoreCase = true) } ?: emptyArray()
                for (file in hgtFiles) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }

            Toast.makeText(context, "Cleared $deletedCount elevation tiles.", Toast.LENGTH_SHORT).show()
            populateDownloadedMapsCategory()
        }

        private fun triggerManualSync(syncPref: Preference) {
            val ctx = context ?: return
            Toast.makeText(ctx, "Starting elevation tiles sync over Wi-Fi...", Toast.LENGTH_SHORT).show()
            syncPref.title = "⏳ Scanning Karoo Map Bounds..."
            syncPref.summary = "Analyzing installed map headers and calculating tiles..."

            val syncWorkRequest = OneTimeWorkRequestBuilder<MapAuditWorker>().build()
            val workManager = WorkManager.getInstance(ctx)

            workManager.enqueueUniqueWork(
                "ManualMapAuditWorker",
                ExistingWorkPolicy.REPLACE,
                syncWorkRequest,
            )

            workManager.getWorkInfoByIdLiveData(syncWorkRequest.id).observe(viewLifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    if (workInfo.state == WorkInfo.State.RUNNING) {
                        val downloaded = workInfo.progress.getInt("downloaded", 0)
                        val total = workInfo.progress.getInt("total", 0)
                        val activeMap = workInfo.progress.getString("activeMap") ?: "Offline Maps"
                        val activeTile = workInfo.progress.getString("activeTile") ?: ""

                        if (total > 0) {
                            val percent = (downloaded * 100) / total
                            syncPref.title = "⏳ Downloading $activeMap... ($downloaded / $total Tiles - $percent%)"
                            syncPref.summary = if (activeTile.isNotBlank()) {
                                "Fetching $activeTile over Wi-Fi..."
                            } else {
                                "Downloading FABDEM topography elevation files over Wi-Fi..."
                            }
                        } else {
                            syncPref.title = "⏳ Scanning Karoo Map Bounds..."
                            syncPref.summary = "Analyzing installed state map headers..."
                        }
                        populateDownloadedMapsCategory()
                    } else if (workInfo.state.isFinished) {
                        syncPref.title = "⚡ Sync Elevation Tiles Now"
                        if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                            Toast.makeText(ctx, "Elevation tiles sync complete!", Toast.LENGTH_SHORT).show()
                            syncPref.summary = "✓ All map tiles synced! Tap to scan again."
                        } else {
                            Toast.makeText(ctx, "Sync failed. Check Wi-Fi connection.", Toast.LENGTH_SHORT).show()
                            syncPref.summary = "Tap to scan active Karoo maps and download missing tiles over Wi-Fi"
                        }
                        populateDownloadedMapsCategory()
                    }
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }
}
