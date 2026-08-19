package com.example.dashtool

import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * On-device customer-address capture + map-prediction diagnostic.
 *
 * Privacy:
 * - The raw street address stays in memory only.
 * - It is NOT uploaded to the DashTool server.
 * - Existing AT_CUSTOMER phone GPS remains the learning ground truth.
 *
 * The address coordinate is a secondary diagnostic because Android geocoding is
 * best-effort and can resolve to a building / parcel / road centroid rather than
 * the exact DoorDash stop point.
 */
object CustomerAddressTracker {

    private const val LOG_TAG =
        "DashToolCustomerAddress"

    data class Result(
        val offerId: String,
        val addressText: String,
        val geocodedLatitude: Double?,
        val geocodedLongitude: Double?,
        val rawPredictionErrorMeters: Double?,
        val correctedPredictionErrorMeters: Double?,
        val geocodeSucceeded: Boolean
    ) {
        fun overlaySummary(): String {
            val primaryError =
                correctedPredictionErrorMeters
                    ?: rawPredictionErrorMeters

            val checkText =
                when {
                    primaryError != null ->
                        "MAP CHECK  •  ${primaryError.roundToInt()} m from address"

                    geocodeSucceeded ->
                        "MAP CHECK  •  address resolved; no linked prediction"

                    else ->
                        "MAP CHECK  •  address captured; geocode unavailable"
                }

            return addressText +
                    "\n" +
                    checkText
        }
    }

    private data class CachedAddress(
        val addressText: String,
        val result: Result?
    )

    private val lock =
        Any()

    private val cacheByOffer =
        mutableMapOf<String, CachedAddress>()

    private val inFlightByOffer =
        mutableMapOf<String, String>()

    /*
     * US-style street suffixes commonly exposed by DoorDash.
     * This is deliberately conservative so mileage, times, customer names,
     * apartment instructions, etc. do not become navigation destinations.
     */
    private val streetAddressRegex =
        Regex(
            pattern =
                """(?i)^\s*\d{1,6}[A-Za-z]?(?:[-/]\d+)?\s+""" +
                        """.{1,70}\b""" +
                        """(?:street|st|road|rd|drive|dr|avenue|ave|""" +
                        """boulevard|blvd|lane|ln|court|ct|circle|cir|""" +
                        """way|place|pl|parkway|pkwy|highway|hwy|""" +
                        """trail|trl|terrace|ter|loop|square|sq)\b.*$"""
        )

    private val cityStateZipRegex =
        Regex(
            """(?i)^[A-Za-z .'\-]{2,50},?\s+[A-Z]{2}(?:\s+\d{5}(?:-\d{4})?)?$"""
        )

    private val unitRegex =
        Regex(
            """(?i)^(?:apt|apartment|unit|suite|ste|#)\s*[-A-Za-z0-9]+.*$"""
        )

    fun captureFromAccessibilityText(
        context: android.content.Context,
        offerId: String,
        lifecycleStage: String,
        nodeText: String,
        onResult: (Result) -> Unit
    ) {
        if (
            lifecycleStage !=
            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_CUSTOMER &&
            lifecycleStage !=
            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER
        ) {
            return
        }

        val addressText =
            extractCustomerAddress(
                nodeText
            )
                ?: return

        synchronized(
            lock
        ) {
            val cached =
                cacheByOffer[
                    offerId
                ]

            if (
                cached?.addressText ==
                addressText &&
                cached.result !=
                null
            ) {
                onResult(
                    cached.result
                )
                return
            }

            if (
                inFlightByOffer[
                    offerId
                ] ==
                addressText
            ) {
                return
            }

            inFlightByOffer[
                offerId
            ] =
                addressText
        }

        Log.d(
            LOG_TAG,
            "Captured customer address for $offerId: $addressText"
        )

        resolveAddress(
            context =
                context.applicationContext,
            addressText =
                addressText
        ) {
                latitude,
                longitude ->

            val result =
                buildResult(
                    context =
                        context.applicationContext,
                    offerId =
                        offerId,
                    addressText =
                        addressText,
                    latitude =
                        latitude,
                    longitude =
                        longitude
                )

            synchronized(
                lock
            ) {
                inFlightByOffer.remove(
                    offerId
                )

                cacheByOffer[
                    offerId
                ] =
                    CachedAddress(
                        addressText =
                            addressText,
                        result =
                            result
                    )
            }

            logResult(
                result
            )

            onResult(
                result
            )
        }
    }

    fun clear(
        offerId: String
    ) {
        synchronized(
            lock
        ) {
            cacheByOffer.remove(
                offerId
            )

            inFlightByOffer.remove(
                offerId
            )
        }
    }

    private fun extractCustomerAddress(
        nodeText: String
    ): String? {
        val lines =
            nodeText
                .lines()
                .map {
                        line ->

                    line
                        .replace(
                            Regex(
                                """\s+"""
                            ),
                            " "
                        )
                        .trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        if (
            lines.isEmpty()
        ) {
            return null
        }

        /*
         * Prefer a street address that appears after DoorDash's customer-leg
         * labels. Fall back to the first strong street-address candidate.
         */
        val customerLabelIndex =
            lines.indexOfFirst {
                    line ->

                val lower =
                    line.lowercase(
                        Locale.US
                    )

                lower.contains(
                    "deliver to"
                ) ||
                        lower.contains(
                            "dropoff"
                        ) ||
                        lower.contains(
                            "drop off"
                        )
            }

        val candidateIndices =
            buildList {
                if (
                    customerLabelIndex >=
                    0
                ) {
                    for (
                    index in
                    customerLabelIndex + 1 until
                            lines.size
                    ) {
                        add(
                            index
                        )
                    }
                }

                for (
                index in
                lines.indices
                ) {
                    if (
                        index <=
                        customerLabelIndex
                    ) {
                        add(
                            index
                        )
                    }
                }
            }

        val streetIndex =
            candidateIndices
                .firstOrNull {
                        index ->

                    streetAddressRegex.matches(
                        lines[
                            index
                        ]
                    )
                }
                ?: return null

        val pieces =
            mutableListOf(
                lines[
                    streetIndex
                ]
            )

        var nextIndex =
            streetIndex +
                    1

        if (
            nextIndex <
            lines.size &&
            unitRegex.matches(
                lines[
                    nextIndex
                ]
            )
        ) {
            pieces.add(
                lines[
                    nextIndex
                ]
            )

            nextIndex +=
                1
        }

        if (
            nextIndex <
            lines.size &&
            cityStateZipRegex.matches(
                lines[
                    nextIndex
                ]
            )
        ) {
            pieces.add(
                lines[
                    nextIndex
                ]
            )
        }

        return pieces
            .joinToString(
                separator =
                    ", "
            )
            .take(
                180
            )
    }

    private fun resolveAddress(
        context: android.content.Context,
        addressText: String,
        onComplete: (
            latitude: Double?,
            longitude: Double?
        ) -> Unit
    ) {
        if (
            !Geocoder.isPresent()
        ) {
            onComplete(
                null,
                null
            )
            return
        }

        val geocoder =
            Geocoder(
                context,
                Locale.US
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            runCatching {
                geocoder.getFromLocationName(
                    addressText,
                    1,
                    object :
                        Geocoder.GeocodeListener {

                        override fun onGeocode(
                            addresses: MutableList<Address>
                        ) {
                            val first =
                                addresses
                                    .firstOrNull()

                            onComplete(
                                first
                                    ?.latitude,
                                first
                                    ?.longitude
                            )
                        }

                        override fun onError(
                            errorMessage: String?
                        ) {
                            Log.w(
                                LOG_TAG,
                                "Address geocode failed: " +
                                        (
                                                errorMessage
                                                    ?: "unknown error"
                                                )
                            )

                            onComplete(
                                null,
                                null
                            )
                        }
                    }
                )
            }.onFailure {
                    exception ->

                Log.w(
                    LOG_TAG,
                    "Could not start address geocode.",
                    exception
                )

                onComplete(
                    null,
                    null
                )
            }

            return
        }

        /*
         * API < 33 has only the blocking overload. Keep it off the main thread.
         */
        Thread {
            @Suppress(
                "DEPRECATION"
            )
            val first =
                runCatching {
                    geocoder
                        .getFromLocationName(
                            addressText,
                            1
                        )
                        ?.firstOrNull()
                }.getOrElse {
                        exception ->

                    Log.w(
                        LOG_TAG,
                        "Address geocode failed.",
                        exception
                    )

                    null
                }

            onComplete(
                first
                    ?.latitude,
                first
                    ?.longitude
            )
        }.start()
    }

    private fun buildResult(
        context: android.content.Context,
        offerId: String,
        addressText: String,
        latitude: Double?,
        longitude: Double?
    ): Result {
        if (
            latitude == null ||
            longitude == null
        ) {
            return Result(
                offerId =
                    offerId,
                addressText =
                    addressText,
                geocodedLatitude =
                    null,
                geocodedLongitude =
                    null,
                rawPredictionErrorMeters =
                    null,
                correctedPredictionErrorMeters =
                    null,
                geocodeSucceeded =
                    false
            )
        }

        val prediction =
            CustomerMapLearningManager
                .latestPredictionForOffer(
                    context =
                        context,
                    offerId =
                        offerId
                )

        val rawError =
            prediction?.let {
                    value ->

                distanceMeters(
                    firstLatitude =
                        value.rawLatitude,
                    firstLongitude =
                        value.rawLongitude,
                    secondLatitude =
                        latitude,
                    secondLongitude =
                        longitude
                )
            }

        val correctedError =
            prediction?.let {
                    value ->

                distanceMeters(
                    firstLatitude =
                        value.correctedLatitude,
                    firstLongitude =
                        value.correctedLongitude,
                    secondLatitude =
                        latitude,
                    secondLongitude =
                        longitude
                )
            }

        return Result(
            offerId =
                offerId,
            addressText =
                addressText,
            geocodedLatitude =
                latitude,
            geocodedLongitude =
                longitude,
            rawPredictionErrorMeters =
                rawError,
            correctedPredictionErrorMeters =
                correctedError,
            geocodeSucceeded =
                true
        )
    }

    private fun distanceMeters(
        firstLatitude: Double,
        firstLongitude: Double,
        secondLatitude: Double,
        secondLongitude: Double
    ): Double {
        val result =
            FloatArray(
                1
            )

        Location.distanceBetween(
            firstLatitude,
            firstLongitude,
            secondLatitude,
            secondLongitude,
            result
        )

        return result[
            0
        ].toDouble()
    }

    private fun logResult(
        result: Result
    ) {
        val coordinateText =
            if (
                result.geocodedLatitude !=
                null &&
                result.geocodedLongitude !=
                null
            ) {
                String.format(
                    Locale.US,
                    "(%.7f,%.7f)",
                    result.geocodedLatitude,
                    result.geocodedLongitude
                )
            } else {
                "unavailable"
            }

        val rawErrorText =
            result.rawPredictionErrorMeters
                ?.let {
                        value ->

                    String.format(
                        Locale.US,
                        "%.1fm",
                        value
                    )
                }
                ?: "n/a"

        val correctedErrorText =
            result.correctedPredictionErrorMeters
                ?.let {
                        value ->

                    String.format(
                        Locale.US,
                        "%.1fm",
                        value
                    )
                }
                ?: "n/a"

        Log.d(
            LOG_TAG,
            "ADDRESS_CHECK offer=${result.offerId} " +
                    "geocode=$coordinateText " +
                    "rawError=$rawErrorText " +
                    "correctedError=$correctedErrorText"
        )
    }
}