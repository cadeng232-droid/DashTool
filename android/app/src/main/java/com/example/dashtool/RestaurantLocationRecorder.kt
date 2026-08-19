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
import java.util.UUID
import org.json.JSONObject

/**
 * Records a restaurant coordinate from the phone's GPS when DashTool detects
 * ARRIVED_AT_RESTAURANT. This does not make a Places or Routes request.
 */
object RestaurantLocationRecorder {

    private const val LOG_TAG = "DashToolRestaurantGPS"
    private const val MAX_ACCEPTABLE_ACCURACY_METERS = 100.0f

    fun record(
        context: Context,
        offerId: String,
        restaurantPlaceId: String?,
        restaurantName: String,
        observedAtWallTime: Long
    ) {
        val appContext = context.applicationContext

        if (!hasLocationPermission(appContext)) {
            Log.w(
                LOG_TAG,
                "Restaurant coordinate skipped: location permission missing."
            )
            return
        }

        requestCurrentLocation(
            context = appContext
        ) { location ->
            if (location == null) {
                Log.w(
                    LOG_TAG,
                    "Restaurant coordinate skipped: no location available."
                )
                return@requestCurrentLocation
            }

            if (
                location.hasAccuracy() &&
                location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS
            ) {
                Log.w(
                    LOG_TAG,
                    "Restaurant coordinate skipped: accuracy " +
                            "${location.accuracy} m is too low."
                )
                return@requestCurrentLocation
            }

            val payload =
                JSONObject().apply {
                    put(
                        "observation_id",
                        "restaurant_observation_${UUID.randomUUID()}"
                    )
                    put(
                        "offer_id",
                        offerId
                    )
                    put(
                        "restaurant_place_id",
                        restaurantPlaceId ?: JSONObject.NULL
                    )
                    put(
                        "restaurant_name",
                        restaurantName
                    )
                    put(
                        "latitude",
                        location.latitude
                    )
                    put(
                        "longitude",
                        location.longitude
                    )
                    put(
                        "accuracy_meters",
                        if (location.hasAccuracy()) {
                            location.accuracy.toDouble()
                        } else {
                            JSONObject.NULL
                        }
                    )
                    put(
                        "observed_at_wall_time_ms",
                        observedAtWallTime
                    )
                    put(
                        "coordinate_source",
                        "GPS_AT_RESTAURANT"
                    )
                }

            Thread {
                WaitingDataUploadManager
                    .enqueueAndTryUpload(
                        context = appContext,
                        endpoint = "/restaurants/observations",
                        payload = payload
                    )
            }.start()
        }
    }

    private fun hasLocationPermission(
        context: Context
    ): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(
        context: Context,
        onResult: (Location?) -> Unit
    ) {
        val locationClient =
            LocationServices
                .getFusedLocationProviderClient(
                    context
                )

        val cancellationTokenSource =
            CancellationTokenSource()

        locationClient
            .getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            )
            .addOnSuccessListener { location ->
                if (location != null) {
                    onResult(location)
                    return@addOnSuccessListener
                }

                locationClient.lastLocation
                    .addOnSuccessListener { lastLocation ->
                        onResult(lastLocation)
                    }
                    .addOnFailureListener {
                        onResult(null)
                    }
            }
            .addOnFailureListener {
                locationClient.lastLocation
                    .addOnSuccessListener { lastLocation ->
                        onResult(lastLocation)
                    }
                    .addOnFailureListener {
                        onResult(null)
                    }
            }
    }
}