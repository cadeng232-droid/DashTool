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
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Customer-coordinate estimator with screenshot-time GPS anchoring.
 *
 * The important change from the original proof-of-concept is that the phone
 * location request starts when the map markers are detected, rather than after
 * OCR + restaurant matching + Place Details complete. That makes the driver GPS
 * anchor correspond much more closely to the driver dot in the screenshot.
 *
 * Both the raw geometry estimate and the server-calibrated estimate are retained.
 * Learning is handled by CustomerMapLearningManager after a real delivery reaches
 * and confirms AT_CUSTOMER.
 */
class CustomerMapCoordinateEstimator(
    context: Context
) {

    companion object {
        private const val LOG_TAG =
            "DashToolCustomerMap"

        private const val PREFS_NAME =
            "dash_tool_customer_map_experiment"

        const val KEY_PREDICTED_CUSTOMER_LAT =
            "predicted_customer_lat"

        const val KEY_PREDICTED_CUSTOMER_LON =
            "predicted_customer_lon"

        const val KEY_PREDICTED_AT_MS =
            "predicted_at_ms"

        const val KEY_RESTAURANT_PLACE_ID =
            "restaurant_place_id"

        private const val MAX_SCREENSHOT_ANCHOR_AGE_MS =
            15_000L

        private const val MAX_SCREENSHOT_ANCHOR_ACCURACY_METERS =
            100.0
    }

    private data class DriverAnchorCandidate(
        val screenshotCapturedAtWallTimeMs: Long,
        val location: Location,
        val source: String
    )

    private val appContext =
        context.applicationContext

    private val locationClient =
        LocationServices
            .getFusedLocationProviderClient(
                appContext
            )

    private val placesClient =
        Places.createClient(
            appContext
        )

    private val anchorLock =
        Any()

    private var bestDriverAnchor:
            DriverAnchorCandidate? = null

    /**
     * Call immediately after the offer-map screenshot markers are found.
     *
     * lastLocation often represents a fix from just before the screenshot while
     * getCurrentLocation supplies a fix just after it. We keep whichever fix is
     * temporally closest after also penalizing weak GPS accuracy.
     */
    @SuppressLint("MissingPermission")
    fun captureDriverAnchorNearScreenshot(
        screenshotCapturedAtWallTimeMs: Long
    ) {
        if (!hasLocationPermission()) {
            return
        }

        synchronized(anchorLock) {
            bestDriverAnchor =
                null
        }

        locationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    considerDriverAnchor(
                        screenshotCapturedAtWallTimeMs =
                            screenshotCapturedAtWallTimeMs,
                        location = location,
                        source =
                            "LAST_LOCATION_NEAR_SCREENSHOT"
                    )
                }
            }

        val tokenSource =
            CancellationTokenSource()

        locationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            tokenSource.token
        )
            .addOnSuccessListener { location ->
                if (location != null) {
                    considerDriverAnchor(
                        screenshotCapturedAtWallTimeMs =
                            screenshotCapturedAtWallTimeMs,
                        location = location,
                        source =
                            "CURRENT_LOCATION_NEAR_SCREENSHOT"
                    )
                }
            }
            .addOnFailureListener { exception ->
                Log.w(
                    LOG_TAG,
                    "Screenshot-time current-location request failed: " +
                            (exception.message
                                ?: exception.javaClass.simpleName)
                )
            }
    }

    fun estimate(
        markers: DoorDashOfferMapLocator.Result,
        restaurantPlaceId: String,
        screenshotCapturedAtWallTimeMs: Long
    ) {
        val driverPixel =
            markers.driver

        val restaurantPixel =
            markers.restaurant

        val customerPixel =
            markers.customer

        if (
            driverPixel == null ||
            restaurantPixel == null ||
            customerPixel == null
        ) {
            Log.d(
                LOG_TAG,
                "Coordinate estimate skipped: all three map markers are required."
            )
            return
        }

        if (restaurantPlaceId.isBlank()) {
            Log.d(
                LOG_TAG,
                "Coordinate estimate skipped: restaurant Place ID was blank."
            )
            return
        }

        if (!hasLocationPermission()) {
            Log.d(
                LOG_TAG,
                "Coordinate estimate skipped: location permission is unavailable."
            )
            return
        }

        /*
         * Fetch restaurant geography while the screenshot-time GPS request is
         * already in flight. We resolve the driver anchor only after this returns.
         */
        fetchRestaurantLocation(
            restaurantPlaceId =
                restaurantPlaceId
        ) { restaurantGeo ->
            if (restaurantGeo == null) {
                return@fetchRestaurantLocation
            }

            resolveDriverAnchor(
                screenshotCapturedAtWallTimeMs =
                    screenshotCapturedAtWallTimeMs
            ) { driverLocation, driverSource ->
                if (driverLocation == null) {
                    Log.d(
                        LOG_TAG,
                        "Coordinate estimate failed: phone location unavailable."
                    )
                    return@resolveDriverAnchor
                }

                val driverGeo =
                    DoorDashMapCoordinateMath.GeoPoint(
                        latitude =
                            driverLocation.latitude,
                        longitude =
                            driverLocation.longitude
                    )

                val estimate =
                    DoorDashMapCoordinateMath
                        .estimateCustomer(
                            driverPixel =
                                driverPixel,
                            restaurantPixel =
                                restaurantPixel,
                            customerPixel =
                                customerPixel,
                            driverGeo =
                                driverGeo,
                            restaurantGeo =
                                restaurantGeo
                        )

                if (estimate == null) {
                    Log.d(
                        LOG_TAG,
                        "Coordinate estimate failed: map calibration was not usable."
                    )
                    return@resolveDriverAnchor
                }

                val predictedAt =
                    System.currentTimeMillis()

                val driverAnchorAgeMs =
                    driverLocation.time -
                            screenshotCapturedAtWallTimeMs

                val restaurantDetection =
                    markers.pins.firstOrNull { pin ->
                        pin.anchor ==
                                restaurantPixel
                    }

                val customerDetection =
                    markers.pins.firstOrNull { pin ->
                        pin.anchor ==
                                customerPixel
                    }

                /*
                 * Preserve the original proof-of-concept keys so any existing
                 * debugging code still sees the most recent raw estimate.
                 */
                appContext
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    .edit()
                    .putLong(
                        KEY_PREDICTED_AT_MS,
                        predictedAt
                    )
                    .putString(
                        KEY_RESTAURANT_PLACE_ID,
                        restaurantPlaceId
                    )
                    .putString(
                        KEY_PREDICTED_CUSTOMER_LAT,
                        estimate.customer.latitude.toString()
                    )
                    .putString(
                        KEY_PREDICTED_CUSTOMER_LON,
                        estimate.customer.longitude.toString()
                    )
                    .apply()

                CustomerMapLearningManager
                    .recordRawPrediction(
                        context =
                            appContext,
                        prediction =
                            CustomerMapLearningManager.Prediction(
                                predictionId =
                                    "prediction_" +
                                            UUID.randomUUID()
                                                .toString(),
                                predictedAtWallTimeMs =
                                    predictedAt,
                                screenshotCapturedAtWallTimeMs =
                                    screenshotCapturedAtWallTimeMs,
                                restaurantPlaceId =
                                    restaurantPlaceId,
                                rawLatitude =
                                    estimate.customer.latitude,
                                rawLongitude =
                                    estimate.customer.longitude,
                                correctedLatitude =
                                    estimate.customer.latitude,
                                correctedLongitude =
                                    estimate.customer.longitude,
                                calibrationSampleCount =
                                    0,
                                calibrationConfidence =
                                    0.0,
                                calibrationModelType =
                                    "RAW_ONLY",
                                correctionEastMeters =
                                    0.0,
                                correctionNorthMeters =
                                    0.0,
                                driverLatitude =
                                    driverGeo.latitude,
                                driverLongitude =
                                    driverGeo.longitude,
                                driverAccuracyMeters =
                                    driverLocation.accuracy
                                        .toDouble(),
                                driverLocationWallTimeMs =
                                    driverLocation.time,
                                driverAnchorAgeMs =
                                    driverAnchorAgeMs,
                                restaurantLatitude =
                                    restaurantGeo.latitude,
                                restaurantLongitude =
                                    restaurantGeo.longitude,
                                driverPixelX =
                                    driverPixel.x,
                                driverPixelY =
                                    driverPixel.y,
                                restaurantPixelX =
                                    restaurantPixel.x,
                                restaurantPixelY =
                                    restaurantPixel.y,
                                customerPixelX =
                                    customerPixel.x,
                                customerPixelY =
                                    customerPixel.y,
                                restaurantWhiteDensity =
                                    restaurantDetection
                                        ?.whiteDensity,
                                restaurantHouseScore =
                                    restaurantDetection
                                        ?.houseShapeScore,
                                customerWhiteDensity =
                                    customerDetection
                                        ?.whiteDensity,
                                customerHouseScore =
                                    customerDetection
                                        ?.houseShapeScore,
                                calibrationPixelDistance =
                                    estimate.calibrationPixelDistance,
                                customerPixelDistance =
                                    estimate.customerPixelDistance,
                                extrapolationRatio =
                                    estimate.extrapolationRatio,
                                anchorStraightLineMeters =
                                    estimate.anchorStraightLineMeters,
                                approximateMetersPerPixel =
                                    estimate.approximateMetersPerPixel
                            )
                    )

                Log.d(
                    LOG_TAG,
                    String.format(
                        Locale.US,
                        "GPS_ESTIMATE customer=(%.7f,%.7f) " +
                                "driver=(%.7f,%.7f) restaurant=(%.7f,%.7f) " +
                                "driverAccuracy=%.1fm driverAnchorAge=%+dms source=%s " +
                                "anchorPixels=%.1f anchorMeters=%.1f " +
                                "approxMetersPerPixel=%.2f customerPixels=%.1f extrapolation=%.2f",
                        estimate.customer.latitude,
                        estimate.customer.longitude,
                        driverGeo.latitude,
                        driverGeo.longitude,
                        restaurantGeo.latitude,
                        restaurantGeo.longitude,
                        driverLocation.accuracy.toDouble(),
                        driverAnchorAgeMs,
                        driverSource,
                        estimate.calibrationPixelDistance,
                        estimate.anchorStraightLineMeters,
                        estimate.approximateMetersPerPixel,
                        estimate.customerPixelDistance,
                        estimate.extrapolationRatio
                    )
                )
            }
        }
    }

    private fun considerDriverAnchor(
        screenshotCapturedAtWallTimeMs: Long,
        location: Location,
        source: String
    ) {
        if (
            !location.latitude.isFinite() ||
            !location.longitude.isFinite()
        ) {
            return
        }

        val age =
            location.time -
                    screenshotCapturedAtWallTimeMs

        if (
            abs(age) >
            MAX_SCREENSHOT_ANCHOR_AGE_MS ||
            location.accuracy.toDouble() >
            MAX_SCREENSHOT_ANCHOR_ACCURACY_METERS
        ) {
            return
        }

        val candidate =
            DriverAnchorCandidate(
                screenshotCapturedAtWallTimeMs =
                    screenshotCapturedAtWallTimeMs,
                location =
                    Location(location),
                source =
                    source
            )

        synchronized(anchorLock) {
            val current =
                bestDriverAnchor

            if (
                current == null ||
                current.screenshotCapturedAtWallTimeMs !=
                screenshotCapturedAtWallTimeMs ||
                candidateScore(candidate) <
                candidateScore(current)
            ) {
                bestDriverAnchor =
                    candidate
            }
        }
    }

    private fun candidateScore(
        candidate: DriverAnchorCandidate
    ): Double {
        val timeErrorMs =
            abs(
                candidate.location.time -
                        candidate.screenshotCapturedAtWallTimeMs
            ).toDouble()

        /*
         * Accuracy matters, but timestamp alignment is the dominant goal here.
         * 50 ms per meter means a 20 m accuracy difference is equivalent to one
         * second of temporal mismatch.
         */
        return timeErrorMs +
                candidate.location.accuracy
                    .toDouble() *
                50.0
    }

    private fun resolveDriverAnchor(
        screenshotCapturedAtWallTimeMs: Long,
        onComplete: (Location?, String) -> Unit
    ) {
        val captured =
            synchronized(anchorLock) {
                bestDriverAnchor
                    ?.takeIf { candidate ->
                        candidate.screenshotCapturedAtWallTimeMs ==
                                screenshotCapturedAtWallTimeMs &&
                                abs(
                                    candidate.location.time -
                                            screenshotCapturedAtWallTimeMs
                                ) <= MAX_SCREENSHOT_ANCHOR_AGE_MS
                    }
            }

        if (captured != null) {
            onComplete(
                Location(
                    captured.location
                ),
                captured.source
            )
            return
        }

        getCurrentDriverLocation { location ->
            onComplete(
                location,
                "LATE_LOCATION_FALLBACK"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentDriverLocation(
        onComplete: (Location?) -> Unit
    ) {
        val cancellationTokenSource =
            CancellationTokenSource()

        locationClient
            .getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            )
            .addOnSuccessListener { location ->
                if (location != null) {
                    onComplete(location)
                    return@addOnSuccessListener
                }

                locationClient.lastLocation
                    .addOnSuccessListener { lastLocation ->
                        onComplete(lastLocation)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(
                            LOG_TAG,
                            "Last-location fallback failed.",
                            exception
                        )
                        onComplete(null)
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(
                    LOG_TAG,
                    "Current-location request failed; trying last location.",
                    exception
                )

                locationClient.lastLocation
                    .addOnSuccessListener { lastLocation ->
                        onComplete(lastLocation)
                    }
                    .addOnFailureListener { fallbackException ->
                        Log.e(
                            LOG_TAG,
                            "Last-location fallback failed.",
                            fallbackException
                        )
                        onComplete(null)
                    }
            }
    }

    private fun fetchRestaurantLocation(
        restaurantPlaceId: String,
        onComplete: (DoorDashMapCoordinateMath.GeoPoint?) -> Unit
    ) {
        val request =
            FetchPlaceRequest.newInstance(
                restaurantPlaceId,
                listOf(
                    Place.Field.LOCATION
                )
            )

        placesClient
            .fetchPlace(request)
            .addOnSuccessListener { response ->
                val location =
                    response.place.location

                if (location == null) {
                    Log.d(
                        LOG_TAG,
                        "Coordinate estimate failed: Google Place location was null."
                    )
                    onComplete(null)
                    return@addOnSuccessListener
                }

                onComplete(
                    DoorDashMapCoordinateMath.GeoPoint(
                        latitude =
                            location.latitude,
                        longitude =
                            location.longitude
                    )
                )
            }
            .addOnFailureListener { exception ->
                Log.e(
                    LOG_TAG,
                    "Could not fetch matched restaurant location by Place ID.",
                    exception
                )
                onComplete(null)
            }
    }

    private fun hasLocationPermission(): Boolean {
        val fineLocationGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineLocationGranted ||
                coarseLocationGranted
    }
}
