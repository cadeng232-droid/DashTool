package com.example.dashtool

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the exact decision context that existed when an offer was first
 * saved. This deliberately avoids a Room migration: CompletedOrderExporter
 * later embeds this snapshot into the normal order JSON, while specialized
 * restaurant/customer/waiting samples remain in their own server tables.
 */
object OrderDecisionTelemetryStore {

    private const val LOG_TAG =
        "DashToolTelemetry"

    private const val TELEMETRY_SCHEMA_VERSION =
        1

    private const val DIRECTORY_NAME =
        "order_decision_telemetry"

    private const val MAX_LOCAL_SNAPSHOTS =
        500

    fun recordIfAbsent(
        context: Context,
        offerId: String,
        detectedAtWallTimeMs: Long,
        detectionSource: String?,
        restaurantName: String,
        restaurantPlaceId: String?,
        payout: Double,
        displayedMiles: Double,
        gasPricePerGallon: Double,
        vehicleMpg: Double,
        ratingResult: OrderRatingResult,
        restaurantWaitMinutes: Double?,
        finalScoreShown: Double,
        demandScoreAdjustment: Double,
        demandEstimate: DoorDashDemandTracker.DemandEstimate?,
        routeCapturedAtWallTimeMs: Long?,
        timeToRestaurantMinutes: Double?,
        distanceToRestaurantMiles: Double?,
        routeSource: String,
        routeStatus: String,
        waitingRecommendation: WaitingAreaRecommender.Recommendation?,
        waitingRecommendationCapturedAtWallTimeMs: Long?
    ) {
        runCatching {
            val directory =
                telemetryDirectory(
                    context
                )

            val destination =
                File(
                    directory,
                    "${safeFileName(offerId)}.json"
                )

            /*
             * Duplicate scans of the same offer must not rewrite the original
             * decision context with later demand calibration or a newer waiting
             * recommendation.
             */
            if (destination.exists()) {
                return
            }

            val payload =
                JSONObject().apply {
                    put(
                        "telemetry_schema_version",
                        TELEMETRY_SCHEMA_VERSION
                    )

                    put(
                        "offer_id",
                        offerId
                    )

                    put(
                        "captured_at_wall_time_ms",
                        detectedAtWallTimeMs
                    )

                    putNullable(
                        key = "detection_source",
                        value = detectionSource
                    )

                    put(
                        "restaurant",
                        JSONObject().apply {
                            put(
                                "name",
                                restaurantName
                            )

                            putNullable(
                                key = "place_id",
                                value = restaurantPlaceId
                            )
                        }
                    )

                    put(
                        "scoring",
                        JSONObject().apply {
                            put(
                                "engine_version",
                                ratingResult.engineVersion
                            )

                            put(
                                "payout",
                                payout
                            )

                            put(
                                "displayed_miles",
                                displayedMiles
                            )

                            put(
                                "gas_price_per_gallon",
                                gasPricePerGallon
                            )

                            put(
                                "vehicle_mpg",
                                vehicleMpg
                            )

                            putOptionalDouble(
                                key = "restaurant_wait_minutes_input",
                                value = restaurantWaitMinutes
                            )

                            put(
                                "base_score_before_demand",
                                ratingResult.finalScore
                            )

                            put(
                                "demand_score_adjustment",
                                demandScoreAdjustment
                            )

                            put(
                                "final_score_shown",
                                finalScoreShown
                            )

                            put(
                                "estimated_completion_minutes",
                                ratingResult.estimatedMinutes
                            )

                            put(
                                "estimated_net_hourly_rate",
                                ratingResult.netHourlyRate
                            )
                        }
                    )

                    put(
                        "route_at_offer",
                        JSONObject().apply {
                            putOptionalLong(
                                key = "captured_at_wall_time_ms",
                                value = routeCapturedAtWallTimeMs
                            )

                            putOptionalDouble(
                                key = "eta_minutes",
                                value = timeToRestaurantMinutes
                            )

                            putOptionalDouble(
                                key = "distance_miles",
                                value = distanceToRestaurantMiles
                            )

                            put(
                                "source",
                                routeSource
                            )

                            put(
                                "status",
                                routeStatus
                            )
                        }
                    )

                    put(
                        "demand",
                        demandEstimate?.let {
                                estimate ->

                            JSONObject().apply {
                                put(
                                    "range_min_minutes",
                                    estimate.range.minimumMinutes
                                )

                                put(
                                    "range_max_minutes",
                                    estimate.range.maximumMinutes
                                )

                                put(
                                    "range_midpoint_minutes",
                                    estimate.range.midpointMinutes
                                )

                                put(
                                    "range_observed_at_wall_time_ms",
                                    estimate.range.observedAtWallTimeMs
                                )

                                put(
                                    "learned_correction_minutes",
                                    estimate.learnedCorrectionMinutes
                                )

                                put(
                                    "calibrated_expected_wait_minutes",
                                    estimate.calibratedExpectedWaitMinutes
                                )

                                put(
                                    "calibration_samples",
                                    estimate.calibrationSamples
                                )

                                put(
                                    "calibration_confidence",
                                    estimate.calibrationConfidence
                                )

                                put(
                                    "score_adjustment",
                                    estimate.scoreAdjustment
                                )
                            }
                        } ?: JSONObject.NULL
                    )

                    put(
                        "waiting_area_context",
                        waitingRecommendation?.let {
                                recommendation ->

                            JSONObject().apply {
                                putOptionalLong(
                                    key = "recommendation_captured_at_wall_time_ms",
                                    value = waitingRecommendationCapturedAtWallTimeMs
                                )

                                put(
                                    "recommended_center",
                                    centerToJson(
                                        recommendation.recommendedCenter
                                    )
                                )

                                put(
                                    "distance_miles",
                                    recommendation.distanceMiles
                                )

                                put(
                                    "saved_restaurant_count",
                                    recommendation.recommendedCenter.restaurantCount
                                )

                                putOptionalDouble(
                                    key = "historical_wait_minutes",
                                    value = recommendation.historicalWaitMinutes
                                )

                                put(
                                    "historical_wait_samples",
                                    recommendation.historicalWaitSamples
                                )

                                put(
                                    "score_out_of_100",
                                    recommendation.scoreOutOf100
                                )

                                put(
                                    "restaurant_component_score",
                                    recommendation.restaurantScore
                                )

                                put(
                                    "historical_wait_component_score",
                                    recommendation.historicalWaitScore
                                )

                                put(
                                    "distance_component_score",
                                    recommendation.distanceScore
                                )

                                val candidates =
                                    JSONArray()

                                recommendation.candidateCenters
                                    .forEach {
                                            center ->

                                        candidates.put(
                                            centerToJson(
                                                center
                                            )
                                        )
                                    }

                                put(
                                    "candidate_centers",
                                    candidates
                                )
                            }
                        } ?: JSONObject.NULL
                    )
                }

            val temporary =
                File(
                    directory,
                    "${destination.name}.tmp"
                )

            temporary.writeText(
                payload.toString()
            )

            if (
                !temporary.renameTo(
                    destination
                )
            ) {
                destination.writeText(
                    payload.toString()
                )

                temporary.delete()
            }

            pruneOldSnapshots(
                directory
            )

            Log.d(
                LOG_TAG,
                "Saved decision telemetry for $offerId."
            )
        }.onFailure {
                exception ->

            Log.e(
                LOG_TAG,
                "Could not save decision telemetry for $offerId.",
                exception
            )
        }
    }

    fun load(
        context: Context,
        offerId: String
    ): JSONObject? {
        return runCatching {
            val file =
                File(
                    telemetryDirectory(
                        context
                    ),
                    "${safeFileName(offerId)}.json"
                )

            if (
                !file.exists()
            ) {
                return null
            }

            JSONObject(
                file.readText()
            )
        }.onFailure {
                exception ->

            Log.w(
                LOG_TAG,
                "Could not load decision telemetry for $offerId.",
                exception
            )
        }.getOrNull()
    }

    private fun telemetryDirectory(
        context: Context
    ): File {
        return File(
            context.applicationContext.filesDir,
            DIRECTORY_NAME
        ).apply {
            mkdirs()
        }
    }

    private fun pruneOldSnapshots(
        directory: File
    ) {
        val files =
            directory
                .listFiles {
                        file ->

                    file.isFile &&
                            file.extension.equals(
                                "json",
                                ignoreCase = true
                            )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                ?: return

        files.drop(
            MAX_LOCAL_SNAPSHOTS
        ).forEach {
            it.delete()
        }
    }

    private fun centerToJson(
        center: WaitingAreaTracker.WaitingCenter
    ): JSONObject {
        return JSONObject().apply {
            put(
                "center_id",
                center.centerId
            )

            put(
                "center_name",
                center.centerName
            )

            put(
                "latitude",
                center.latitude
            )

            put(
                "longitude",
                center.longitude
            )

            put(
                "restaurant_count",
                center.restaurantCount
            )
        }
    }

    private fun safeFileName(
        value: String
    ): String {
        return value.replace(
            Regex(
                "[^A-Za-z0-9._-]"
            ),
            "_"
        )
    }

    private fun JSONObject.putNullable(
        key: String,
        value: String?
    ) {
        put(
            key,
            value ?: JSONObject.NULL
        )
    }

    private fun JSONObject.putOptionalDouble(
        key: String,
        value: Double?
    ) {
        put(
            key,
            value?.takeIf {
                it.isFinite()
            } ?: JSONObject.NULL
        )
    }

    private fun JSONObject.putOptionalLong(
        key: String,
        value: Long?
    ) {
        put(
            key,
            value ?: JSONObject.NULL
        )
    }
}