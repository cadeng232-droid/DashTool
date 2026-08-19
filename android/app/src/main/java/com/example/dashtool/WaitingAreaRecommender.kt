package com.example.dashtool

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RoutingParameters
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Discovers nearby shopping-center candidates and ranks the best nearby options.
 *
 * Final ranking factors:
 *  45% nearby restaurant coverage
 *  35% historical wait (shorter is better)
 *  20% current straight-line distance
 *
 * Restaurant coverage uses a live Google Nearby Search for only the best four
 * preliminary center candidates. If that live search fails, DashTool falls back
 * to its own GPS-observed restaurant database.
 *
 * No time-of-day or opening-hours adjustment is used here.
 */
class WaitingAreaRecommender(
    context: Context
) {

    data class Recommendation(
        val recommendedCenter: WaitingAreaTracker.WaitingCenter,
        val candidateCenters: List<WaitingAreaTracker.WaitingCenter>,
        /*
         * Live Google Maps display name for this recommendation only.
         * It is deliberately not copied into WaitingCenter because that object
         * is persisted by WaitingAreaTracker.
         */
        val displayName: String?,
        val distanceMiles: Double,
        val restaurantCount: Int,
        val historicalWaitMinutes: Double?,
        val historicalWaitSamples: Int,
        val scoreOutOf100: Double,
        val restaurantScore: Double,
        val historicalWaitScore: Double,
        val distanceScore: Double
    ) {
        fun overlaySummary(): String {
            val waitText =
                if (
                    historicalWaitSamples >
                    0 &&
                    historicalWaitMinutes !=
                    null
                ) {
                    String.format(
                        java.util.Locale.US,
                        "%.1f min (%d sample%s)",
                        historicalWaitMinutes,
                        historicalWaitSamples,
                        if (
                            historicalWaitSamples ==
                            1
                        ) {
                            ""
                        } else {
                            "s"
                        }
                    )
                } else {
                    "No center history yet"
                }

            val restaurantText =
                if (
                    restaurantCount >=
                    MAX_RESTAURANT_RESULTS
                ) {
                    "$MAX_RESTAURANT_RESULTS+"
                } else {
                    restaurantCount.toString()
                }

            return buildString {
                appendLine(
                    String.format(
                        java.util.Locale.US,
                        "%.1f mi away",
                        distanceMiles
                    )
                )
                appendLine("Nearby restaurants: $restaurantText")
                appendLine("Historical wait: $waitText")
                append(
                    String.format(
                        java.util.Locale.US,
                        "Center rank: %.0f / 100",
                        scoreOutOf100
                    )
                )
            }
        }
    }

    /**
     * Post-delivery estimate used only by the order grading engine.
     *
     * Unlike the idle recommendation, this deliberately chooses the shopping
     * center with the shortest traffic-aware drive from the predicted customer
     * coordinates. Restaurant density does not affect this selection.
     */
    data class PostDeliveryEstimate(
        val center: WaitingAreaTracker.WaitingCenter,
        val displayName: String?,
        val driveMinutes: Double,
        val driveMiles: Double,
        val historicalWaitMinutes: Double,
        val historicalWaitSamples: Int,
        val usedGlobalWaitFallback: Boolean,
        val usedDefaultWaitFallback: Boolean
    ) {
        val totalPostDeliveryMinutes: Double
            get() = driveMinutes + historicalWaitMinutes
    }

    private data class RestaurantPoint(
        val latitude: Double,
        val longitude: Double
    )

    private data class HistoricalCenter(
        val blendedWaitMinutes: Double?,
        val samples: Int
    )

    companion object {
        private const val LOG_TAG = "DashToolCenterRank"

        private const val SEARCH_RADIUS_METERS = 10_000.0
        private const val POST_DELIVERY_SEARCH_RADIUS_METERS = 25_000.0
        private const val RESTAURANT_COUNT_RADIUS_METERS = 1_609.344

        private const val MAX_CENTER_RESULTS = 20
        private const val MAX_RESTAURANT_ENRICHMENT_CENTERS = 4
        internal const val MAX_RESTAURANT_RESULTS = 20

        private const val MAX_DISTANCE_SCORE_MILES = 6.25
        private const val WAIT_SCORE_ZERO_MINUTES = 15.0
        private const val MAX_REASONABLE_HISTORICAL_WAIT_MINUTES = 180.0

        /*
         * Only used when neither this center nor the server-wide history has
         * any usable waiting samples yet. It prevents an unknown future wait
         * from being treated as zero and artificially inflating the grade.
         */
        private const val DEFAULT_POST_DELIVERY_WAIT_MINUTES = 8.0
        private const val METERS_TO_MILES = 0.000621371

        private const val RESTAURANT_SATURATION_COUNT = 8.0

        private const val RESTAURANT_WEIGHT = 0.45
        private const val HISTORICAL_WAIT_WEIGHT = 0.35
        private const val DISTANCE_WEIGHT = 0.20
    }

    private val appContext =
        context.applicationContext

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val locationClient =
        LocationServices
            .getFusedLocationProviderClient(
                appContext
            )

    private val placesClient =
        Places.createClient(
            appContext
        )

    /*
     * Google Maps place names are kept only for the current in-memory search.
     * Place IDs remain the persistent center identity.
     */
    private val liveDisplayNamesByCenterId =
        mutableMapOf<String, String>()

    @Volatile
    private var lastKnownGlobalHistoricalWaitMinutes: Double? =
        null

    /**
     * Immediate fallback used while an offer-specific customer prediction and
     * route are still being resolved. This is never zero merely because data
     * is temporarily unavailable.
     */
    fun fallbackPostDeliveryWaitMinutes(): Double {
        return lastKnownGlobalHistoricalWaitMinutes
            ?.takeIf {
                isReasonableHistoricalWait(it)
            }
            ?: DEFAULT_POST_DELIVERY_WAIT_MINUTES
    }

    fun recommend(
        onComplete: (Recommendation?) -> Unit
    ) {
        if (!hasLocationPermission()) {
            Log.w(
                LOG_TAG,
                "Center recommendation skipped: location permission missing."
            )
            onMain {
                onComplete(null)
            }
            return
        }

        Thread {
            val restaurantPoints =
                loadRestaurantPoints()

            val historicalData =
                loadHistoricalData()

            requestCurrentLocation { location ->
                if (location == null) {
                    Log.w(
                        LOG_TAG,
                        "Center recommendation skipped: no phone location."
                    )
                    onMain {
                        onComplete(null)
                    }
                    return@requestCurrentLocation
                }

                searchNearbyCenters(
                    currentLocation = location,
                    restaurantPoints = restaurantPoints,
                    historicalData = historicalData,
                    onComplete = onComplete
                )
            }
        }.start()
    }

    /**
     * Estimate the unpaid time after completing an offered order.
     *
     * Search is centered on the predicted customer coordinates rather than the
     * phone's current location. Among nearby shopping malls, the center with
     * the shortest usable driving duration is selected. Its learned blended
     * wait is preferred, then the global historical wait, then a conservative
     * default while the model is still cold.
     */
    fun estimatePostDelivery(
        customerLatitude: Double,
        customerLongitude: Double,
        onComplete: (PostDeliveryEstimate?) -> Unit
    ) {
        if (
            !customerLatitude.isFinite() ||
            !customerLongitude.isFinite() ||
            customerLatitude !in -90.0..90.0 ||
            customerLongitude !in -180.0..180.0
        ) {
            onMain {
                onComplete(null)
            }
            return
        }

        Thread {
            val historicalData =
                loadHistoricalData()

            searchPostDeliveryCenters(
                customerLatitude = customerLatitude,
                customerLongitude = customerLongitude,
                historicalData = historicalData,
                onComplete = onComplete
            )
        }.start()
    }

    private fun searchPostDeliveryCenters(
        customerLatitude: Double,
        customerLongitude: Double,
        historicalData: Pair<Map<String, HistoricalCenter>, Double?>,
        onComplete: (PostDeliveryEstimate?) -> Unit
    ) {
        val origin =
            LatLng(
                customerLatitude,
                customerLongitude
            )

        val circle =
            CircularBounds.newInstance(
                origin,
                POST_DELIVERY_SEARCH_RADIUS_METERS
            )

        val routingParameters =
            RoutingParameters.builder()
                .setOrigin(origin)
                .setTravelMode(
                    RoutingParameters.TravelMode.DRIVE
                )
                .setRoutingPreference(
                    RoutingParameters.RoutingPreference.TRAFFIC_AWARE
                )
                .build()

        val request =
            SearchNearbyRequest
                .builder(
                    circle,
                    listOf(
                        Place.Field.ID,
                        Place.Field.LOCATION,
                        Place.Field.DISPLAY_NAME
                    )
                )
                .setIncludedTypes(
                    listOf(
                        "shopping_mall"
                    )
                )
                .setMaxResultCount(
                    MAX_CENTER_RESULTS
                )
                .setRankPreference(
                    SearchNearbyRequest
                        .RankPreference
                        .DISTANCE
                )
                .setRoutingParameters(
                    routingParameters
                )
                .setRoutingSummariesIncluded(
                    true
                )
                .build()

        placesClient
            .searchNearby(request)
            .addOnSuccessListener { response ->
                val routingSummaries =
                    response.routingSummaries
                        ?: emptyList()

                val historyMap =
                    historicalData.first

                val globalWait =
                    historicalData.second
                        ?.takeIf {
                            isReasonableHistoricalWait(it)
                        }

                val estimates =
                    response.places.indices
                        .mapNotNull { index ->
                            val place =
                                response.places[index]

                            val placeId =
                                place.id
                                    ?: return@mapNotNull null

                            val location =
                                place.location
                                    ?: return@mapNotNull null

                            val routeLeg =
                                routingSummaries
                                    .getOrNull(index)
                                    ?.legs
                                    ?.firstOrNull()
                                    ?: return@mapNotNull null

                            val driveMinutes =
                                routeLeg.duration
                                    .toMillis()
                                    .toDouble() /
                                        60_000.0

                            val driveMiles =
                                routeLeg.distanceMeters
                                    .toDouble() *
                                        METERS_TO_MILES

                            if (
                                !driveMinutes.isFinite() ||
                                !driveMiles.isFinite() ||
                                driveMinutes < 0.0 ||
                                driveMiles < 0.0
                            ) {
                                return@mapNotNull null
                            }

                            val centerHistory =
                                historyMap[placeId]

                            val centerWait =
                                centerHistory
                                    ?.blendedWaitMinutes
                                    ?.takeIf {
                                        isReasonableHistoricalWait(it)
                                    }

                            val waitMinutes =
                                centerWait
                                    ?: globalWait
                                    ?: DEFAULT_POST_DELIVERY_WAIT_MINUTES

                            val center =
                                WaitingAreaTracker.WaitingCenter(
                                    centerId = placeId,
                                    centerName = localCenterLabel(placeId),
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    restaurantCount = 0
                                )

                            PostDeliveryEstimate(
                                center = center,
                                displayName =
                                    place.displayName
                                        ?.toString()
                                        ?.trim()
                                        ?.takeIf {
                                            it.isNotEmpty()
                                        },
                                driveMinutes = driveMinutes,
                                driveMiles = driveMiles,
                                historicalWaitMinutes = waitMinutes,
                                historicalWaitSamples =
                                    centerHistory?.samples
                                        ?: 0,
                                usedGlobalWaitFallback =
                                    centerWait == null &&
                                            globalWait != null,
                                usedDefaultWaitFallback =
                                    centerWait == null &&
                                            globalWait == null
                            )
                        }

                val best =
                    estimates.minWithOrNull(
                        compareBy<PostDeliveryEstimate> {
                            it.driveMinutes
                        }.thenBy {
                            it.driveMiles
                        }
                    )

                if (best == null) {
                    Log.w(
                        LOG_TAG,
                        "Predicted-destination shopping-center search " +
                                "returned no usable driving routes."
                    )
                } else {
                    Log.d(
                        LOG_TAG,
                        "Predicted post-delivery center ${best.center.centerId}: " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f min / %.2f mi drive + %.1f min wait",
                                    best.driveMinutes,
                                    best.driveMiles,
                                    best.historicalWaitMinutes
                                )
                    )
                }

                onMain {
                    onComplete(best)
                }
            }
            .addOnFailureListener { exception ->
                Log.w(
                    LOG_TAG,
                    "Predicted-destination shopping-center search failed: " +
                            (exception.message
                                ?: exception.javaClass.simpleName),
                    exception
                )

                onMain {
                    onComplete(null)
                }
            }
    }

    private fun loadRestaurantPoints(): List<RestaurantPoint> {
        val response =
            WaitingDataClient.getJson(
                "/restaurants?limit=5000"
            )
                ?: return emptyList()

        val array =
            response.optJSONArray(
                "restaurants"
            )
                ?: return emptyList()

        return buildList {
            for (
            index in
            0 until array.length()
            ) {
                val item =
                    array.optJSONObject(index)
                        ?: continue

                if (
                    !item.has("latitude") ||
                    !item.has("longitude")
                ) {
                    continue
                }

                add(
                    RestaurantPoint(
                        latitude =
                            item.optDouble(
                                "latitude"
                            ),

                        longitude =
                            item.optDouble(
                                "longitude"
                            )
                    )
                )
            }
        }
    }

    private fun loadHistoricalData(): Pair<Map<String, HistoricalCenter>, Double?> {
        val response =
            WaitingDataClient.getJson(
                "/waiting-centers/stats"
            )
                ?: return emptyMap<String, HistoricalCenter>() to null

        val globalWait =
            response
                .optJSONObject(
                    "global_historical_wait"
                )
                ?.optDouble(
                    "mean_minutes"
                )
                ?.takeIf {
                    isReasonableHistoricalWait(
                        it
                    )
                }

        if (globalWait != null) {
            lastKnownGlobalHistoricalWaitMinutes =
                globalWait
        }

        val centers =
            response.optJSONArray(
                "centers"
            )
                ?: return emptyMap<String, HistoricalCenter>() to globalWait

        val map =
            mutableMapOf<String, HistoricalCenter>()

        for (
        index in
        0 until centers.length()
        ) {
            val item =
                centers.optJSONObject(index)
                    ?: continue

            val centerId =
                item.optString(
                    "center_id"
                )
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: continue

            val blended =
                if (
                    item.isNull(
                        "blended_historical_wait_minutes"
                    )
                ) {
                    null
                } else {
                    item.optDouble(
                        "blended_historical_wait_minutes"
                    )
                        .takeIf {
                            isReasonableHistoricalWait(
                                it
                            )
                        }
                }

            val samples =
                item
                    .optJSONObject(
                        "historical_wait"
                    )
                    ?.optInt(
                        "samples",
                        0
                    )
                    ?: 0

            map[centerId] =
                HistoricalCenter(
                    blendedWaitMinutes =
                        blended,

                    samples =
                        samples
                )
        }

        return map to globalWait
    }

    private fun searchNearbyCenters(
        currentLocation: Location,
        restaurantPoints: List<RestaurantPoint>,
        historicalData: Pair<Map<String, HistoricalCenter>, Double?>,
        onComplete: (Recommendation?) -> Unit
    ) {
        val currentLatLng =
            LatLng(
                currentLocation.latitude,
                currentLocation.longitude
            )

        val circle =
            CircularBounds.newInstance(
                currentLatLng,
                SEARCH_RADIUS_METERS
            )

        val placeFields =
            listOf(
                Place.Field.ID,
                Place.Field.LOCATION,
                Place.Field.DISPLAY_NAME
            )

        val request =
            SearchNearbyRequest
                .builder(
                    circle,
                    placeFields
                )
                .setIncludedTypes(
                    listOf(
                        "shopping_mall"
                    )
                )
                .setMaxResultCount(
                    MAX_CENTER_RESULTS
                )
                .setRankPreference(
                    SearchNearbyRequest
                        .RankPreference
                        .DISTANCE
                )
                .build()

        placesClient
            .searchNearby(
                request
            )
            .addOnSuccessListener { response ->
                liveDisplayNamesByCenterId.clear()

                val candidates =
                    response.places
                        .mapNotNull { place ->
                            val placeId =
                                place.id
                                    ?: return@mapNotNull null

                            val location =
                                place.location
                                    ?: return@mapNotNull null

                            place.displayName
                                ?.toString()
                                ?.trim()
                                ?.takeIf {
                                    it.isNotEmpty()
                                }
                                ?.let { displayName ->
                                    liveDisplayNamesByCenterId[
                                        placeId
                                    ] =
                                        displayName
                                }

                            WaitingAreaTracker
                                .WaitingCenter(
                                    centerId =
                                        placeId,

                                    centerName =
                                        localCenterLabel(
                                            placeId
                                        ),

                                    latitude =
                                        location.latitude,

                                    longitude =
                                        location.longitude,

                                    restaurantCount =
                                        countSavedRestaurants(
                                            centerLatitude =
                                                location.latitude,

                                            centerLongitude =
                                                location.longitude,

                                            restaurantPoints =
                                                restaurantPoints
                                        )
                                )
                        }

                if (candidates.isEmpty()) {
                    Log.w(
                        LOG_TAG,
                        "Nearby Search returned no usable shopping centers."
                    )
                    onMain {
                        onComplete(null)
                    }
                    return@addOnSuccessListener
                }

                val shortlist =
                    chooseRestaurantSearchShortlist(
                        currentLocation =
                            currentLocation,

                        candidates =
                            candidates,

                        historicalData =
                            historicalData
                    )

                enrichRestaurantCounts(
                    centers =
                        shortlist
                ) { enrichedCenters ->
                    rankCenters(
                        currentLocation =
                            currentLocation,

                        allCandidates =
                            candidates,

                        rankingCandidates =
                            enrichedCenters,

                        historicalData =
                            historicalData,

                        onComplete =
                            onComplete
                    )
                }
            }
            .addOnFailureListener { exception ->
                Log.w(
                    LOG_TAG,
                    "Nearby shopping-center search failed: " +
                            (
                                    exception.message
                                        ?: exception.javaClass.simpleName
                                    ),
                    exception
                )
                onMain {
                    onComplete(null)
                }
            }
    }

    private fun chooseRestaurantSearchShortlist(
        currentLocation: Location,
        candidates: List<WaitingAreaTracker.WaitingCenter>,
        historicalData: Pair<Map<String, HistoricalCenter>, Double?>
    ): List<WaitingAreaTracker.WaitingCenter> {
        val historyMap =
            historicalData.first

        val globalWait =
            historicalData.second

        return candidates
            .map { center ->
                val distanceMiles =
                    straightLineMiles(
                        currentLocation.latitude,
                        currentLocation.longitude,
                        center.latitude,
                        center.longitude
                    )

                val historicalWait =
                    historyMap[
                        center.centerId
                    ]
                        ?.blendedWaitMinutes
                        ?: globalWait

                val preliminaryScore =
                    RESTAURANT_WEIGHT *
                            restaurantScore(
                                center.restaurantCount
                            ) +
                            HISTORICAL_WAIT_WEIGHT *
                            historicalWaitScore(
                                historicalWait
                            ) +
                            DISTANCE_WEIGHT *
                            distanceScore(
                                distanceMiles
                            )

                center to preliminaryScore
            }
            .sortedByDescending {
                it.second
            }
            .take(
                MAX_RESTAURANT_ENRICHMENT_CENTERS
            )
            .map {
                it.first
            }
    }

    private fun enrichRestaurantCounts(
        centers: List<WaitingAreaTracker.WaitingCenter>,
        onComplete: (List<WaitingAreaTracker.WaitingCenter>) -> Unit
    ) {
        if (centers.isEmpty()) {
            onComplete(
                emptyList()
            )
            return
        }

        val remaining =
            AtomicInteger(
                centers.size
            )

        val results =
            mutableListOf<WaitingAreaTracker.WaitingCenter>()

        centers.forEach { center ->
            searchLiveRestaurantCount(
                center =
                    center
            ) { liveCount ->
                val finalCount =
                    if (liveCount == null) {
                        center.restaurantCount
                    } else {
                        max(
                            center.restaurantCount,
                            liveCount
                        )
                    }

                synchronized(results) {
                    results.add(
                        center.copy(
                            restaurantCount =
                                finalCount
                        )
                    )
                }

                if (
                    remaining.decrementAndGet() ==
                    0
                ) {
                    onComplete(
                        synchronized(results) {
                            results.toList()
                        }
                    )
                }
            }
        }
    }

    private fun searchLiveRestaurantCount(
        center: WaitingAreaTracker.WaitingCenter,
        onComplete: (Int?) -> Unit
    ) {
        val circle =
            CircularBounds.newInstance(
                LatLng(
                    center.latitude,
                    center.longitude
                ),
                RESTAURANT_COUNT_RADIUS_METERS
            )

        /*
         * We only need IDs to count matching restaurants.
         * No Google restaurant coordinates/names are persisted here.
         */
        val request =
            SearchNearbyRequest
                .builder(
                    circle,
                    listOf(
                        Place.Field.ID
                    )
                )
                .setIncludedTypes(
                    listOf(
                        "restaurant"
                    )
                )
                .setMaxResultCount(
                    MAX_RESTAURANT_RESULTS
                )
                .setRankPreference(
                    SearchNearbyRequest
                        .RankPreference
                        .POPULARITY
                )
                .build()

        placesClient
            .searchNearby(
                request
            )
            .addOnSuccessListener { response ->
                val count =
                    response.places.size

                Log.d(
                    LOG_TAG,
                    "Center ${center.centerId}: live restaurant coverage=$count, " +
                            "saved=${center.restaurantCount}."
                )

                onComplete(
                    count
                )
            }
            .addOnFailureListener { exception ->
                Log.w(
                    LOG_TAG,
                    "Restaurant coverage search failed for " +
                            "${center.centerId}; using saved GPS count " +
                            "${center.restaurantCount}. " +
                            (
                                    exception.message
                                        ?: exception.javaClass.simpleName
                                    )
                )

                onComplete(
                    null
                )
            }
    }

    private fun rankCenters(
        currentLocation: Location,
        allCandidates: List<WaitingAreaTracker.WaitingCenter>,
        rankingCandidates: List<WaitingAreaTracker.WaitingCenter>,
        historicalData: Pair<Map<String, HistoricalCenter>, Double?>,
        onComplete: (Recommendation?) -> Unit
    ) {
        val historyMap =
            historicalData.first

        val globalWait =
            historicalData.second

        val ranked =
            rankingCandidates.map { center ->
                val distanceMiles =
                    straightLineMiles(
                        currentLocation.latitude,
                        currentLocation.longitude,
                        center.latitude,
                        center.longitude
                    )

                val centerHistory =
                    historyMap[
                        center.centerId
                    ]

                val historicalWait =
                    centerHistory
                        ?.blendedWaitMinutes
                        ?: globalWait

                val restaurantScore =
                    restaurantScore(
                        center.restaurantCount
                    )

                val waitScore =
                    historicalWaitScore(
                        historicalWait
                    )

                val distanceScore =
                    distanceScore(
                        distanceMiles
                    )

                val totalScore =
                    100.0 *
                            (
                                    RESTAURANT_WEIGHT *
                                            restaurantScore +
                                            HISTORICAL_WAIT_WEIGHT *
                                            waitScore +
                                            DISTANCE_WEIGHT *
                                            distanceScore
                                    )

                Recommendation(
                    recommendedCenter =
                        center,

                    candidateCenters =
                        allCandidates,

                    displayName =
                        liveDisplayNamesByCenterId[
                            center.centerId
                        ],

                    distanceMiles =
                        distanceMiles,

                    restaurantCount =
                        center.restaurantCount,

                    historicalWaitMinutes =
                        historicalWait,

                    historicalWaitSamples =
                        centerHistory?.samples
                            ?: 0,

                    scoreOutOf100 =
                        totalScore,

                    restaurantScore =
                        restaurantScore,

                    historicalWaitScore =
                        waitScore,

                    distanceScore =
                        distanceScore
                )
            }

        val best =
            ranked.maxByOrNull {
                it.scoreOutOf100
            }

        if (best != null) {
            Log.d(
                LOG_TAG,
                "Recommended ${best.recommendedCenter.centerId}: " +
                        "score=" +
                        String.format(
                            java.util.Locale.US,
                            "%.1f",
                            best.scoreOutOf100
                        ) +
                        ", distance=" +
                        String.format(
                            java.util.Locale.US,
                            "%.2f",
                            best.distanceMiles
                        ) +
                        " mi, restaurants=${best.restaurantCount}, " +
                        "wait=${best.historicalWaitMinutes ?: "none"}."
            )
        }

        onMain {
            onComplete(
                best
            )
        }
    }

    private fun restaurantScore(
        count: Int
    ): Double {
        if (count <= 0) {
            return 0.0
        }

        return min(
            1.0,
            ln(
                1.0 + count.toDouble()
            ) /
                    ln(
                        1.0 + RESTAURANT_SATURATION_COUNT
                    )
        )
    }

    private fun isReasonableHistoricalWait(
        waitMinutes: Double
    ): Boolean {
        return (
                !waitMinutes.isNaN() &&
                        !waitMinutes.isInfinite() &&
                        waitMinutes >= 0.0 &&
                        waitMinutes <=
                        MAX_REASONABLE_HISTORICAL_WAIT_MINUTES
                )
    }

    private fun historicalWaitScore(
        waitMinutes: Double?
    ): Double {
        if (waitMinutes == null) {
            return 0.5
        }

        return max(
            0.0,
            min(
                1.0,
                1.0 -
                        waitMinutes /
                        WAIT_SCORE_ZERO_MINUTES
            )
        )
    }

    private fun distanceScore(
        distanceMiles: Double
    ): Double {
        return max(
            0.0,
            min(
                1.0,
                1.0 -
                        distanceMiles /
                        MAX_DISTANCE_SCORE_MILES
            )
        )
    }

    private fun countSavedRestaurants(
        centerLatitude: Double,
        centerLongitude: Double,
        restaurantPoints: List<RestaurantPoint>
    ): Int {
        return restaurantPoints.count { restaurant ->
            distanceMeters(
                centerLatitude,
                centerLongitude,
                restaurant.latitude,
                restaurant.longitude
            ) <=
                    RESTAURANT_COUNT_RADIUS_METERS
        }
    }

    private fun straightLineMiles(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        return distanceMeters(
            lat1,
            lon1,
            lat2,
            lon2
        ) *
                0.000621371
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val results =
            FloatArray(1)

        Location.distanceBetween(
            lat1,
            lon1,
            lat2,
            lon2,
            results
        )

        return results[0]
            .toDouble()
    }

    private fun localCenterLabel(
        placeId: String
    ): String {
        val suffix =
            placeId.takeLast(6)

        return "Center-$suffix"
    }

    private fun hasLocationPermission(): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(
        onResult: (Location?) -> Unit
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
                    onResult(
                        location
                    )
                } else {
                    locationClient
                        .lastLocation
                        .addOnSuccessListener { lastLocation ->
                            onResult(
                                lastLocation
                            )
                        }
                        .addOnFailureListener {
                            onResult(
                                null
                            )
                        }
                }
            }
            .addOnFailureListener {
                locationClient
                    .lastLocation
                    .addOnSuccessListener { lastLocation ->
                        onResult(
                            lastLocation
                        )
                    }
                    .addOnFailureListener {
                        onResult(
                            null
                        )
                    }
            }
    }

    private fun onMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            block()
        } else {
            mainHandler.post(
                block
            )
        }
    }
}