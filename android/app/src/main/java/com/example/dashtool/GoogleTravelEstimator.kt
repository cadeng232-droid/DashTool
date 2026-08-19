package com.example.dashtool

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RoutingParameters
import com.google.android.libraries.places.api.net.PlacesStatusCodes
import com.google.android.libraries.places.api.net.SearchByTextRequest

data class GoogleTravelEstimate(

    val matchedRestaurantName: String,

    /*
     * Identifies the exact Google restaurant branch.
     *
     * It remains nullable because Google could
     * theoretically return a result without an ID.
     */
    val matchedRestaurantPlaceId: String?,

    val minutesToRestaurant: Double,

    val milesToRestaurant: Double
)

class GoogleTravelEstimator(
    context: Context
) {

    companion object {

        private const val LOG_TAG =
            "DashToolTravel"

        private const val METERS_TO_MILES =
            0.000621371

        private const val SEARCH_RADIUS_METERS =
            24_140.0
    }

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

    var lastError: String =
        "No error recorded."
        private set

    fun estimateToNearestRestaurant(
        restaurantName: String,
        onComplete: (
            GoogleTravelEstimate?
        ) -> Unit
    ) {
        lastError =
            "No error recorded."

        if (restaurantName.isBlank()) {
            fail(
                message =
                    "Restaurant name was blank.",

                onComplete =
                    onComplete
            )

            return
        }

        val fineLocationGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission
                    .ACCESS_FINE_LOCATION
            ) ==
                    PackageManager
                        .PERMISSION_GRANTED

        val coarseLocationGranted =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission
                    .ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager
                        .PERMISSION_GRANTED

        if (
            !fineLocationGranted &&
            !coarseLocationGranted
        ) {
            fail(
                message =
                    "Location permission has not been granted.",

                onComplete =
                    onComplete
            )

            return
        }

        getCurrentLocation(
            restaurantName =
                restaurantName,

            onComplete =
                onComplete
        )
    }

    @SuppressLint(
        "MissingPermission"
    )
    private fun getCurrentLocation(
        restaurantName: String,
        onComplete: (
            GoogleTravelEstimate?
        ) -> Unit
    ) {
        val cancellationTokenSource =
            CancellationTokenSource()

        locationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        )
            .addOnSuccessListener {
                    location ->

                if (location != null) {
                    searchForRestaurant(
                        restaurantName =
                            restaurantName,

                        origin =
                            LatLng(
                                location.latitude,
                                location.longitude
                            ),

                        onComplete =
                            onComplete
                    )

                    return@addOnSuccessListener
                }

                /*
                 * Current location occasionally returns
                 * null. Fall back to the most recently
                 * available phone location.
                 */
                getLastKnownLocation(
                    restaurantName =
                        restaurantName,

                    onComplete =
                        onComplete
                )
            }
            .addOnFailureListener {
                    exception ->

                fail(
                    message =
                        "Current-location request failed: " +
                                (
                                        exception.message
                                            ?: exception
                                                .javaClass
                                                .simpleName
                                        ),

                    exception =
                        exception,

                    onComplete =
                        onComplete
                )
            }
    }

    @SuppressLint(
        "MissingPermission"
    )
    private fun getLastKnownLocation(
        restaurantName: String,
        onComplete: (
            GoogleTravelEstimate?
        ) -> Unit
    ) {
        locationClient.lastLocation
            .addOnSuccessListener {
                    location ->

                if (location == null) {
                    fail(
                        message =
                            "No phone location is available. " +
                                    "Turn on Location, open Google Maps " +
                                    "briefly, and try again.",

                        onComplete =
                            onComplete
                    )

                    return@addOnSuccessListener
                }

                searchForRestaurant(
                    restaurantName =
                        restaurantName,

                    origin =
                        LatLng(
                            location.latitude,
                            location.longitude
                        ),

                    onComplete =
                        onComplete
                )
            }
            .addOnFailureListener {
                    exception ->

                fail(
                    message =
                        "Last-location request failed: " +
                                (
                                        exception.message
                                            ?: exception
                                                .javaClass
                                                .simpleName
                                        ),

                    exception =
                        exception,

                    onComplete =
                        onComplete
                )
            }
    }

    private fun searchForRestaurant(
        restaurantName: String,
        origin: LatLng,
        onComplete: (
            GoogleTravelEstimate?
        ) -> Unit
    ) {
        val routingParameters =
            RoutingParameters.builder()
                .setOrigin(
                    origin
                )
                .setTravelMode(
                    RoutingParameters
                        .TravelMode
                        .DRIVE
                )
                .setRoutingPreference(
                    RoutingParameters
                        .RoutingPreference
                        .TRAFFIC_AWARE
                )
                .build()

        val locationBias =
            CircularBounds.newInstance(
                origin,
                SEARCH_RADIUS_METERS
            )

        /*
         * ID is already part of the existing request.
         * Returning it from GoogleTravelEstimate does
         * not create a second API request.
         */
        val placeFields =
            listOf(
                Place.Field.ID,
                Place.Field.DISPLAY_NAME
            )

        val request =
            SearchByTextRequest.builder(
                restaurantName,
                placeFields
            )
                .setMaxResultCount(
                    5
                )
                .setLocationBias(
                    locationBias
                )
                .setRegionCode(
                    "US"
                )
                .setRoutingParameters(
                    routingParameters
                )
                .setRoutingSummariesIncluded(
                    true
                )
                .build()

        placesClient.searchByText(
            request
        )
            .addOnSuccessListener {
                    response ->

                if (
                    response.places.isEmpty()
                ) {
                    fail(
                        message =
                            "Google returned no matching restaurants " +
                                    "for \"$restaurantName\".",

                        onComplete =
                            onComplete
                    )

                    return@addOnSuccessListener
                }

                val routingSummaries =
                    response.routingSummaries
                        ?: emptyList()

                if (
                    routingSummaries.isEmpty()
                ) {
                    fail(
                        message =
                            "Google found restaurants, but returned " +
                                    "no travel estimates.",

                        onComplete =
                            onComplete
                    )

                    return@addOnSuccessListener
                }

                val estimates =
                    response.places.indices
                        .mapNotNull {
                                index ->

                            val place =
                                response.places[
                                    index
                                ]

                            val routingSummary =
                                routingSummaries
                                    .getOrNull(
                                        index
                                    )
                                    ?: return@mapNotNull null

                            val routeLeg =
                                routingSummary
                                    .legs
                                    .firstOrNull()
                                    ?: return@mapNotNull null

                            val minutes =
                                routeLeg.duration
                                    .toMillis()
                                    .toDouble() /
                                        60_000.0

                            val miles =
                                routeLeg
                                    .distanceMeters
                                    .toDouble() *
                                        METERS_TO_MILES

                            GoogleTravelEstimate(
                                matchedRestaurantName =
                                    place.displayName
                                        ?: restaurantName,

                                matchedRestaurantPlaceId =
                                    place.id,

                                minutesToRestaurant =
                                    minutes,

                                milesToRestaurant =
                                    miles
                            )
                        }

                val nearestEstimate =
                    estimates.minByOrNull {
                            estimate ->

                        estimate
                            .milesToRestaurant
                    }

                if (
                    nearestEstimate == null
                ) {
                    fail(
                        message =
                            "Google returned results, but none had " +
                                    "a usable route.",

                        onComplete =
                            onComplete
                    )

                    return@addOnSuccessListener
                }

                lastError =
                    "No error."

                Log.d(
                    LOG_TAG,
                    "Matched " +
                            nearestEstimate
                                .matchedRestaurantName +
                            " [" +
                            (
                                    nearestEstimate
                                        .matchedRestaurantPlaceId
                                        ?: "no place ID"
                                    ) +
                            "]: " +
                            nearestEstimate
                                .minutesToRestaurant +
                            " minutes, " +
                            nearestEstimate
                                .milesToRestaurant +
                            " miles."
                )

                onComplete(
                    nearestEstimate
                )
            }
            .addOnFailureListener {
                    exception ->

                val errorMessage =
                    if (
                        exception is ApiException
                    ) {
                        val statusName =
                            PlacesStatusCodes
                                .getStatusCodeString(
                                    exception
                                        .statusCode
                                )

                        "Places request failed: " +
                                "$statusName " +
                                "(${exception.statusCode}). " +
                                (
                                        exception.message
                                            ?: ""
                                        )
                    } else {
                        "Places request failed: " +
                                (
                                        exception.message
                                            ?: exception
                                                .javaClass
                                                .simpleName
                                        )
                    }

                fail(
                    message =
                        errorMessage,

                    exception =
                        exception,

                    onComplete =
                        onComplete
                )
            }
    }

    private fun fail(
        message: String,
        exception: Exception? = null,
        onComplete: (
            GoogleTravelEstimate?
        ) -> Unit
    ) {
        lastError =
            message

        if (exception == null) {
            Log.e(
                LOG_TAG,
                message
            )
        } else {
            Log.e(
                LOG_TAG,
                message,
                exception
            )
        }

        onComplete(
            null
        )
    }
}