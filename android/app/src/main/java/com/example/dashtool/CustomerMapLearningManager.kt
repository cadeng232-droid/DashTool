package com.example.dashtool

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persistent customer-map learning path.
 *
 * Responsibilities:
 *  1. Store the latest raw customer-map prediction and its geometry/quality data.
 *  2. Associate that prediction with the offer ID once OverlayService saves it.
 *  3. Capture a provisional high-accuracy GPS fix at AT_CUSTOMER.
 *  4. Discard the label if AT_CUSTOMER rolls back to navigation.
 *  5. Upload only after a genuine completion confirms the arrival.
 *  6. Fetch the server's learned residual calibration and retain both raw and
 *     corrected customer coordinates.
 *  7. Persist failed uploads in app-private storage for retry on next service start.
 */
object CustomerMapLearningManager {

    private const val LOG_TAG =
        "DashToolCustomerLearn"

    private const val PREFS_NAME =
        "dash_tool_customer_map_learning"

    private const val KEY_LATEST_PREDICTION_JSON =
        "latest_prediction_json"

    private const val KEY_ACTIVE_OFFER_ID =
        "active_offer_id"

    private const val KEY_ACTIVE_RESTAURANT_PLACE_ID =
        "active_restaurant_place_id"

    private const val KEY_ACTIVE_OFFER_DETECTED_AT_MS =
        "active_offer_detected_at_ms"

    private const val KEY_PROVISIONAL_ACTUAL_JSON =
        "provisional_actual_json"

    private const val KEY_CONFIRMATION_JSON =
        "confirmation_json"

    private const val MAX_PREDICTION_ASSOCIATION_AGE_MS =
        2L * 60L * 1_000L

    private const val MAX_PREDICTION_TO_CUSTOMER_AGE_MS =
        4L * 60L * 60L * 1_000L

    private const val CONNECT_TIMEOUT_MS =
        4_000

    private const val READ_TIMEOUT_MS =
        5_000

    private const val GOOD_ACTUAL_ACCURACY_METERS =
        35.0

    private const val MAX_LOCAL_ACTUAL_ACCURACY_METERS =
        100.0

    private const val MAX_LOCAL_DRIVER_ACCURACY_METERS =
        100.0

    private const val MAX_LOCAL_DRIVER_ANCHOR_AGE_MS =
        15_000L

    private const val MIN_LOCAL_ANCHOR_PIXELS =
        60.0

    private const val MAX_LOCAL_EXTRAPOLATION_RATIO =
        5.0

    private const val QUEUE_DIRECTORY =
        "customer_map_learning_queue"

    data class Prediction(
        val predictionId: String,
        val predictedAtWallTimeMs: Long,
        val screenshotCapturedAtWallTimeMs: Long,
        val restaurantPlaceId: String,
        val rawLatitude: Double,
        val rawLongitude: Double,
        val correctedLatitude: Double,
        val correctedLongitude: Double,
        val calibrationSampleCount: Int,
        val calibrationConfidence: Double,
        val calibrationModelType: String,
        val correctionEastMeters: Double,
        val correctionNorthMeters: Double,
        val driverLatitude: Double,
        val driverLongitude: Double,
        val driverAccuracyMeters: Double,
        val driverLocationWallTimeMs: Long,
        val driverAnchorAgeMs: Long,
        val restaurantLatitude: Double,
        val restaurantLongitude: Double,
        val driverPixelX: Int,
        val driverPixelY: Int,
        val restaurantPixelX: Int,
        val restaurantPixelY: Int,
        val customerPixelX: Int,
        val customerPixelY: Int,
        val restaurantWhiteDensity: Double?,
        val restaurantHouseScore: Double?,
        val customerWhiteDensity: Double?,
        val customerHouseScore: Double?,
        val calibrationPixelDistance: Double,
        val customerPixelDistance: Double,
        val extrapolationRatio: Double,
        val anchorStraightLineMeters: Double,
        val approximateMetersPerPixel: Double,
        val associatedOfferId: String? = null
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("prediction_id", predictionId)
                put("predicted_at_wall_time_ms", predictedAtWallTimeMs)
                put("screenshot_captured_at_wall_time_ms", screenshotCapturedAtWallTimeMs)
                put("restaurant_place_id", restaurantPlaceId)
                put("raw_latitude", rawLatitude)
                put("raw_longitude", rawLongitude)
                put("corrected_latitude", correctedLatitude)
                put("corrected_longitude", correctedLongitude)
                put("calibration_sample_count", calibrationSampleCount)
                put("calibration_confidence", calibrationConfidence)
                put("calibration_model_type", calibrationModelType)
                put("correction_east_meters", correctionEastMeters)
                put("correction_north_meters", correctionNorthMeters)
                put("driver_latitude", driverLatitude)
                put("driver_longitude", driverLongitude)
                put("driver_accuracy_meters", driverAccuracyMeters)
                put("driver_location_wall_time_ms", driverLocationWallTimeMs)
                put("driver_anchor_age_ms", driverAnchorAgeMs)
                put("restaurant_latitude", restaurantLatitude)
                put("restaurant_longitude", restaurantLongitude)
                put("driver_pixel_x", driverPixelX)
                put("driver_pixel_y", driverPixelY)
                put("restaurant_pixel_x", restaurantPixelX)
                put("restaurant_pixel_y", restaurantPixelY)
                put("customer_pixel_x", customerPixelX)
                put("customer_pixel_y", customerPixelY)
                put(
                    "restaurant_white_density",
                    restaurantWhiteDensity ?: JSONObject.NULL
                )
                put(
                    "restaurant_house_score",
                    restaurantHouseScore ?: JSONObject.NULL
                )
                put(
                    "customer_white_density",
                    customerWhiteDensity ?: JSONObject.NULL
                )
                put(
                    "customer_house_score",
                    customerHouseScore ?: JSONObject.NULL
                )
                put("calibration_pixel_distance", calibrationPixelDistance)
                put("customer_pixel_distance", customerPixelDistance)
                put("extrapolation_ratio", extrapolationRatio)
                put("anchor_straight_line_meters", anchorStraightLineMeters)
                put("approximate_meters_per_pixel", approximateMetersPerPixel)
                if (!associatedOfferId.isNullOrBlank()) {
                    put("associated_offer_id", associatedOfferId)
                }
            }
        }
    }

    private data class ActualArrival(
        val offerId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val locationWallTimeMs: Long,
        val requestedAtWallTimeMs: Long,
        val source: String
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("offer_id", offerId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy_meters", accuracyMeters)
                put("location_wall_time_ms", locationWallTimeMs)
                put("requested_at_wall_time_ms", requestedAtWallTimeMs)
                put("source", source)
            }
        }
    }

    private data class Confirmation(
        val offerId: String,
        val restaurantPlaceId: String?,
        val confirmedAtWallTimeMs: Long,
        val source: String,
        val learnable: Boolean
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("offer_id", offerId)
                put("confirmed_at_wall_time_ms", confirmedAtWallTimeMs)
                put("source", source)
                put("learnable", learnable)
                if (!restaurantPlaceId.isNullOrBlank()) {
                    put("restaurant_place_id", restaurantPlaceId)
                }
            }
        }
    }

    fun recordRawPrediction(
        context: Context,
        prediction: Prediction
    ) {
        val appContext =
            context.applicationContext

        val associated =
            associateWithActiveOfferIfPossible(
                context = appContext,
                prediction = prediction
            )

        savePrediction(
            context = appContext,
            prediction = associated
        )

        Log.d(
            LOG_TAG,
            "Stored raw customer prediction ${associated.predictionId}; " +
                    "offer=${associated.associatedOfferId ?: "unassigned"}."
        )

        Thread {
            val calibrated =
                fetchCalibration(
                    context = appContext,
                    prediction = associated
                )

            if (calibrated != null) {
                updatePredictionIfStillCurrent(
                    context = appContext,
                    calibratedPrediction = calibrated
                )

                Log.d(
                    LOG_TAG,
                    String.format(
                        java.util.Locale.US,
                        "CALIBRATED raw=(%.7f,%.7f) corrected=(%.7f,%.7f) " +
                                "delta=(%.1fE,%.1fN)m samples=%d confidence=%.2f model=%s",
                        calibrated.rawLatitude,
                        calibrated.rawLongitude,
                        calibrated.correctedLatitude,
                        calibrated.correctedLongitude,
                        calibrated.correctionEastMeters,
                        calibrated.correctionNorthMeters,
                        calibrated.calibrationSampleCount,
                        calibrated.calibrationConfidence,
                        calibrated.calibrationModelType
                    )
                )
            }
        }.start()
    }

    /**
     * Called after OverlayService creates the persistent offer ID. This handles
     * either race ordering: prediction may already exist, or it may arrive later.
     */
    fun onOfferSaved(
        context: Context,
        offerId: String,
        restaurantPlaceId: String?,
        detectedAtWallTimeMs: Long
    ) {
        val preferences =
            preferences(context)

        preferences.edit()
            .putString(
                KEY_ACTIVE_OFFER_ID,
                offerId
            )
            .putLong(
                KEY_ACTIVE_OFFER_DETECTED_AT_MS,
                detectedAtWallTimeMs
            )
            .apply {
                if (!restaurantPlaceId.isNullOrBlank()) {
                    putString(
                        KEY_ACTIVE_RESTAURANT_PLACE_ID,
                        restaurantPlaceId
                    )
                } else {
                    remove(
                        KEY_ACTIVE_RESTAURANT_PLACE_ID
                    )
                }
            }
            .apply()

        val latest =
            loadPrediction(context)
                ?: return

        val associated =
            associateWithActiveOfferIfPossible(
                context = context,
                prediction = latest
            )

        if (
            associated.associatedOfferId !=
            latest.associatedOfferId
        ) {
            savePrediction(
                context = context,
                prediction = associated
            )
        }
    }

    fun onProvisionalCustomerArrival(
        context: Context,
        offerId: String,
        requestedAtWallTimeMs: Long
    ) {
        val existing =
            loadActualArrival(context)

        if (
            existing?.offerId ==
            offerId
        ) {
            return
        }

        captureLocation(
            context = context,
            requestedAtWallTimeMs =
                requestedAtWallTimeMs
        ) { location, source ->
            if (location == null) {
                Log.w(
                    LOG_TAG,
                    "Could not capture provisional customer GPS for $offerId."
                )
                return@captureLocation
            }

            val actual =
                ActualArrival(
                    offerId = offerId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters =
                        location.accuracy.toDouble(),
                    locationWallTimeMs =
                        location.time,
                    requestedAtWallTimeMs =
                        requestedAtWallTimeMs,
                    source = source
                )

            saveActualArrival(
                context = context,
                actual = actual
            )

            Log.d(
                LOG_TAG,
                String.format(
                    java.util.Locale.US,
                    "PROVISIONAL_ACTUAL offer=%s gps=(%.7f,%.7f) accuracy=%.1fm source=%s",
                    offerId,
                    actual.latitude,
                    actual.longitude,
                    actual.accuracyMeters,
                    source
                )
            )

            maybeFinishPendingConfirmation(
                context = context,
                offerId = offerId
            )
        }
    }

    fun rollbackProvisionalCustomerArrival(
        context: Context,
        offerId: String
    ) {
        val actual =
            loadActualArrival(context)

        if (
            actual?.offerId ==
            offerId
        ) {
            preferences(context)
                .edit()
                .remove(
                    KEY_PROVISIONAL_ACTUAL_JSON
                )
                .remove(
                    KEY_CONFIRMATION_JSON
                )
                .apply()

            Log.d(
                LOG_TAG,
                "Discarded provisional customer GPS after lifecycle rollback for $offerId."
            )
        }
    }

    fun confirmDelivery(
        context: Context,
        offerId: String,
        restaurantPlaceId: String?,
        confirmedAtWallTimeMs: Long,
        confirmationSource: String,
        learnable: Boolean
    ) {
        val confirmation =
            Confirmation(
                offerId = offerId,
                restaurantPlaceId =
                    restaurantPlaceId,
                confirmedAtWallTimeMs =
                    confirmedAtWallTimeMs,
                source = confirmationSource,
                learnable = learnable
            )

        preferences(context)
            .edit()
            .putString(
                KEY_CONFIRMATION_JSON,
                confirmation.toJson()
                    .toString()
            )
            .apply()

        val actual =
            loadActualArrival(context)

        if (
            actual == null ||
            actual.offerId != offerId
        ) {
            /*
             * Rare fallback: lifecycle completion happened before the provisional
             * location callback returned. Capture a completion-time fix so the raw
             * sample is not lost. The server can still exclude it if quality is poor.
             */
            captureLocation(
                context = context,
                requestedAtWallTimeMs =
                    confirmedAtWallTimeMs
            ) { location, source ->
                if (location != null) {
                    saveActualArrival(
                        context = context,
                        actual =
                            ActualArrival(
                                offerId = offerId,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracyMeters =
                                    location.accuracy.toDouble(),
                                locationWallTimeMs =
                                    location.time,
                                requestedAtWallTimeMs =
                                    confirmedAtWallTimeMs,
                                source =
                                    "COMPLETION_$source"
                            )
                    )
                }

                maybeFinishPendingConfirmation(
                    context = context,
                    offerId = offerId
                )
            }

            return
        }

        /*
         * If the first arrival fix is weak, take one more fix at completion and
         * keep whichever is more accurate, provided it belongs to the same stop.
         */
        if (
            actual.accuracyMeters >
            GOOD_ACTUAL_ACCURACY_METERS
        ) {
            captureLocation(
                context = context,
                requestedAtWallTimeMs =
                    confirmedAtWallTimeMs
            ) { completionLocation, source ->
                if (
                    completionLocation != null &&
                    completionLocation.accuracy.toDouble() <
                    actual.accuracyMeters
                ) {
                    val candidate =
                        ActualArrival(
                            offerId = offerId,
                            latitude = completionLocation.latitude,
                            longitude = completionLocation.longitude,
                            accuracyMeters =
                                completionLocation.accuracy.toDouble(),
                            locationWallTimeMs =
                                completionLocation.time,
                            requestedAtWallTimeMs =
                                confirmedAtWallTimeMs,
                            source =
                                "COMPLETION_$source"
                        )

                    /*
                     * Do not replace the true-arrival fix if the phone has clearly
                     * moved away from the stop by completion time.
                     */
                    if (
                        distanceMeters(
                            actual.latitude,
                            actual.longitude,
                            candidate.latitude,
                            candidate.longitude
                        ) <= 150.0
                    ) {
                        saveActualArrival(
                            context = context,
                            actual = candidate
                        )
                    }
                }

                maybeFinishPendingConfirmation(
                    context = context,
                    offerId = offerId
                )
            }
        } else {
            maybeFinishPendingConfirmation(
                context = context,
                offerId = offerId
            )
        }
    }

    fun resumePendingConfirmation(
        context: Context
    ) {
        val confirmation =
            loadConfirmation(context)
                ?: return

        val actual =
            loadActualArrival(context)
                ?: return

        if (
            confirmation.offerId ==
            actual.offerId
        ) {
            maybeFinishPendingConfirmation(
                context = context,
                offerId = confirmation.offerId
            )
        }
    }

    fun retryPending(
        context: Context
    ) {
        val appContext =
            context.applicationContext

        Thread {
            val directory =
                queueDirectory(appContext)

            val files =
                directory.listFiles()
                    ?.sortedBy {
                        it.lastModified()
                    }
                    .orEmpty()

            var uploaded =
                0

            for (file in files) {
                val payload =
                    runCatching {
                        JSONObject(
                            file.readText()
                        )
                    }.getOrNull()

                if (payload == null) {
                    file.delete()
                    continue
                }

                if (
                    postJson(
                        endpoint =
                            "/customer-map-samples",
                        payload = payload
                    )
                ) {
                    file.delete()
                    uploaded += 1
                }
            }

            if (
                uploaded > 0 ||
                files.isNotEmpty()
            ) {
                Log.d(
                    LOG_TAG,
                    "Customer-map queue retry: $uploaded uploaded, " +
                            "${directory.listFiles()?.size ?: 0} remaining."
                )
            }
        }.start()
    }

    private fun maybeFinishPendingConfirmation(
        context: Context,
        offerId: String
    ) {
        val confirmation =
            loadConfirmation(context)
                ?: return

        if (
            confirmation.offerId !=
            offerId
        ) {
            return
        }

        val actual =
            loadActualArrival(context)
                ?: return

        if (
            actual.offerId !=
            offerId
        ) {
            return
        }

        val prediction =
            loadPrediction(context)

        if (
            prediction == null ||
            prediction.associatedOfferId !=
            offerId
        ) {
            Log.w(
                LOG_TAG,
                "No matching customer-map prediction for confirmed offer $offerId; sample skipped."
            )
            clearCompletedState(
                context = context,
                offerId = offerId
            )
            return
        }

        val age =
            actual.requestedAtWallTimeMs -
                    prediction.predictedAtWallTimeMs

        val localExclusionReasons =
            mutableListOf<String>()

        if (
            !confirmation.learnable
        ) {
            localExclusionReasons.add(
                "LOW_CONFIDENCE_COMPLETION"
            )
        }

        if (
            age < 0L ||
            age > MAX_PREDICTION_TO_CUSTOMER_AGE_MS
        ) {
            localExclusionReasons.add(
                "PREDICTION_AGE_OUT_OF_RANGE"
            )
        }

        if (
            actual.accuracyMeters >
            MAX_LOCAL_ACTUAL_ACCURACY_METERS
        ) {
            localExclusionReasons.add(
                "ACTUAL_GPS_LOW_ACCURACY"
            )
        }

        if (
            prediction.driverAccuracyMeters >
            MAX_LOCAL_DRIVER_ACCURACY_METERS
        ) {
            localExclusionReasons.add(
                "DRIVER_GPS_LOW_ACCURACY"
            )
        }

        if (
            abs(
                prediction.driverAnchorAgeMs
            ) > MAX_LOCAL_DRIVER_ANCHOR_AGE_MS
        ) {
            localExclusionReasons.add(
                "DRIVER_GPS_TOO_FAR_FROM_SCREENSHOT"
            )
        }

        if (
            prediction.calibrationPixelDistance <
            MIN_LOCAL_ANCHOR_PIXELS
        ) {
            localExclusionReasons.add(
                "CALIBRATION_ANCHOR_TOO_SHORT"
            )
        }

        if (
            prediction.extrapolationRatio >
            MAX_LOCAL_EXTRAPOLATION_RATIO
        ) {
            localExclusionReasons.add(
                "EXTREME_EXTRAPOLATION"
            )
        }

        val sampleId =
            "customer_map_" +
                    UUID.randomUUID()
                        .toString()

        val payload =
            prediction.toJson()
                .apply {
                    put("sample_id", sampleId)
                    put("offer_id", offerId)
                    put("actual_latitude", actual.latitude)
                    put("actual_longitude", actual.longitude)
                    put("actual_accuracy_meters", actual.accuracyMeters)
                    put("actual_location_wall_time_ms", actual.locationWallTimeMs)
                    put("actual_requested_at_wall_time_ms", actual.requestedAtWallTimeMs)
                    put("actual_location_source", actual.source)
                    put("delivery_confirmed_at_wall_time_ms", confirmation.confirmedAtWallTimeMs)
                    put("confirmation_source", confirmation.source)
                    put("exclude_from_learning", localExclusionReasons.isNotEmpty())
                    if (localExclusionReasons.isNotEmpty()) {
                        put(
                            "exclusion_reason",
                            localExclusionReasons.joinToString(
                                separator = ","
                            )
                        )
                    }
                }

        /*
         * Persist first, then upload. A process death between these operations
         * therefore cannot erase the confirmed training label.
         */
        val queuedFile =
            enqueuePayload(
                context = context,
                payload = payload
            )

        Thread {
            val uploaded =
                postJson(
                    endpoint =
                        "/customer-map-samples",
                    payload = payload
                )

            if (uploaded) {
                queuedFile?.delete()
            }

            Log.d(
                LOG_TAG,
                "Customer-map training sample $sampleId " +
                        if (uploaded) {
                            "uploaded."
                        } else {
                            "queued for retry."
                        }
            )
        }.start()

        clearCompletedState(
            context = context,
            offerId = offerId
        )
    }

    private fun clearCompletedState(
        context: Context,
        offerId: String
    ) {
        val preferences =
            preferences(context)

        val editor =
            preferences.edit()
                .remove(
                    KEY_PROVISIONAL_ACTUAL_JSON
                )
                .remove(
                    KEY_CONFIRMATION_JSON
                )

        if (
            preferences.getString(
                KEY_ACTIVE_OFFER_ID,
                null
            ) == offerId
        ) {
            editor
                .remove(
                    KEY_ACTIVE_OFFER_ID
                )
                .remove(
                    KEY_ACTIVE_RESTAURANT_PLACE_ID
                )
                .remove(
                    KEY_ACTIVE_OFFER_DETECTED_AT_MS
                )
        }

        editor.apply()
    }

    private fun associateWithActiveOfferIfPossible(
        context: Context,
        prediction: Prediction
    ): Prediction {
        val preferences =
            preferences(context)

        val activeOfferId =
            preferences.getString(
                KEY_ACTIVE_OFFER_ID,
                null
            )
                ?: return prediction

        val activeRestaurantPlaceId =
            preferences.getString(
                KEY_ACTIVE_RESTAURANT_PLACE_ID,
                null
            )

        val detectedAt =
            preferences.getLong(
                KEY_ACTIVE_OFFER_DETECTED_AT_MS,
                0L
            )

        if (
            activeRestaurantPlaceId.isNullOrBlank() ||
            activeRestaurantPlaceId !=
            prediction.restaurantPlaceId
        ) {
            return prediction
        }

        val predictionDelayMs =
            prediction.predictedAtWallTimeMs -
                    detectedAt

        if (
            detectedAt <= 0L ||
            predictionDelayMs < -10_000L ||
            predictionDelayMs >
            MAX_PREDICTION_ASSOCIATION_AGE_MS
        ) {
            return prediction
        }

        return prediction.copy(
            associatedOfferId =
                activeOfferId
        )
    }

    private fun fetchCalibration(
        context: Context,
        prediction: Prediction
    ): Prediction? {
        val endpoint =
            buildString {
                append(
                    "/customer-map/calibration?raw_latitude="
                )
                append(prediction.rawLatitude)
                append("&raw_longitude=")
                append(prediction.rawLongitude)
                append("&driver_latitude=")
                append(prediction.driverLatitude)
                append("&driver_longitude=")
                append(prediction.driverLongitude)
                append("&restaurant_place_id=")
                append(
                    URLEncoder.encode(
                        prediction.restaurantPlaceId,
                        "UTF-8"
                    )
                )
            }

        val response =
            getJson(endpoint)
                ?: return null

        return prediction.copy(
            correctedLatitude =
                response.optDouble(
                    "corrected_latitude",
                    prediction.rawLatitude
                ),
            correctedLongitude =
                response.optDouble(
                    "corrected_longitude",
                    prediction.rawLongitude
                ),
            calibrationSampleCount =
                response.optInt(
                    "sample_count",
                    0
                ),
            calibrationConfidence =
                response.optDouble(
                    "confidence",
                    0.0
                ),
            calibrationModelType =
                response.optString(
                    "model_type",
                    "RAW_ONLY"
                ),
            correctionEastMeters =
                response.optDouble(
                    "correction_east_meters",
                    0.0
                ),
            correctionNorthMeters =
                response.optDouble(
                    "correction_north_meters",
                    0.0
                )
        )
    }

    private fun updatePredictionIfStillCurrent(
        context: Context,
        calibratedPrediction: Prediction
    ) {
        val current =
            loadPrediction(context)
                ?: return

        if (
            current.predictionId !=
            calibratedPrediction.predictionId
        ) {
            return
        }

        val associated =
            associateWithActiveOfferIfPossible(
                context = context,
                prediction = calibratedPrediction
            )

        savePrediction(
            context = context,
            prediction = associated
        )
    }

    private fun savePrediction(
        context: Context,
        prediction: Prediction
    ) {
        preferences(context)
            .edit()
            .putString(
                KEY_LATEST_PREDICTION_JSON,
                prediction.toJson()
                    .toString()
            )
            .apply()
    }


    /**
     * Read-only access for on-device diagnostics such as customer-address
     * validation. The raw address itself is never stored here.
     */
    fun latestPredictionForOffer(
        context: Context,
        offerId: String
    ): Prediction? {
        return loadPrediction(
            context
        )?.takeIf {
                prediction ->

            prediction.associatedOfferId ==
                    offerId
        }
    }

    private fun loadPrediction(
        context: Context
    ): Prediction? {
        val raw =
            preferences(context)
                .getString(
                    KEY_LATEST_PREDICTION_JSON,
                    null
                )
                ?: return null

        return runCatching {
            val json =
                JSONObject(raw)

            Prediction(
                predictionId =
                    json.getString("prediction_id"),
                predictedAtWallTimeMs =
                    json.getLong("predicted_at_wall_time_ms"),
                screenshotCapturedAtWallTimeMs =
                    json.getLong("screenshot_captured_at_wall_time_ms"),
                restaurantPlaceId =
                    json.getString("restaurant_place_id"),
                rawLatitude =
                    json.getDouble("raw_latitude"),
                rawLongitude =
                    json.getDouble("raw_longitude"),
                correctedLatitude =
                    json.optDouble(
                        "corrected_latitude",
                        json.getDouble("raw_latitude")
                    ),
                correctedLongitude =
                    json.optDouble(
                        "corrected_longitude",
                        json.getDouble("raw_longitude")
                    ),
                calibrationSampleCount =
                    json.optInt(
                        "calibration_sample_count",
                        0
                    ),
                calibrationConfidence =
                    json.optDouble(
                        "calibration_confidence",
                        0.0
                    ),
                calibrationModelType =
                    json.optString(
                        "calibration_model_type",
                        "RAW_ONLY"
                    ),
                correctionEastMeters =
                    json.optDouble(
                        "correction_east_meters",
                        0.0
                    ),
                correctionNorthMeters =
                    json.optDouble(
                        "correction_north_meters",
                        0.0
                    ),
                driverLatitude =
                    json.getDouble("driver_latitude"),
                driverLongitude =
                    json.getDouble("driver_longitude"),
                driverAccuracyMeters =
                    json.getDouble("driver_accuracy_meters"),
                driverLocationWallTimeMs =
                    json.getLong("driver_location_wall_time_ms"),
                driverAnchorAgeMs =
                    json.getLong("driver_anchor_age_ms"),
                restaurantLatitude =
                    json.getDouble("restaurant_latitude"),
                restaurantLongitude =
                    json.getDouble("restaurant_longitude"),
                driverPixelX =
                    json.getInt("driver_pixel_x"),
                driverPixelY =
                    json.getInt("driver_pixel_y"),
                restaurantPixelX =
                    json.getInt("restaurant_pixel_x"),
                restaurantPixelY =
                    json.getInt("restaurant_pixel_y"),
                customerPixelX =
                    json.getInt("customer_pixel_x"),
                customerPixelY =
                    json.getInt("customer_pixel_y"),
                restaurantWhiteDensity =
                    json.optNullableDouble(
                        "restaurant_white_density"
                    ),
                restaurantHouseScore =
                    json.optNullableDouble(
                        "restaurant_house_score"
                    ),
                customerWhiteDensity =
                    json.optNullableDouble(
                        "customer_white_density"
                    ),
                customerHouseScore =
                    json.optNullableDouble(
                        "customer_house_score"
                    ),
                calibrationPixelDistance =
                    json.getDouble("calibration_pixel_distance"),
                customerPixelDistance =
                    json.getDouble("customer_pixel_distance"),
                extrapolationRatio =
                    json.getDouble("extrapolation_ratio"),
                anchorStraightLineMeters =
                    json.getDouble("anchor_straight_line_meters"),
                approximateMetersPerPixel =
                    json.getDouble("approximate_meters_per_pixel"),
                associatedOfferId =
                    json.optString(
                        "associated_offer_id"
                    ).takeIf {
                        it.isNotBlank()
                    }
            )
        }.getOrNull()
    }

    private fun saveActualArrival(
        context: Context,
        actual: ActualArrival
    ) {
        preferences(context)
            .edit()
            .putString(
                KEY_PROVISIONAL_ACTUAL_JSON,
                actual.toJson()
                    .toString()
            )
            .apply()
    }

    private fun loadActualArrival(
        context: Context
    ): ActualArrival? {
        val raw =
            preferences(context)
                .getString(
                    KEY_PROVISIONAL_ACTUAL_JSON,
                    null
                )
                ?: return null

        return runCatching {
            val json =
                JSONObject(raw)

            ActualArrival(
                offerId =
                    json.getString("offer_id"),
                latitude =
                    json.getDouble("latitude"),
                longitude =
                    json.getDouble("longitude"),
                accuracyMeters =
                    json.getDouble("accuracy_meters"),
                locationWallTimeMs =
                    json.getLong("location_wall_time_ms"),
                requestedAtWallTimeMs =
                    json.getLong("requested_at_wall_time_ms"),
                source =
                    json.optString(
                        "source",
                        "UNKNOWN"
                    )
            )
        }.getOrNull()
    }

    private fun loadConfirmation(
        context: Context
    ): Confirmation? {
        val raw =
            preferences(context)
                .getString(
                    KEY_CONFIRMATION_JSON,
                    null
                )
                ?: return null

        return runCatching {
            val json =
                JSONObject(raw)

            Confirmation(
                offerId =
                    json.getString("offer_id"),
                restaurantPlaceId =
                    json.optString(
                        "restaurant_place_id"
                    ).takeIf {
                        it.isNotBlank()
                    },
                confirmedAtWallTimeMs =
                    json.getLong("confirmed_at_wall_time_ms"),
                source =
                    json.optString(
                        "source",
                        "UNKNOWN"
                    ),
                learnable =
                    json.optBoolean(
                        "learnable",
                        false
                    )
            )
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun captureLocation(
        context: Context,
        requestedAtWallTimeMs: Long,
        onComplete: (Location?, String) -> Unit
    ) {
        if (!hasLocationPermission(context)) {
            onComplete(
                null,
                "NO_PERMISSION"
            )
            return
        }

        val client =
            LocationServices
                .getFusedLocationProviderClient(
                    context.applicationContext
                )

        val tokenSource =
            CancellationTokenSource()

        client.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            tokenSource.token
        )
            .addOnSuccessListener { location ->
                if (location != null) {
                    onComplete(
                        location,
                        "CURRENT_HIGH_ACCURACY"
                    )
                } else {
                    client.lastLocation
                        .addOnSuccessListener { last ->
                            onComplete(
                                last,
                                "LAST_LOCATION_FALLBACK"
                            )
                        }
                        .addOnFailureListener {
                            onComplete(
                                null,
                                "LAST_LOCATION_FAILED"
                            )
                        }
                }
            }
            .addOnFailureListener {
                client.lastLocation
                    .addOnSuccessListener { last ->
                        onComplete(
                            last,
                            "LAST_LOCATION_FALLBACK"
                        )
                    }
                    .addOnFailureListener {
                        onComplete(
                            null,
                            "LOCATION_FAILED"
                        )
                    }
            }
    }

    private fun hasLocationPermission(
        context: Context
    ): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun preferences(
        context: Context
    ) = context.applicationContext
        .getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private fun baseUrl(): String {
        return OrderUploadConfig.ORDERS_URL
            .trimEnd('/')
            .removeSuffix("/orders")
    }

    private fun postJson(
        endpoint: String,
        payload: JSONObject
    ): Boolean {
        var connection:
                HttpURLConnection? = null

        return try {
            val active =
                (
                        URL(
                            baseUrl() +
                                    endpoint
                        ).openConnection() as
                                HttpURLConnection
                        ).apply {
                        requestMethod =
                            "POST"
                        connectTimeout =
                            CONNECT_TIMEOUT_MS
                        readTimeout =
                            READ_TIMEOUT_MS
                        doOutput =
                            true
                        setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                        )
                        setRequestProperty(
                            "Accept",
                            "application/json"
                        )
                    }

            connection =
                active

            active.outputStream.use { output ->
                output.write(
                    payload.toString()
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
            }

            val code =
                active.responseCode

            code in 200..299
        } catch (
            exception: Exception
        ) {
            Log.w(
                LOG_TAG,
                "POST $endpoint failed: " +
                        (exception.message
                            ?: exception.javaClass.simpleName)
            )
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun getJson(
        endpoint: String
    ): JSONObject? {
        var connection:
                HttpURLConnection? = null

        return try {
            val active =
                (
                        URL(
                            baseUrl() +
                                    endpoint
                        ).openConnection() as
                                HttpURLConnection
                        ).apply {
                        requestMethod =
                            "GET"
                        connectTimeout =
                            CONNECT_TIMEOUT_MS
                        readTimeout =
                            READ_TIMEOUT_MS
                        doInput =
                            true
                    }

            connection =
                active

            val code =
                active.responseCode

            if (
                code !in 200..299
            ) {
                return null
            }

            val text =
                BufferedReader(
                    InputStreamReader(
                        active.inputStream
                    )
                ).use { reader ->
                    reader.readText()
                }

            JSONObject(text)
        } catch (
            exception: Exception
        ) {
            Log.w(
                LOG_TAG,
                "GET $endpoint failed: " +
                        (exception.message
                            ?: exception.javaClass.simpleName)
            )
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun enqueuePayload(
        context: Context,
        payload: JSONObject
    ): File? {
        return runCatching {
            val sampleId =
                payload.optString(
                    "sample_id",
                    UUID.randomUUID()
                        .toString()
                )

            val file =
                File(
                    queueDirectory(context),
                    "$sampleId.json"
                )

            file.writeText(
                payload.toString()
            )

            file
        }.onFailure { exception ->
            Log.e(
                LOG_TAG,
                "Could not persist customer-map sample queue item.",
                exception
            )
        }.getOrNull()
    }

    private fun queueDirectory(
        context: Context
    ): File {
        return File(
            context.applicationContext.filesDir,
            QUEUE_DIRECTORY
        ).apply {
            mkdirs()
        }
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val result =
            FloatArray(1)

        Location.distanceBetween(
            lat1,
            lon1,
            lat2,
            lon2,
            result
        )

        return result[0]
            .toDouble()
    }

    private fun JSONObject.putNullableDouble(
        key: String,
        value: Double?
    ) {
        if (value == null) {
            put(
                key,
                JSONObject.NULL
            )
        } else {
            put(
                key,
                value
            )
        }
    }

    private fun JSONObject.optNullableDouble(
        key: String
    ): Double? {
        if (
            !has(key) ||
            isNull(key)
        ) {
            return null
        }

        return optDouble(
            key
        ).takeIf {
            it.isFinite()
        }
    }
}