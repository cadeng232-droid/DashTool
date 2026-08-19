package com.example.dashtool

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent post-delivery waiting-session tracker.
 *
 * Phase 1 provides the complete session state machine and center-arrival GPS
 * tracking. The recommender added next only needs to call setTargetCenter().
 * Historical wait starts when the phone actually reaches the target center,
 * never when the delivery ends.
 */
class WaitingAreaTracker(
    context: Context
) {

    data class WaitingCenter(
        val centerId: String,
        val centerName: String,
        val latitude: Double,
        val longitude: Double,
        val restaurantCount: Int = 0
    )

    private data class ResumeState(
        val center: WaitingCenter?,
        val recommendedCenterId: String?,
        val wasArrived: Boolean,
        val hadFirstEntry: Boolean,
        val arrivalLatitude: Double?,
        val arrivalLongitude: Double?,
        val candidates: List<WaitingCenter>
    )

    companion object {
        private const val LOG_TAG = "DashToolWaiting"
        private const val PREFS_NAME = "dash_tool_waiting_area"
        private const val RESUME_PREFS_NAME = "dash_tool_waiting_area_resume"

        // Large enough for normal shopping-center GPS variation without
        // treating a drive-by several blocks away as an arrival.
        private const val ARRIVAL_RADIUS_METERS = 175.0f
        private const val ARRIVAL_CONFIRMATION_MS = 20_000L

        /*
         * A center wait that survives for many hours is almost certainly a
         * stale restored session rather than a meaningful between-offer wait.
         * Preserve it in raw telemetry, but never let it train the model.
         */
        private const val MAX_LEARNABLE_WAIT_AT_CENTER_MS =
            3L * 60L * 60L * 1_000L

        private const val MOVING_UPDATE_INTERVAL_MS = 10_000L
        private const val MOVING_MIN_UPDATE_INTERVAL_MS = 5_000L

        private const val KEY_ACTIVE = "active"
        private const val KEY_WAIT_SESSION_ID = "wait_session_id"
        private const val KEY_PREVIOUS_OFFER_ID = "previous_offer_id"
        private const val KEY_DELIVERY_COMPLETED_AT = "delivery_completed_at"
        private const val KEY_WAIT_STARTED_AT = "wait_started_at"
        private const val KEY_START_REASON = "start_reason"
        private const val KEY_CENTER_ID = "center_id"
        private const val KEY_CENTER_NAME = "center_name"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LON = "center_lon"
        private const val KEY_RECOMMENDED_CENTER_ID = "recommended_center_id"
        private const val KEY_CENTER_FIRST_ENTRY_AT = "center_first_entry_at"
        private const val KEY_CENTER_ARRIVAL_AT = "center_arrival_at"
        private const val KEY_ARRIVAL_LAT = "arrival_lat"
        private const val KEY_ARRIVAL_LON = "arrival_lon"

        private const val KEY_RESUME_AVAILABLE = "resume_available"
        private const val KEY_RESUME_CENTER_ID = "resume_center_id"
        private const val KEY_RESUME_CENTER_NAME = "resume_center_name"
        private const val KEY_RESUME_CENTER_LAT = "resume_center_lat"
        private const val KEY_RESUME_CENTER_LON = "resume_center_lon"
        private const val KEY_RESUME_RECOMMENDED_CENTER_ID =
            "resume_recommended_center_id"
        private const val KEY_RESUME_WAS_ARRIVED = "resume_was_arrived"
        private const val KEY_RESUME_HAD_FIRST_ENTRY =
            "resume_had_first_entry"
        private const val KEY_RESUME_ARRIVAL_LAT = "resume_arrival_lat"
        private const val KEY_RESUME_ARRIVAL_LON = "resume_arrival_lon"
        private const val KEY_RESUME_CANDIDATES_JSON =
            "resume_candidates_json"
    }

    private val appContext =
        context.applicationContext

    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val resumePreferences: SharedPreferences =
        appContext.getSharedPreferences(
            RESUME_PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val locationClient =
        LocationServices
            .getFusedLocationProviderClient(
                appContext
            )

    private var locationUpdatesRunning = false

    /*
     * Alternatives are kept only for the current process/session. If the
     * driver goes to a different discovered center, GPS can reassign the
     * observation to the center actually reached.
     */
    private var candidateCenters: List<WaitingCenter> = emptyList()

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(
                result: LocationResult
            ) {
                val location =
                    result.lastLocation
                        ?: return

                processLocation(location)
            }
        }

    init {
        restoreTrackingIfNeeded()
    }

    /**
     * Starts a post-delivery session only after a genuine return to DoorDash's
     * waiting screen. Fallback completion inferred from a later offer must not
     * call this method.
     */
    fun onDeliveryCompleted(
        previousOfferId: String,
        completedAtWallTime: Long
    ) {
        stopLocationUpdates()
        candidateCenters = emptyList()
        clearResumeState()

        preferences.edit()
            .clear()
            .putBoolean(
                KEY_ACTIVE,
                true
            )
            .putString(
                KEY_WAIT_SESSION_ID,
                "wait_${UUID.randomUUID()}"
            )
            .putString(
                KEY_PREVIOUS_OFFER_ID,
                previousOfferId
            )
            .putLong(
                KEY_DELIVERY_COMPLETED_AT,
                completedAtWallTime
            )
            .putLong(
                KEY_WAIT_STARTED_AT,
                completedAtWallTime
            )
            .putString(
                KEY_START_REASON,
                "DELIVERY_COMPLETED"
            )
            .apply()

        Log.d(
            LOG_TAG,
            "Started post-delivery wait tracking for $previousOfferId."
        )
    }

    /**
     * Called by the waiting-area recommender after it chooses a center.
     */
    fun setTargetCenter(
        center: WaitingCenter,
        wasRecommended: Boolean = true
    ) {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            Log.w(
                LOG_TAG,
                "Ignored target center because no post-delivery session is active."
            )
            return
        }

        val editor =
            preferences.edit()
                .putString(
                    KEY_CENTER_ID,
                    center.centerId
                )
                .putString(
                    KEY_CENTER_NAME,
                    center.centerName
                )
                .putLong(
                    KEY_CENTER_LAT,
                    center.latitude.toBits()
                )
                .putLong(
                    KEY_CENTER_LON,
                    center.longitude.toBits()
                )
                .remove(
                    KEY_CENTER_FIRST_ENTRY_AT
                )
                .remove(
                    KEY_CENTER_ARRIVAL_AT
                )
                .remove(
                    KEY_ARRIVAL_LAT
                )
                .remove(
                    KEY_ARRIVAL_LON
                )

        if (wasRecommended) {
            editor.putString(
                KEY_RECOMMENDED_CENTER_ID,
                center.centerId
            )
        }

        editor.apply()

        startLocationUpdates()

        Log.d(
            LOG_TAG,
            "Tracking arrival at ${center.centerName}."
        )
    }

    /**
     * Sets the recommendation plus the other centers returned by the same
     * discovery scan. This lets GPS attribute the wait to an alternative
     * center if the driver ignores the recommendation and reaches another
     * discovered center instead.
     */
    fun setRecommendation(
        recommendedCenter: WaitingCenter,
        candidates: List<WaitingCenter>
    ) {
        candidateCenters = candidates
            .distinctBy { center -> center.centerId }

        setTargetCenter(
            center = recommendedCenter,
            wasRecommended = true
        )
    }

    /**
     * If the user goes somewhere other than the recommendation, the
     * recommender/center detector can switch the target. The actual center ID
     * is what the historical wait sample is attributed to.
     */
    fun switchActualCenter(
        center: WaitingCenter
    ) {
        setTargetCenter(
            center = center,
            wasRecommended = false
        )
    }

    /**
     * Ends the current interval on the next valid offer. If the center was
     * never reached, the session is uploaded as excluded so it cannot corrupt
     * center wait statistics.
     */
    fun onOfferDetected(
        nextOfferId: String,
        detectedAtWallTime: Long
    ) {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            return
        }

        /*
         * The offer ends the current historical wait interval immediately,
         * but we do not yet know whether the driver will accept it. Preserve
         * enough center state to resume cleanly if the offer is rejected.
         */
        saveResumeState()

        val payload =
            buildCompletedPayload(
                nextOfferId = nextOfferId,
                detectedAtWallTime = detectedAtWallTime
            )

        stopLocationUpdates()
        candidateCenters = emptyList()
        preferences.edit()
            .clear()
            .apply()

        Thread {
            WaitingDataUploadManager
                .enqueueAndTryUpload(
                    context = appContext,
                    endpoint = "/waiting-sessions",
                    payload = payload
                )
        }.start()
    }

    /**
     * Called only after the offer decision is known to be NOT ACCEPTED. The
     * previous interval correctly ended when the offer appeared; this starts
     * a new interval at rejection time and restores the same center context.
     */
    fun onOfferNotAccepted(
        rejectedOfferId: String,
        rejectedAtWallTime: Long
    ) {
        val resumeState =
            loadResumeState()
                ?: return

        stopLocationUpdates()
        candidateCenters =
            resumeState.candidates

        val editor =
            preferences.edit()
                .clear()
                .putBoolean(
                    KEY_ACTIVE,
                    true
                )
                .putString(
                    KEY_WAIT_SESSION_ID,
                    "wait_${UUID.randomUUID()}"
                )
                .putString(
                    KEY_PREVIOUS_OFFER_ID,
                    rejectedOfferId
                )
                .putLong(
                    KEY_WAIT_STARTED_AT,
                    rejectedAtWallTime
                )
                .putString(
                    KEY_START_REASON,
                    "OFFER_NOT_ACCEPTED"
                )

        val center =
            resumeState.center

        if (center != null) {
            editor
                .putString(
                    KEY_CENTER_ID,
                    center.centerId
                )
                .putString(
                    KEY_CENTER_NAME,
                    center.centerName
                )
                .putLong(
                    KEY_CENTER_LAT,
                    center.latitude.toBits()
                )
                .putLong(
                    KEY_CENTER_LON,
                    center.longitude.toBits()
                )
        }

        resumeState.recommendedCenterId
            ?.let { recommendedId ->
                editor.putString(
                    KEY_RECOMMENDED_CENTER_ID,
                    recommendedId
                )
            }

        if (
            center != null &&
            resumeState.wasArrived
        ) {
            /*
             * The driver was already waiting at this center when the offer
             * appeared. The new interval begins at the rejection timestamp,
             * not at the old arrival timestamp, so decision time is never
             * counted as post-rejection waiting.
             */
            editor.putLong(
                KEY_CENTER_ARRIVAL_AT,
                rejectedAtWallTime
            )

            resumeState.arrivalLatitude
                ?.let { latitude ->
                    editor.putLong(
                        KEY_ARRIVAL_LAT,
                        latitude.toBits()
                    )
                }

            resumeState.arrivalLongitude
                ?.let { longitude ->
                    editor.putLong(
                        KEY_ARRIVAL_LON,
                        longitude.toBits()
                    )
                }
        } else if (
            center != null &&
            resumeState.hadFirstEntry
        ) {
            /*
             * The offer arrived during the 20-second arrival confirmation
             * window. Restart that confirmation from rejection time so the
             * offer-decision period cannot leak into the measured wait.
             */
            editor.putLong(
                KEY_CENTER_FIRST_ENTRY_AT,
                rejectedAtWallTime
            )
        }

        editor.apply()
        clearResumeState()

        if (
            center != null &&
            !resumeState.wasArrived
        ) {
            startLocationUpdates()
        }

        Log.d(
            LOG_TAG,
            "Resumed waiting-center tracking after rejected offer " +
                    "$rejectedOfferId; center=" +
                    (center?.centerId ?: "none") +
                    ", alreadyAtCenter=${resumeState.wasArrived}."
        )
    }

    /**
     * An accepted offer means the saved resume snapshot is no longer useful.
     */
    fun onOfferAccepted() {
        clearResumeState()
        candidateCenters = emptyList()
    }

    fun close() {
        stopLocationUpdates()
        candidateCenters = emptyList()
    }

    private fun buildCompletedPayload(
        nextOfferId: String,
        detectedAtWallTime: Long
    ): JSONObject {
        val sessionId =
            preferences.getString(
                KEY_WAIT_SESSION_ID,
                null
            ) ?: "wait_${UUID.randomUUID()}"

        val previousOfferId =
            preferences.getString(
                KEY_PREVIOUS_OFFER_ID,
                null
            )

        val completedAt =
            preferences.getLong(
                KEY_DELIVERY_COMPLETED_AT,
                0L
            )

        val waitingStartedAt =
            preferences.getLong(
                KEY_WAIT_STARTED_AT,
                completedAt
            )

        val startReason =
            preferences.getString(
                KEY_START_REASON,
                "UNKNOWN"
            ) ?: "UNKNOWN"

        val centerId =
            preferences.getString(
                KEY_CENTER_ID,
                null
            )

        val centerName =
            preferences.getString(
                KEY_CENTER_NAME,
                null
            )

        val centerArrivalAt =
            if (
                preferences.contains(
                    KEY_CENTER_ARRIVAL_AT
                )
            ) {
                preferences.getLong(
                    KEY_CENTER_ARRIVAL_AT,
                    0L
                )
            } else {
                null
            }

        val reachedCenter =
            centerId != null &&
                    centerArrivalAt != null &&
                    centerArrivalAt > 0L &&
                    detectedAtWallTime >= centerArrivalAt

        val waitAtCenterMs =
            if (reachedCenter) {
                detectedAtWallTime - centerArrivalAt!!
            } else {
                null
            }

        val travelToCenterMs =
            if (
                reachedCenter &&
                waitingStartedAt > 0L &&
                centerArrivalAt!! >= waitingStartedAt
            ) {
                centerArrivalAt - waitingStartedAt
            } else {
                null
            }

        val waitExceedsLearningLimit =
            waitAtCenterMs != null &&
                    waitAtCenterMs >
                    MAX_LEARNABLE_WAIT_AT_CENTER_MS

        val excludeFromLearning =
            !reachedCenter ||
                    waitExceedsLearningLimit

        val exclusionReason =
            when {
                !reachedCenter &&
                        centerId == null ->
                    "NO_CENTER_ASSIGNED"

                !reachedCenter ->
                    "OFFER_BEFORE_CENTER_ARRIVAL"

                waitExceedsLearningLimit ->
                    "WAIT_EXCEEDS_3_HOURS"

                else ->
                    null
            }

        return JSONObject().apply {
            put(
                "waiting_session_id",
                sessionId
            )
            put(
                "previous_offer_id",
                previousOfferId ?: JSONObject.NULL
            )
            put(
                "next_offer_id",
                nextOfferId
            )
            put(
                "delivery_completed_at_wall_time_ms",
                completedAt.takeIf { it > 0L }
                    ?: JSONObject.NULL
            )
            put(
                "waiting_started_at_wall_time_ms",
                waitingStartedAt.takeIf { it > 0L }
                    ?: JSONObject.NULL
            )
            put(
                "start_reason",
                startReason
            )
            put(
                "center_id",
                centerId ?: JSONObject.NULL
            )
            put(
                "center_name",
                centerName ?: JSONObject.NULL
            )
            putOptionalDouble(
                "center_latitude",
                readDouble(KEY_ARRIVAL_LAT)
                    ?: readDouble(KEY_CENTER_LAT)
            )
            putOptionalDouble(
                "center_longitude",
                readDouble(KEY_ARRIVAL_LON)
                    ?: readDouble(KEY_CENTER_LON)
            )
            put(
                "recommended_center_id",
                preferences.getString(
                    KEY_RECOMMENDED_CENTER_ID,
                    null
                ) ?: JSONObject.NULL
            )
            put(
                "center_arrived_at_wall_time_ms",
                centerArrivalAt ?: JSONObject.NULL
            )
            putOptionalDouble(
                "arrival_latitude",
                readDouble(
                    KEY_ARRIVAL_LAT
                )
            )
            putOptionalDouble(
                "arrival_longitude",
                readDouble(
                    KEY_ARRIVAL_LON
                )
            )
            put(
                "next_offer_detected_at_wall_time_ms",
                detectedAtWallTime
            )
            put(
                "travel_to_center_ms",
                travelToCenterMs ?: JSONObject.NULL
            )
            put(
                "wait_at_center_ms",
                waitAtCenterMs ?: JSONObject.NULL
            )
            put(
                "offer_before_arrival",
                !reachedCenter
            )
            put(
                "exclude_from_learning",
                excludeFromLearning
            )
            put(
                "exclusion_reason",
                exclusionReason
                    ?: JSONObject.NULL
            )
        }
    }

    private fun saveResumeState() {
        val centerId = preferences.getString(
            KEY_CENTER_ID,
            null
        )

        val centerName = preferences.getString(
            KEY_CENTER_NAME,
            null
        )

        val centerLatitude = readDouble(
            KEY_CENTER_LAT
        )

        val centerLongitude = readDouble(
            KEY_CENTER_LON
        )

        val candidatesJson = JSONArray()
        candidateCenters.forEach { center ->
            candidatesJson.put(
                JSONObject().apply {
                    put("center_id", center.centerId)
                    put("center_name", center.centerName)
                    put("latitude", center.latitude)
                    put("longitude", center.longitude)
                    put("restaurant_count", center.restaurantCount)
                }
            )
        }

        val editor = resumePreferences.edit()
            .clear()
            .putBoolean(
                KEY_RESUME_AVAILABLE,
                true
            )
            .putString(
                KEY_RESUME_RECOMMENDED_CENTER_ID,
                preferences.getString(
                    KEY_RECOMMENDED_CENTER_ID,
                    null
                )
            )
            .putBoolean(
                KEY_RESUME_WAS_ARRIVED,
                preferences.contains(
                    KEY_CENTER_ARRIVAL_AT
                )
            )
            .putBoolean(
                KEY_RESUME_HAD_FIRST_ENTRY,
                preferences.contains(
                    KEY_CENTER_FIRST_ENTRY_AT
                )
            )
            .putString(
                KEY_RESUME_CANDIDATES_JSON,
                candidatesJson.toString()
            )

        if (
            centerId != null &&
            centerName != null &&
            centerLatitude != null &&
            centerLongitude != null
        ) {
            editor
                .putString(
                    KEY_RESUME_CENTER_ID,
                    centerId
                )
                .putString(
                    KEY_RESUME_CENTER_NAME,
                    centerName
                )
                .putLong(
                    KEY_RESUME_CENTER_LAT,
                    centerLatitude.toBits()
                )
                .putLong(
                    KEY_RESUME_CENTER_LON,
                    centerLongitude.toBits()
                )
        }

        readDouble(KEY_ARRIVAL_LAT)
            ?.let { latitude ->
                editor.putLong(
                    KEY_RESUME_ARRIVAL_LAT,
                    latitude.toBits()
                )
            }

        readDouble(KEY_ARRIVAL_LON)
            ?.let { longitude ->
                editor.putLong(
                    KEY_RESUME_ARRIVAL_LON,
                    longitude.toBits()
                )
            }

        editor.apply()
    }

    private fun loadResumeState(): ResumeState? {
        if (
            !resumePreferences.getBoolean(
                KEY_RESUME_AVAILABLE,
                false
            )
        ) {
            return null
        }

        val center =
            if (
                resumePreferences.contains(
                    KEY_RESUME_CENTER_ID
                ) &&
                resumePreferences.contains(
                    KEY_RESUME_CENTER_LAT
                ) &&
                resumePreferences.contains(
                    KEY_RESUME_CENTER_LON
                )
            ) {
                WaitingCenter(
                    centerId =
                        resumePreferences.getString(
                            KEY_RESUME_CENTER_ID,
                            ""
                        ) ?: "",
                    centerName =
                        resumePreferences.getString(
                            KEY_RESUME_CENTER_NAME,
                            "Waiting center"
                        ) ?: "Waiting center",
                    latitude =
                        Double.fromBits(
                            resumePreferences.getLong(
                                KEY_RESUME_CENTER_LAT,
                                0L
                            )
                        ),
                    longitude =
                        Double.fromBits(
                            resumePreferences.getLong(
                                KEY_RESUME_CENTER_LON,
                                0L
                            )
                        )
                )
            } else {
                null
            }

        val candidates = mutableListOf<WaitingCenter>()
        val candidatesText =
            resumePreferences.getString(
                KEY_RESUME_CANDIDATES_JSON,
                null
            )

        if (!candidatesText.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(candidatesText)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    candidates.add(
                        WaitingCenter(
                            centerId = item.getString("center_id"),
                            centerName = item.getString("center_name"),
                            latitude = item.getDouble("latitude"),
                            longitude = item.getDouble("longitude"),
                            restaurantCount = item.optInt(
                                "restaurant_count",
                                0
                            )
                        )
                    )
                }
            }.onFailure { exception ->
                Log.w(
                    LOG_TAG,
                    "Could not restore waiting-center candidates.",
                    exception
                )
            }
        }

        if (
            center != null &&
            candidates.none { candidate ->
                candidate.centerId == center.centerId
            }
        ) {
            candidates.add(center)
        }

        return ResumeState(
            center = center,
            recommendedCenterId =
                resumePreferences.getString(
                    KEY_RESUME_RECOMMENDED_CENTER_ID,
                    null
                ),
            wasArrived =
                resumePreferences.getBoolean(
                    KEY_RESUME_WAS_ARRIVED,
                    false
                ),
            hadFirstEntry =
                resumePreferences.getBoolean(
                    KEY_RESUME_HAD_FIRST_ENTRY,
                    false
                ),
            arrivalLatitude =
                readResumeDouble(
                    KEY_RESUME_ARRIVAL_LAT
                ),
            arrivalLongitude =
                readResumeDouble(
                    KEY_RESUME_ARRIVAL_LON
                ),
            candidates = candidates
        )
    }

    private fun clearResumeState() {
        resumePreferences.edit()
            .clear()
            .apply()
    }

    private fun readResumeDouble(
        key: String
    ): Double? {
        if (!resumePreferences.contains(key)) {
            return null
        }

        return Double.fromBits(
            resumePreferences.getLong(
                key,
                0L
            )
        )
    }

    private fun JSONObject.putOptionalDouble(
        key: String,
        value: Double?
    ) {
        put(
            key,
            value ?: JSONObject.NULL
        )
    }

    private fun restoreTrackingIfNeeded() {
        if (
            preferences.getBoolean(
                KEY_ACTIVE,
                false
            ) &&
            preferences.contains(
                KEY_CENTER_ID
            ) &&
            !preferences.contains(
                KEY_CENTER_ARRIVAL_AT
            )
        ) {
            startLocationUpdates()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (
            locationUpdatesRunning ||
            !hasLocationPermission()
        ) {
            return
        }

        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                MOVING_UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(
                    MOVING_MIN_UPDATE_INTERVAL_MS
                )
                .build()

        locationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        locationUpdatesRunning = true
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesRunning) {
            return
        }

        locationClient.removeLocationUpdates(
            locationCallback
        )

        locationUpdatesRunning = false
    }

    private fun processLocation(
        location: Location
    ) {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            stopLocationUpdates()
            return
        }

        if (
            preferences.contains(
                KEY_CENTER_ARRIVAL_AT
            )
        ) {
            stopLocationUpdates()
            return
        }

        maybeSwitchToActuallyReachedCenter(location)

        val centerLatitude =
            readDouble(
                KEY_CENTER_LAT
            ) ?: return

        val centerLongitude =
            readDouble(
                KEY_CENTER_LON
            ) ?: return

        val distanceMeters =
            FloatArray(1).also { result ->
                Location.distanceBetween(
                    location.latitude,
                    location.longitude,
                    centerLatitude,
                    centerLongitude,
                    result
                )
            }[0]

        val now =
            System.currentTimeMillis()

        if (distanceMeters <= ARRIVAL_RADIUS_METERS) {
            val firstEntryAt =
                if (
                    preferences.contains(
                        KEY_CENTER_FIRST_ENTRY_AT
                    )
                ) {
                    preferences.getLong(
                        KEY_CENTER_FIRST_ENTRY_AT,
                        now
                    )
                } else {
                    preferences.edit()
                        .putLong(
                            KEY_CENTER_FIRST_ENTRY_AT,
                            now
                        )
                        .apply()

                    now
                }

            if (
                now - firstEntryAt >=
                ARRIVAL_CONFIRMATION_MS
            ) {
                // Use the first entry timestamp as arrival so the confirmation
                // delay does not inflate the historical wait.
                preferences.edit()
                    .putLong(
                        KEY_CENTER_ARRIVAL_AT,
                        firstEntryAt
                    )
                    .putLong(
                        KEY_ARRIVAL_LAT,
                        location.latitude.toBits()
                    )
                    .putLong(
                        KEY_ARRIVAL_LON,
                        location.longitude.toBits()
                    )
                    .apply()

                uploadGpsConfirmedCenter(
                    latitude = location.latitude,
                    longitude = location.longitude
                )

                Log.d(
                    LOG_TAG,
                    "Confirmed waiting-center arrival; " +
                            "distance=$distanceMeters m."
                )

                stopLocationUpdates()
            }
        } else if (
            preferences.contains(
                KEY_CENTER_FIRST_ENTRY_AT
            )
        ) {
            preferences.edit()
                .remove(
                    KEY_CENTER_FIRST_ENTRY_AT
                )
                .apply()
        }
    }

    private fun maybeSwitchToActuallyReachedCenter(
        location: Location
    ) {
        if (candidateCenters.isEmpty()) {
            return
        }

        val nearest = candidateCenters
            .map { center ->
                val result = FloatArray(1)
                Location.distanceBetween(
                    location.latitude,
                    location.longitude,
                    center.latitude,
                    center.longitude,
                    result
                )
                center to result[0]
            }
            .minByOrNull { pair -> pair.second }
            ?: return

        if (nearest.second > ARRIVAL_RADIUS_METERS) {
            return
        }

        val currentCenterId = preferences.getString(
            KEY_CENTER_ID,
            null
        )

        if (nearest.first.centerId == currentCenterId) {
            return
        }

        val recommendedCenterId = preferences.getString(
            KEY_RECOMMENDED_CENTER_ID,
            null
        )

        preferences.edit()
            .putString(KEY_CENTER_ID, nearest.first.centerId)
            .putString(KEY_CENTER_NAME, nearest.first.centerName)
            .putLong(KEY_CENTER_LAT, nearest.first.latitude.toBits())
            .putLong(KEY_CENTER_LON, nearest.first.longitude.toBits())
            .remove(KEY_CENTER_FIRST_ENTRY_AT)
            .remove(KEY_CENTER_ARRIVAL_AT)
            .remove(KEY_ARRIVAL_LAT)
            .remove(KEY_ARRIVAL_LON)
            .apply()

        if (recommendedCenterId != null) {
            preferences.edit()
                .putString(KEY_RECOMMENDED_CENTER_ID, recommendedCenterId)
                .apply()
        }

        Log.d(
            LOG_TAG,
            "Driver reached alternative center ${nearest.first.centerId}; " +
                    "wait will be attributed there."
        )
    }

    private fun uploadGpsConfirmedCenter(
        latitude: Double,
        longitude: Double
    ) {
        val centerId = preferences.getString(
            KEY_CENTER_ID,
            null
        ) ?: return

        val centerName = preferences.getString(
            KEY_CENTER_NAME,
            null
        ) ?: "Center-${centerId.takeLast(6)}"

        val payload = JSONObject().apply {
            put("center_id", centerId)
            put("center_name", centerName)
            put("latitude", latitude)
            put("longitude", longitude)
            put("source", "GPS_AT_CENTER")
            put("last_seen_at_wall_time_ms", System.currentTimeMillis())
        }

        Thread {
            WaitingDataUploadManager.enqueueAndTryUpload(
                context = appContext,
                endpoint = "/waiting-centers",
                payload = payload
            )
        }.start()
    }

    private fun readDouble(
        key: String
    ): Double? {
        if (!preferences.contains(key)) {
            return null
        }

        return Double.fromBits(
            preferences.getLong(
                key,
                0L
            )
        )
    }
}