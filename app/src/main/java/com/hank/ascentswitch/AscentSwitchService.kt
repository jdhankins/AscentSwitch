package com.hank.ascentswitch

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
    private var evaluationCounter: Long = 0

    override fun onCreate() {
        super.onCreate()

        AppLogger.init(applicationContext)
        AppLogger.d(TAG, "AscentSwitch telemetry service initialized.")

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                AppLogger.d(TAG, "Successfully connected to KarooSystemService.")
                subscribeToTelemetry()
            } else {
                AppLogger.w(TAG, "Disconnected or failed to connect to KarooSystemService.")
            }
        }

        scheduleMapAuditWorker()
        startBackgroundCalculationLoop()
    }

    private fun scheduleMapAuditWorker() {
        try {
            val mapAuditRequest = PeriodicWorkRequestBuilder<MapAuditWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "MapAuditWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                mapAuditRequest,
            )
            AppLogger.d(TAG, "Scheduled 24-hour MapAuditWorker routine.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule MapAuditWorker: ${e.message}", e)
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
                    AppLogger.e(TAG, "Error evaluating terrain: ${e.message}", e)
                }
                delay(2.seconds)
            }
        }
    }

    private fun evaluateUpcomingTerrain() {
        evaluationCounter++
        if ((currentLat == 0.0) && (currentLon == 0.0)) return

        val cacheDir = applicationContext.filesDir
        if (!HgtReader.hasTile(currentLat, currentLon, cacheDir)) {
            val tileFilename = HgtReader.getTileFilename(currentLat, currentLon)
            serviceScope.launch(Dispatchers.IO) {
                TileDownloader.downloadTile(tileFilename, File(cacheDir, "elevation"))
            }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        val profileFilter = prefs.getString("active_profile_filter", "ALL") ?: "ALL"
        if ((profileFilter == "OUTDOOR_ONLY") && (currentSpeed < 1.0) && (currentLat == 0.0)) {
            return
        }

        val pageGapString = prefs.getString("page_gap_index", "1") ?: "1"
        val pageGap = pageGapString.toIntOrNull() ?: 1

        val cooldownString = prefs.getString("cooldown_seconds", "20") ?: "20"
        val cooldownSeconds = cooldownString.toIntOrNull() ?: 20

        val directionSetting = prefs.getString("page_switch_direction", "TOP_RIGHT") ?: "TOP_RIGHT"
        val ascentButton = when (directionSetting) {
            "TOP_RIGHT" -> HardwareButton.TOP_RIGHT
            "TOP_LEFT" -> HardwareButton.TOP_LEFT
            "BOTTOM_LEFT" -> HardwareButton.BOTTOM_LEFT
            "BOTTOM_RIGHT" -> HardwareButton.BOTTOM_RIGHT
            else -> HardwareButton.TOP_RIGHT
        }

        val returnButton = when (ascentButton) {
            HardwareButton.TOP_RIGHT -> HardwareButton.TOP_LEFT
            HardwareButton.TOP_LEFT -> HardwareButton.TOP_RIGHT
            HardwareButton.BOTTOM_LEFT -> HardwareButton.TOP_LEFT
            HardwareButton.BOTTOM_RIGHT -> HardwareButton.TOP_LEFT
            else -> HardwareButton.TOP_LEFT
        }

        // Karoo CLIMBER Logic:
        // 1. Standard Climb Probe: 500m length along bearing vector
        val (proj500Lat, proj500Lon) = projectCoordinate(currentLat, currentLon, currentHeading, 500.0)
        // 2. Short Probe: 200m length along bearing vector
        val (proj200Lat, proj200Lon) = projectCoordinate(currentLat, currentLon, currentHeading, 200.0)

        val currentElev = HgtReader.getElevationAtCoordinate(currentLat, currentLon, cacheDir, applicationContext)
        val elev500 = HgtReader.getElevationAtCoordinate(proj500Lat, proj500Lon, cacheDir, applicationContext)
        val elev200 = HgtReader.getElevationAtCoordinate(proj200Lat, proj200Lon, cacheDir, applicationContext)

        val gain500 = elev500 - currentElev
        val grade500 = (gain500 / 500.0) * 100.0

        val gain200 = elev200 - currentElev
        val grade200 = (gain200 / 200.0) * 100.0

        // Karoo CLIMBER Trigger Criteria:
        // - 500m probe grade >= 3.0% OR net gain >= 15m (matching Karoo CLIMBER 3.0% / 30m gain rule)
        // - OR short punchy incline: 200m probe grade >= 4.0%
        val isClimbingTerrain = (grade500 >= 3.0) || (gain500 >= 15.0) || (grade200 >= 4.0)

        if (((evaluationCounter % 5L) == 0L) || isClimbingTerrain || (currentState != ClimbState.REGULAR)) {
            AppLogger.d(
                TAG,
                "Terrain Probe #$evaluationCounter: Pos=(${"%.4f".format(currentLat)}, ${"%.4f".format(currentLon)}), " +
                    "Speed=${"%.1f".format(currentSpeed * 3.6)}km/h, Heading=${"%.0f".format(currentHeading)}°, " +
                    "Elev=${"%.0f".format(currentElev)}m, Grade500=${"%.1f".format(grade500)}%, Gain500=${"%.1f".format(gain500)}m, " +
                    "State=$currentState",
            )
        }

        when (currentState) {
            ClimbState.REGULAR -> {
                if (isClimbingTerrain) {
                    currentState = ClimbState.CONFIRMING_ASCENT
                    AppLogger.d(TAG, "Incline detected (500m grade=${"%.1f".format(grade500)}%, gain=${"%.1f".format(gain500)}m). Entering CONFIRMING_ASCENT.")
                }
            }
            ClimbState.CONFIRMING_ASCENT -> {
                if (isClimbingTerrain) {
                    currentState = ClimbState.ASCENDING
                    AppLogger.d(TAG, "Climb confirmed matching Karoo CLIMBER spec. Switching page (Button=$ascentButton, gap=$pageGap).")
                    executePageShift(ascentButton, pageGap)
                } else {
                    currentState = ClimbState.REGULAR
                    AppLogger.d(TAG, "Incline unconfirmed. Reverting to REGULAR.")
                }
            }
            ClimbState.ASCENDING -> {
                if (!isClimbingTerrain) {
                    currentState = ClimbState.COOLDOWN
                    AppLogger.d(TAG, "Terrain flattened (500m grade=${"%.1f".format(grade500)}%). Starting ${cooldownSeconds}s COOLDOWN timer.")
                    startCooldownTimer(cooldownSeconds, pageGap, returnButton)
                }
            }
            ClimbState.COOLDOWN -> {
                if (isClimbingTerrain) {
                    cooldownJob?.cancel()
                    cooldownJob = null
                    currentState = ClimbState.ASCENDING
                    AppLogger.d(TAG, "Terrain steepened during cooldown. Cancelling COOLDOWN timer.")
                }
            }
        }
    }

    private fun startCooldownTimer(seconds: Int = 20, pageGap: Int = 1, returnButton: HardwareButton = HardwareButton.TOP_LEFT) {
        cooldownJob?.cancel()
        cooldownJob = serviceScope.launch {
            delay(seconds.seconds)
            if (currentState == ClimbState.COOLDOWN) {
                AppLogger.d(TAG, "${seconds}s COOLDOWN complete. Switching back to REGULAR page (Button=$returnButton, gap=$pageGap).")
                executePageShift(returnButton, pageGap)
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
        AppLogger.d(TAG, "Dispatching hardware action $action $counts time(s)...")
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
        AppLogger.d(TAG, "AscentSwitch telemetry service destroyed.")
    }

    companion object {
        private const val TAG = "AscentSwitch"
    }
}
