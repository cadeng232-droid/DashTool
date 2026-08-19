package com.example.dashtool

import android.content.Context
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * Tracks DoorDash's most recently displayed average-offer-wait range and
 * calibrates that live signal against DashTool's observed real wait times.
 *
 * DoorDash's displayed range is always the primary live demand signal.
 * Learned history only corrects the displayed range; DashTool does not invent
 * its own geographic regions in this model.
 */
object DoorDashDemandTracker {

    data class WaitRange(
        val minimumMinutes: Double,
        val maximumMinutes: Double,
        val observedAtWallTimeMs: Long
    ) {
        val midpointMinutes: Double
            get() = (minimumMinutes + maximumMinutes) / 2.0

        fun key(): String {
            return "${formatKeyNumber(minimumMinutes)}_${formatKeyNumber(maximumMinutes)}"
        }

        fun displayText(): String {
            return if (minimumMinutes == maximumMinutes) {
                "${formatDisplayNumber(minimumMinutes)} min"
            } else {
                "${formatDisplayNumber(minimumMinutes)}–${formatDisplayNumber(maximumMinutes)} min"
            }
        }
    }

    data class DemandEstimate(
        val range: WaitRange,
        val learnedCorrectionMinutes: Double,
        val calibratedExpectedWaitMinutes: Double,
        val calibrationSamples: Int,
        val calibrationConfidence: Double,
        val scoreAdjustment: Double
    ) {
        fun overlaySummary(): String {
            val correctionText =
                if (calibrationSamples <= 0) {
                    "uncalibrated"
                } else {
                    String.format(
                        Locale.US,
                        "%+.1f min learned",
                        learnedCorrectionMinutes
                    )
                }

            return buildString {
                append("Demand: DD ")
                append(range.displayText())
                append(" → ")
                append(
                    String.format(
                        Locale.US,
                        "%.1f min",
                        calibratedExpectedWaitMinutes
                    )
                )
                append(" (")
                append(correctionText)
                append(")")
                append("\nDemand score: ")
                append(
                    String.format(
                        Locale.US,
                        "%+.1f",
                        scoreAdjustment
                    )
                )
            }
        }
    }

    private const val LOG_TAG = "DashToolDemand"
    private const val PREFS_NAME = "dash_tool_doordash_demand"

    private const val KEY_RANGE_MIN = "latest_range_min_minutes"
    private const val KEY_RANGE_MAX = "latest_range_max_minutes"
    private const val KEY_RANGE_OBSERVED_AT = "latest_range_observed_at_ms"

    private const val KEY_PENDING_STARTED_AT = "pending_wait_started_at_ms"
    private const val KEY_PENDING_RANGE_MIN = "pending_range_min_minutes"
    private const val KEY_PENDING_RANGE_MAX = "pending_range_max_minutes"
    private const val KEY_PENDING_RANGE_OBSERVED_AT = "pending_range_observed_at_ms"
    private const val KEY_PENDING_START_REASON = "pending_start_reason"
    private const val KEY_PENDING_LOCAL_HOUR = "pending_local_hour"
    private const val KEY_PENDING_DAY_OF_WEEK = "pending_day_of_week"

    private const val MAX_LEARNED_CORRECTION_MINUTES = 10.0
    private const val MAX_LEARNABLE_WAIT_MS = 90L * 60L * 1_000L

    /*
     * Score influence is intentionally bounded. A busy market can make a
     * mediocre order a little less attractive, and a slow market can make it
     * a little more attractive, but demand cannot overwhelm order economics.
     */
    private const val MAX_SCORE_ADJUSTMENT = 0.75
    private const val VERY_BUSY_WAIT_MINUTES = 2.0
    private const val NEUTRAL_WAIT_MINUTES = 5.0
    private const val VERY_SLOW_WAIT_MINUTES = 12.0

    private const val CALIBRATION_REFRESH_MS = 10L * 60L * 1_000L

    private val rangePattern = Regex(
        pattern = """(?i)(\d{1,2}(?:\.\d+)?)\s*(?:-|–|—|to)\s*(\d{1,2}(?:\.\d+)?)\s*(?:min|mins|minute|minutes)\b"""
    )

    private val singlePattern = Regex(
        pattern = """(?i)(\d{1,2}(?:\.\d+)?)\s*(?:min|mins|minute|minutes)\b"""
    )

    private val waitMarkers = listOf(
        "average offer wait time",
        "average offer wait",
        "offer wait time",
        "average wait time"
    )

    @Synchronized
    fun observeDoorDashText(
        context: Context,
        completeNodeText: String,
        observedAtWallTimeMs: Long
    ) {
        val parsed = parseWaitRange(
            completeNodeText = completeNodeText,
            observedAtWallTimeMs = observedAtWallTimeMs
        ) ?: return

        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val previousMin =
            if (preferences.contains(KEY_RANGE_MIN)) {
                preferences.getFloat(KEY_RANGE_MIN, 0f).toDouble()
            } else {
                null
            }
        val previousMax =
            if (preferences.contains(KEY_RANGE_MAX)) {
                preferences.getFloat(KEY_RANGE_MAX, 0f).toDouble()
            } else {
                null
            }

        preferences.edit()
            .putFloat(KEY_RANGE_MIN, parsed.minimumMinutes.toFloat())
            .putFloat(KEY_RANGE_MAX, parsed.maximumMinutes.toFloat())
            .putLong(KEY_RANGE_OBSERVED_AT, parsed.observedAtWallTimeMs)
            .apply()

        val rangeChanged =
            previousMin != parsed.minimumMinutes ||
                    previousMax != parsed.maximumMinutes

        if (rangeChanged) {
            Log.d(
                LOG_TAG,
                "DoorDash wait range updated to ${parsed.displayText()}."
            )
        }

        if (
            rangeChanged ||
            calibrationIsStale(
                preferences = preferences,
                range = parsed,
                nowWallTimeMs = observedAtWallTimeMs
            )
        ) {
            Thread {
                refreshCalibration(
                    context = appContext,
                    range = parsed
                )
            }.start()
        }
    }

    /**
     * Starts one idle exposure interval. Repeated accessibility redraws while
     * waiting do not reset the start time.
     */
    @Synchronized
    fun onWaitingScreen(
        context: Context,
        wallTimeMs: Long,
        reason: String = "WAITING_SCREEN"
    ) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        if (preferences.contains(KEY_PENDING_STARTED_AT)) {
            return
        }

        val range = loadLatestRange(preferences) ?: return
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = wallTimeMs

        preferences.edit()
            .putLong(KEY_PENDING_STARTED_AT, wallTimeMs)
            .putFloat(KEY_PENDING_RANGE_MIN, range.minimumMinutes.toFloat())
            .putFloat(KEY_PENDING_RANGE_MAX, range.maximumMinutes.toFloat())
            .putLong(KEY_PENDING_RANGE_OBSERVED_AT, range.observedAtWallTimeMs)
            .putString(KEY_PENDING_START_REASON, reason)
            .putInt(KEY_PENDING_LOCAL_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
            .putInt(KEY_PENDING_DAY_OF_WEEK, calendar.get(Calendar.DAY_OF_WEEK))
            .apply()

        Log.d(
            LOG_TAG,
            "Started offer-wait calibration interval using ${range.displayText()}."
        )
    }

    /**
     * Ends the current idle interval at the next detected offer and uploads the
     * comparison between DoorDash's prediction and the actual wait.
     */
    @Synchronized
    fun onOfferDetected(
        context: Context,
        offerId: String,
        detectedAtWallTimeMs: Long,
        detectionSource: String?
    ) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        if (!preferences.contains(KEY_PENDING_STARTED_AT)) {
            return
        }

        val startedAt = preferences.getLong(KEY_PENDING_STARTED_AT, 0L)
        val minMinutes = preferences.getFloat(KEY_PENDING_RANGE_MIN, 0f).toDouble()
        val maxMinutes = preferences.getFloat(KEY_PENDING_RANGE_MAX, 0f).toDouble()
        val rangeObservedAt = preferences.getLong(KEY_PENDING_RANGE_OBSERVED_AT, 0L)
        val startReason = preferences.getString(KEY_PENDING_START_REASON, "WAITING_SCREEN")
            ?: "WAITING_SCREEN"
        val localHour = preferences.getInt(KEY_PENDING_LOCAL_HOUR, -1)
        val dayOfWeek = preferences.getInt(KEY_PENDING_DAY_OF_WEEK, -1)

        clearPending(preferences)

        val actualWaitMs = detectedAtWallTimeMs - startedAt
        val range = WaitRange(
            minimumMinutes = minMinutes,
            maximumMinutes = maxMinutes,
            observedAtWallTimeMs = rangeObservedAt
        )

        val isManual =
            detectionSource ==
                    com.example.dashtool.data.OfferDetectionSource.MANUAL_SCAN

        val exclusionReason = when {
            detectedAtWallTimeMs <= startedAt -> "INVALID_TIMESTAMPS"
            actualWaitMs > MAX_LEARNABLE_WAIT_MS -> "WAIT_TOO_LONG"
            isManual -> "MANUAL_OFFER_DETECTION"
            else -> null
        }

        val actualWaitMinutes = actualWaitMs.coerceAtLeast(0L) / 60_000.0
        val predictionErrorMinutes = actualWaitMinutes - range.midpointMinutes

        val payload = JSONObject().apply {
            put("sample_id", "offer_wait_${UUID.randomUUID()}")
            put("next_offer_id", offerId)
            put("range_min_minutes", range.minimumMinutes)
            put("range_max_minutes", range.maximumMinutes)
            put("range_midpoint_minutes", range.midpointMinutes)
            put("range_observed_at_wall_time_ms", range.observedAtWallTimeMs)
            put("wait_started_at_wall_time_ms", startedAt)
            put("next_offer_detected_at_wall_time_ms", detectedAtWallTimeMs)
            put("actual_wait_ms", actualWaitMs.coerceAtLeast(0L))
            put("actual_wait_minutes", actualWaitMinutes)
            put("prediction_error_minutes", predictionErrorMinutes)
            put("start_reason", startReason)
            put("next_offer_detection_source", detectionSource ?: JSONObject.NULL)
            put("local_hour", if (localHour >= 0) localHour else JSONObject.NULL)
            put("day_of_week", if (dayOfWeek >= 0) dayOfWeek else JSONObject.NULL)
            put("exclude_from_learning", exclusionReason != null)
            put("exclusion_reason", exclusionReason ?: JSONObject.NULL)
        }

        Thread {
            val uploaded = WaitingDataUploadManager.enqueueAndTryUpload(
                context = appContext,
                endpoint = "/offer-wait-samples",
                payload = payload
            )

            if (uploaded && exclusionReason == null) {
                refreshCalibration(
                    context = appContext,
                    range = range
                )
            }
        }.start()

        Log.d(
            LOG_TAG,
            "Closed wait interval: DD=${range.displayText()}, " +
                    "actual=${String.format(Locale.US, "%.1f", actualWaitMinutes)} min, " +
                    "excluded=${exclusionReason != null}."
        )
    }

    fun currentEstimate(context: Context): DemandEstimate? {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val range = loadLatestRange(preferences) ?: return null
        val key = range.key()

        val learnedCorrection = preferences.getFloat(
            calibrationAdjustmentKey(key),
            0f
        ).toDouble().coerceIn(
            -MAX_LEARNED_CORRECTION_MINUTES,
            MAX_LEARNED_CORRECTION_MINUTES
        )

        val samples = preferences.getInt(
            calibrationSamplesKey(key),
            0
        )
        val confidence = preferences.getFloat(
            calibrationConfidenceKey(key),
            0f
        ).toDouble().coerceIn(0.0, 1.0)

        val calibratedWait =
            (range.midpointMinutes + learnedCorrection)
                .coerceIn(0.0, 60.0)

        return DemandEstimate(
            range = range,
            learnedCorrectionMinutes = learnedCorrection,
            calibratedExpectedWaitMinutes = calibratedWait,
            calibrationSamples = samples,
            calibrationConfidence = confidence,
            scoreAdjustment = scoreAdjustmentForWait(calibratedWait)
        )
    }

    private fun parseWaitRange(
        completeNodeText: String,
        observedAtWallTimeMs: Long
    ): WaitRange? {
        val normalized = completeNodeText
            .lowercase(Locale.US)
            .replace('–', '-')
            .replace('—', '-')

        val markerPositions = waitMarkers.mapNotNull { marker ->
            normalized.indexOf(marker)
                .takeIf { it >= 0 }
        }

        if (markerPositions.isEmpty()) {
            return null
        }

        val allRangeMatches = rangePattern.findAll(completeNodeText).toList()
        val bestRangeMatch = allRangeMatches.minByOrNull { match ->
            markerPositions.minOf { markerPosition ->
                kotlin.math.abs(match.range.first - markerPosition)
            }
        }

        if (bestRangeMatch != null) {
            val distance = markerPositions.minOf { markerPosition ->
                kotlin.math.abs(bestRangeMatch.range.first - markerPosition)
            }
            if (distance <= 260) {
                val first = bestRangeMatch.groupValues[1].toDoubleOrNull()
                val second = bestRangeMatch.groupValues[2].toDoubleOrNull()
                if (first != null && second != null) {
                    val low = minOf(first, second)
                    val high = maxOf(first, second)
                    if (validWaitRange(low, high)) {
                        return WaitRange(low, high, observedAtWallTimeMs)
                    }
                }
            }
        }

        /*
         * Fallback for DoorDash variants that show a single value instead of
         * a range. Restrict it to text close to the wait label to avoid using
         * unrelated delivery times elsewhere on the screen.
         */
        val allSingleMatches = singlePattern.findAll(completeNodeText).toList()
        val bestSingle = allSingleMatches.minByOrNull { match ->
            markerPositions.minOf { markerPosition ->
                kotlin.math.abs(match.range.first - markerPosition)
            }
        }
        if (bestSingle != null) {
            val distance = markerPositions.minOf { markerPosition ->
                kotlin.math.abs(bestSingle.range.first - markerPosition)
            }
            val value = bestSingle.groupValues[1].toDoubleOrNull()
            if (distance <= 180 && value != null && validWaitRange(value, value)) {
                return WaitRange(value, value, observedAtWallTimeMs)
            }
        }

        return null
    }

    private fun validWaitRange(low: Double, high: Double): Boolean {
        return low >= 0.0 && high >= low && high <= 60.0
    }

    private fun loadLatestRange(
        preferences: android.content.SharedPreferences
    ): WaitRange? {
        if (
            !preferences.contains(KEY_RANGE_MIN) ||
            !preferences.contains(KEY_RANGE_MAX)
        ) {
            return null
        }

        val low = preferences.getFloat(KEY_RANGE_MIN, 0f).toDouble()
        val high = preferences.getFloat(KEY_RANGE_MAX, 0f).toDouble()
        if (!validWaitRange(low, high)) {
            return null
        }

        return WaitRange(
            minimumMinutes = low,
            maximumMinutes = high,
            observedAtWallTimeMs = preferences.getLong(KEY_RANGE_OBSERVED_AT, 0L)
        )
    }

    private fun clearPending(
        preferences: android.content.SharedPreferences
    ) {
        preferences.edit()
            .remove(KEY_PENDING_STARTED_AT)
            .remove(KEY_PENDING_RANGE_MIN)
            .remove(KEY_PENDING_RANGE_MAX)
            .remove(KEY_PENDING_RANGE_OBSERVED_AT)
            .remove(KEY_PENDING_START_REASON)
            .remove(KEY_PENDING_LOCAL_HOUR)
            .remove(KEY_PENDING_DAY_OF_WEEK)
            .apply()
    }

    private fun scoreAdjustmentForWait(
        expectedWaitMinutes: Double
    ): Double {
        return when {
            expectedWaitMinutes <= VERY_BUSY_WAIT_MINUTES ->
                -MAX_SCORE_ADJUSTMENT

            expectedWaitMinutes < NEUTRAL_WAIT_MINUTES ->
                -MAX_SCORE_ADJUSTMENT *
                        (NEUTRAL_WAIT_MINUTES - expectedWaitMinutes) /
                        (NEUTRAL_WAIT_MINUTES - VERY_BUSY_WAIT_MINUTES)

            expectedWaitMinutes <= NEUTRAL_WAIT_MINUTES ->
                0.0

            expectedWaitMinutes < VERY_SLOW_WAIT_MINUTES ->
                MAX_SCORE_ADJUSTMENT *
                        (expectedWaitMinutes - NEUTRAL_WAIT_MINUTES) /
                        (VERY_SLOW_WAIT_MINUTES - NEUTRAL_WAIT_MINUTES)

            else ->
                MAX_SCORE_ADJUSTMENT
        }.coerceIn(
            -MAX_SCORE_ADJUSTMENT,
            MAX_SCORE_ADJUSTMENT
        )
    }

    private fun calibrationIsStale(
        preferences: android.content.SharedPreferences,
        range: WaitRange,
        nowWallTimeMs: Long
    ): Boolean {
        val updatedAt = preferences.getLong(
            calibrationUpdatedAtKey(range.key()),
            0L
        )
        return updatedAt <= 0L || nowWallTimeMs - updatedAt >= CALIBRATION_REFRESH_MS
    }

    private fun refreshCalibration(
        context: Context,
        range: WaitRange
    ) {
        val endpoint =
            "/offer-wait-calibration" +
                    "?min_minutes=${range.minimumMinutes}" +
                    "&max_minutes=${range.maximumMinutes}"

        val response = WaitingDataClient.getJson(endpoint) ?: return

        val adjustment = response.optDouble("adjustment_minutes", 0.0)
            .coerceIn(
                -MAX_LEARNED_CORRECTION_MINUTES,
                MAX_LEARNED_CORRECTION_MINUTES
            )
        val samples = response.optInt("range_sample_count", 0)
        val confidence = response.optDouble("confidence", 0.0)
            .coerceIn(0.0, 1.0)

        val preferences = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val key = range.key()
        preferences.edit()
            .putFloat(calibrationAdjustmentKey(key), adjustment.toFloat())
            .putInt(calibrationSamplesKey(key), samples)
            .putFloat(calibrationConfidenceKey(key), confidence.toFloat())
            .putLong(calibrationUpdatedAtKey(key), System.currentTimeMillis())
            .apply()

        Log.d(
            LOG_TAG,
            "Calibration for ${range.displayText()}: " +
                    "${String.format(Locale.US, "%+.2f", adjustment)} min " +
                    "from $samples exact-range samples."
        )
    }

    private fun calibrationAdjustmentKey(key: String) =
        "calibration_adjustment_$key"

    private fun calibrationSamplesKey(key: String) =
        "calibration_samples_$key"

    private fun calibrationConfidenceKey(key: String) =
        "calibration_confidence_$key"

    private fun calibrationUpdatedAtKey(key: String) =
        "calibration_updated_at_$key"

    private fun formatKeyNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
                .replace('.', '_')
        }
    }

    private fun formatDisplayNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }
}