package com.hank.ascentswitch

import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.Math.toRadians
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ClimbState {
    REGULAR,
    CONFIRMING_ASCENT,
    ASCENDING,
    COOLDOWN,
}

enum class HardwareButton {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CONTROL_CENTER,
}

class AscentSwitchService : KarooExtension("ascentswitch", "1.0.0") {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var karooSystem: KarooSystemService

    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentHeading: Double = 0.0
    private var currentSpeed: Double = 0.0

    private var currentState: ClimbState = ClimbState.REGULAR
    private var cooldownJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                Log.d(TAG, "Successfully connected to KarooSystemService.")
                subscribeToTelemetry()
            } else {
                Log.w(TAG, "Disconnected or failed to connect to KarooSystemService.")
            }
        }

        scheduleMapAuditWorker()
        startBackgroundCalculationLoop()

        Log.d(TAG, "AscentSwitch telemetry service initialized.")
    }

    private fun scheduleMapAuditWorker() {
        try {
            val mapAuditRequest = PeriodicWorkRequestBuilder<MapAuditWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "MapAuditWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                mapAuditRequest,
            )
            Log.d(TAG, "Scheduled 24-hour MapAuditWorker routine.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule MapAuditWorker: ${e.message}", e)
        }
    }

    private fun subscribeToTelemetry() {
        // Track live location and heading (orientation)
        karooSystem.addConsumer<OnLocationChanged> { (lat, lng, orientation) ->
            currentLat = lat
            currentLon = lng
            orientation?.let { currentHeading = it }
        }

        // Track live speed stream
        karooSystem.addConsumer<OnStreamState>(
            params = OnStreamState.StartStreaming(DataType.Type.SPEED),
        ) { (state) ->
            (state as? StreamState.Streaming)?.dataPoint?.singleValue?.let { speed ->
                currentSpeed = speed
            }
        }
    }

    private fun startBackgroundCalculationLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    evaluateUpcomingTerrain()
                } catch (e: Exception) {
                    Log.e(TAG, "Error evaluating terrain: ${e.message}", e)
                }
                delay(2.seconds)
            }
        }
    }

    private fun evaluateUpcomingTerrain() {
        if ((currentLat == 0.0) && (currentLon == 0.0)) return

        val cacheDir = applicationContext.filesDir
        if (!HgtReader.hasTile(currentLat, currentLon, cacheDir)) {
            val tileFilename = HgtReader.getTileFilename(currentLat, currentLon)
            serviceScope.launch(Dispatchers.IO) {
                TileDownloader.downloadTile(tileFilename, File(cacheDir, "elevation"))
            }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val pageGapString = prefs.getString("page_gap_index", "1") ?: "1"
        val pageGap = pageGapString.toIntOrNull() ?: 1

        val minGradeInt = prefs.getInt("min_grade_trigger", 35)
        val triggerThreshold = minGradeInt / 10.0

        val cooldownSeconds = prefs.getInt("cooldown_seconds", 20)

        val probeDistance = if (currentSpeed > 8.0) 250.0 else 150.0
        val (projectedLat, projectedLon) = projectCoordinate(
            currentLat,
            currentLon,
            currentHeading,
            probeDistance,
        )

        val currentElevation = HgtReader.getElevationAtCoordinate(currentLat, currentLon, cacheDir, applicationContext)
        val projectedElevation = HgtReader.getElevationAtCoordinate(projectedLat, projectedLon, cacheDir, applicationContext)

        val deltaElevation = projectedElevation - currentElevation
        val grade = (deltaElevation / probeDistance) * 100.0

        when (currentState) {
            ClimbState.REGULAR -> {
                if (grade >= triggerThreshold) {
                    currentState = ClimbState.CONFIRMING_ASCENT
                    Log.d(TAG, "Grade $grade% >= $triggerThreshold%. Entering CONFIRMING_ASCENT.")
                }
            }
            ClimbState.CONFIRMING_ASCENT -> {
                if (grade >= triggerThreshold) {
                    currentState = ClimbState.ASCENDING
                    Log.d(TAG, "Ascent confirmed ($grade%). Switching page to ASCENDING (gap=$pageGap).")
                    executePageShift(HardwareButton.TOP_RIGHT, pageGap)
                } else {
                    currentState = ClimbState.REGULAR
                    Log.d(TAG, "Ascent unconfirmed ($grade%). Reverting to REGULAR.")
                }
            }
            ClimbState.ASCENDING -> {
                if (grade < triggerThreshold) {
                    currentState = ClimbState.COOLDOWN
                    Log.d(TAG, "Grade dropped to $grade%. Starting ${cooldownSeconds}s COOLDOWN timer.")
                    startCooldownTimer(cooldownSeconds, pageGap)
                }
            }
            ClimbState.COOLDOWN -> {
                if (grade >= triggerThreshold) {
                    cooldownJob?.cancel()
                    cooldownJob = null
                    currentState = ClimbState.ASCENDING
                    Log.d(TAG, "Grade re-entered ascent zone ($grade%). Cancelling COOLDOWN.")
                }
            }
        }
    }

    private fun startCooldownTimer(seconds: Int = 20, pageGap: Int = 1) {
        cooldownJob?.cancel()
        cooldownJob = serviceScope.launch {
            delay(seconds.seconds)
            if (currentState == ClimbState.COOLDOWN) {
                Log.d(TAG, "${seconds}s COOLDOWN complete. Switching back to REGULAR page (gap=$pageGap).")
                executePageShift(HardwareButton.TOP_LEFT, pageGap)
                currentState = ClimbState.REGULAR
            }
        }
    }

    fun executePageShift(button: HardwareButton, counts: Int = 1) {
        val action = when (button) {
            HardwareButton.TOP_LEFT -> PerformHardwareAction.TopLeftPress
            HardwareButton.TOP_RIGHT -> PerformHardwareAction.TopRightPress
            HardwareButton.BOTTOM_LEFT -> PerformHardwareAction.BottomLeftPress
            HardwareButton.BOTTOM_RIGHT -> PerformHardwareAction.BottomRightPress
            HardwareButton.CONTROL_CENTER -> PerformHardwareAction.ControlCenterComboPress
        }
        repeat(counts) {
            karooSystem.dispatch(action)
            try {
                Thread.sleep(150.milliseconds.inWholeMilliseconds)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun projectCoordinate(
        lat: Double,
        lon: Double,
        headingDegrees: Double,
        distanceMeters: Double,
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val angularDistance = distanceMeters / earthRadius
        val latRad = toRadians(lat)
        val lonRad = toRadians(lon)
        val bearingRad = toRadians(headingDegrees)

        val lat2Rad = asin(
            (sin(latRad) * cos(angularDistance)) +
                (cos(latRad) * sin(angularDistance) * cos(bearingRad)),
        )
        val lon2Rad = lonRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - (sin(latRad) * sin(lat2Rad)),
        )

        return Pair(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
    }

    override fun onDestroy() {
        cooldownJob?.cancel()
        serviceScope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
        Log.d(TAG, "AscentSwitch telemetry service destroyed.")
    }

    companion object {
        private const val TAG = "AscentSwitch"
    }
}
