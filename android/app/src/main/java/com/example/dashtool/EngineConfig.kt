package com.example.dashtool

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class EngineGlobalParameters(
    val routeMultiplier: Double,
    val routeFixedDelayMinutes: Double,
    val restaurantWaitMinutes: Double,
    val customerDropoffMinutes: Double,
    val nextOfferWaitMinutes: Double,
    val repositioningMinutes: Double,
    val repositioningMiles: Double
)

data class ScoreThresholds(
    val poor: Double,
    val middle: Double,
    val excellent: Double
)

data class EngineScoringParameters(
    val hourlyRateWeight: Double,
    val dollarsPerMileWeight: Double,
    val netProfitWeight: Double,
    val hourlyRateThresholds: ScoreThresholds,
    val dollarsPerMileThresholds: ScoreThresholds,
    val netProfitThresholds: ScoreThresholds
)

data class FallbackMinutesPerMileBand(
    val throughMiles: Double?,
    val minutesPerMile: Double
)

data class CustomerGoogleWeightBand(
    val throughMiles: Double?,
    val googleWeight: Double
)

data class EngineTravelModel(
    val fallbackMinutesPerMileBands:
        List<FallbackMinutesPerMileBand>,

    val googleMinutesPerMileMinimum: Double,
    val googleMinutesPerMileMaximum: Double,

    val customerGoogleWeightBands:
        List<CustomerGoogleWeightBand>
)

data class EngineFeatures(
    val useServerRouteCorrection: Boolean,
    val useRestaurantSpecificWait: Boolean,
    val includeNextOfferWaitInScore: Boolean,
    val includeRepositioningInScore: Boolean
)

data class RestaurantWaitParameters(
    val restaurantPlaceId: String?,
    val restaurantName: String?,
    val samples: Int,
    val blendedWaitMinutes: Double
)

data class EngineConfig(
    val engineVersion: Int,
    val generatedAt: String?,
    val status: String?,
    val eligibleOrders: Int,
    val usableOrders: Int,
    val global: EngineGlobalParameters,
    val scoring: EngineScoringParameters,
    val travelModel: EngineTravelModel,
    val features: EngineFeatures,
    val restaurantWaits:
        Map<String, RestaurantWaitParameters>,
    val rawJson: String
) {
    fun restaurantWaitMinutes(
        placeId: String?,
        restaurantName: String?
    ): Double {
        if (
            features.useRestaurantSpecificWait &&
            !placeId.isNullOrBlank()
        ) {
            restaurantWaits[placeId]
                ?.let {
                    return it.blendedWaitMinutes
                }
        }

        if (
            features.useRestaurantSpecificWait &&
            !restaurantName.isNullOrBlank()
        ) {
            val nameKey =
                "name:" +
                    restaurantName
                        .trim()
                        .lowercase()

            restaurantWaits[nameKey]
                ?.let {
                    return it.blendedWaitMinutes
                }
        }

        return global.restaurantWaitMinutes
    }
}

object EngineConfigDefaults {

    fun baseline(): EngineConfig {
        val raw =
            """
            {
              "engine_version": 1,
              "status": "offline_baseline",
              "source": {
                "eligible_orders": 0,
                "usable_orders": 0
              },
              "global": {
                "route_multiplier": 1.0,
                "route_fixed_delay_minutes": 0.0,
                "restaurant_wait_minutes": 4.0,
                "customer_dropoff_minutes": 2.5,
                "next_offer_wait_minutes": 0.0,
                "repositioning_minutes": 0.0,
                "repositioning_miles": 0.0
              },
              "scoring": {
                "hourly_rate_weight": 0.60,
                "dollars_per_mile_weight": 0.25,
                "net_profit_weight": 0.15,
                "hourly_rate_thresholds": {
                  "poor": 10.0,
                  "middle": 20.0,
                  "excellent": 30.0
                },
                "dollars_per_mile_thresholds": {
                  "poor": 0.75,
                  "middle": 1.50,
                  "excellent": 2.25
                },
                "net_profit_thresholds": {
                  "poor": 2.0,
                  "middle": 6.0,
                  "excellent": 10.0
                }
              },
              "travel_model": {
                "fallback_minutes_per_mile_bands": [
                  {
                    "through_miles": 2.0,
                    "minutes_per_mile": 3.0
                  },
                  {
                    "through_miles": 5.0,
                    "minutes_per_mile": 2.6
                  },
                  {
                    "through_miles": 8.0,
                    "minutes_per_mile": 2.2
                  },
                  {
                    "through_miles": 12.0,
                    "minutes_per_mile": 1.8
                  },
                  {
                    "through_miles": null,
                    "minutes_per_mile": 1.6
                  }
                ],
                "google_minutes_per_mile_minimum": 1.5,
                "google_minutes_per_mile_maximum": 4.0,
                "customer_google_weight_bands": [
                  {
                    "through_miles": 2.0,
                    "google_weight": 0.75
                  },
                  {
                    "through_miles": 5.0,
                    "google_weight": 0.65
                  },
                  {
                    "through_miles": 8.0,
                    "google_weight": 0.50
                  },
                  {
                    "through_miles": 12.0,
                    "google_weight": 0.35
                  },
                  {
                    "through_miles": null,
                    "google_weight": 0.25
                  }
                ]
              },
              "features": {
                "use_server_route_correction": true,
                "use_restaurant_specific_wait": true,
                "include_next_offer_wait_in_score": false,
                "include_repositioning_in_score": false
              },
              "restaurant_waits": {}
            }
            """.trimIndent()

        return EngineConfigParser.parse(
            raw
        )
    }
}

object EngineConfigParser {

    fun parse(
        jsonText: String
    ): EngineConfig {
        require(
            jsonText.length <=
                2_000_000
        ) {
            "Engine configuration is unexpectedly large."
        }

        val root =
            JSONObject(
                jsonText
            )

        val engineVersion =
            root.requiredInt(
                "engine_version",
                minimum = 1
            )

        val source =
            root.optJSONObject(
                "source"
            )

        val globalObject =
            root.requiredObject(
                "global"
            )

        val scoringObject =
            root.requiredObject(
                "scoring"
            )

        val travelObject =
            root.requiredObject(
                "travel_model"
            )

        val featureObject =
            root.requiredObject(
                "features"
            )

        val global =
            EngineGlobalParameters(
                routeMultiplier =
                    globalObject.requiredDouble(
                        "route_multiplier",
                        0.25,
                        3.0
                    ),

                routeFixedDelayMinutes =
                    globalObject.requiredDouble(
                        "route_fixed_delay_minutes",
                        -10.0,
                        60.0
                    ),

                restaurantWaitMinutes =
                    globalObject.requiredDouble(
                        "restaurant_wait_minutes",
                        0.0,
                        120.0
                    ),

                customerDropoffMinutes =
                    globalObject.requiredDouble(
                        "customer_dropoff_minutes",
                        0.0,
                        60.0
                    ),

                nextOfferWaitMinutes =
                    globalObject.requiredDouble(
                        "next_offer_wait_minutes",
                        0.0,
                        180.0
                    ),

                repositioningMinutes =
                    globalObject.requiredDouble(
                        "repositioning_minutes",
                        0.0,
                        180.0
                    ),

                repositioningMiles =
                    globalObject.requiredDouble(
                        "repositioning_miles",
                        0.0,
                        200.0
                    )
            )

        val scoring =
            EngineScoringParameters(
                hourlyRateWeight =
                    scoringObject.requiredDouble(
                        "hourly_rate_weight",
                        0.0,
                        1.0
                    ),

                dollarsPerMileWeight =
                    scoringObject.requiredDouble(
                        "dollars_per_mile_weight",
                        0.0,
                        1.0
                    ),

                netProfitWeight =
                    scoringObject.requiredDouble(
                        "net_profit_weight",
                        0.0,
                        1.0
                    ),

                hourlyRateThresholds =
                    scoringObject
                        .requiredObject(
                            "hourly_rate_thresholds"
                        )
                        .thresholds(),

                dollarsPerMileThresholds =
                    scoringObject
                        .requiredObject(
                            "dollars_per_mile_thresholds"
                        )
                        .thresholds(),

                netProfitThresholds =
                    scoringObject
                        .requiredObject(
                            "net_profit_thresholds"
                        )
                        .thresholds()
            )

        val weightTotal =
            scoring.hourlyRateWeight +
                scoring.dollarsPerMileWeight +
                scoring.netProfitWeight

        require(
            weightTotal > 0.0 &&
                abs(
                    weightTotal - 1.0
                ) <= 0.001
        ) {
            "Scoring weights must add to 1.0."
        }

        val travelModel =
            EngineTravelModel(
                fallbackMinutesPerMileBands =
                    parseFallbackBands(
                        travelObject.requiredArray(
                            "fallback_minutes_per_mile_bands"
                        )
                    ),

                googleMinutesPerMileMinimum =
                    travelObject.requiredDouble(
                        "google_minutes_per_mile_minimum",
                        0.1,
                        20.0
                    ),

                googleMinutesPerMileMaximum =
                    travelObject.requiredDouble(
                        "google_minutes_per_mile_maximum",
                        0.1,
                        20.0
                    ),

                customerGoogleWeightBands =
                    parseGoogleWeightBands(
                        travelObject.requiredArray(
                            "customer_google_weight_bands"
                        )
                    )
            )

        require(
            travelModel.googleMinutesPerMileMinimum <=
                travelModel.googleMinutesPerMileMaximum
        ) {
            "Google minutes-per-mile bounds are reversed."
        }

        val features =
            EngineFeatures(
                useServerRouteCorrection =
                    featureObject.requiredBoolean(
                        "use_server_route_correction"
                    ),

                useRestaurantSpecificWait =
                    featureObject.requiredBoolean(
                        "use_restaurant_specific_wait"
                    ),

                includeNextOfferWaitInScore =
                    featureObject.requiredBoolean(
                        "include_next_offer_wait_in_score"
                    ),

                includeRepositioningInScore =
                    featureObject.requiredBoolean(
                        "include_repositioning_in_score"
                    )
            )

        val restaurantWaits =
            parseRestaurantWaits(
                root.optJSONObject(
                    "restaurant_waits"
                ) ?: JSONObject()
            )

        return EngineConfig(
            engineVersion =
                engineVersion,

            generatedAt =
                root.optNullableString(
                    "generated_at"
                ),

            status =
                root.optNullableString(
                    "status"
                ),

            eligibleOrders =
                source?.optInt(
                    "eligible_orders",
                    0
                ) ?: 0,

            usableOrders =
                source?.optInt(
                    "usable_orders",
                    0
                ) ?: 0,

            global =
                global,

            scoring =
                scoring,

            travelModel =
                travelModel,

            features =
                features,

            restaurantWaits =
                restaurantWaits,

            rawJson =
                root.toString()
        )
    }

    private fun JSONObject.thresholds():
            ScoreThresholds {
        val thresholds =
            ScoreThresholds(
                poor =
                    requiredDouble(
                        "poor",
                        -10_000.0,
                        10_000.0
                    ),

                middle =
                    requiredDouble(
                        "middle",
                        -10_000.0,
                        10_000.0
                    ),

                excellent =
                    requiredDouble(
                        "excellent",
                        -10_000.0,
                        10_000.0
                    )
            )

        require(
            thresholds.poor <
                thresholds.middle &&
                thresholds.middle <
                thresholds.excellent
        ) {
            "Score thresholds must be strictly increasing."
        }

        return thresholds
    }

    private fun parseFallbackBands(
        array: JSONArray
    ): List<FallbackMinutesPerMileBand> {
        require(
            array.length() > 0
        ) {
            "Fallback travel bands cannot be empty."
        }

        val result =
            mutableListOf<
                FallbackMinutesPerMileBand
            >()

        for (
            index in
                0 until array.length()
        ) {
            val item =
                array.getJSONObject(
                    index
                )

            result +=
                FallbackMinutesPerMileBand(
                    throughMiles =
                        item.optNullableDouble(
                            "through_miles"
                        ),

                    minutesPerMile =
                        item.requiredDouble(
                            "minutes_per_mile",
                            0.1,
                            20.0
                        )
                )
        }

        validateBandOrder(
            result.map {
                it.throughMiles
            }
        )

        return result
    }

    private fun parseGoogleWeightBands(
        array: JSONArray
    ): List<CustomerGoogleWeightBand> {
        require(
            array.length() > 0
        ) {
            "Google weight bands cannot be empty."
        }

        val result =
            mutableListOf<
                CustomerGoogleWeightBand
            >()

        for (
            index in
                0 until array.length()
        ) {
            val item =
                array.getJSONObject(
                    index
                )

            result +=
                CustomerGoogleWeightBand(
                    throughMiles =
                        item.optNullableDouble(
                            "through_miles"
                        ),

                    googleWeight =
                        item.requiredDouble(
                            "google_weight",
                            0.0,
                            1.0
                        )
                )
        }

        validateBandOrder(
            result.map {
                it.throughMiles
            }
        )

        return result
    }

    private fun validateBandOrder(
        limits: List<Double?>
    ) {
        var previous =
            Double.NEGATIVE_INFINITY

        limits.forEachIndexed {
                index,
                limit ->

            if (
                limit == null
            ) {
                require(
                    index ==
                        limits.lastIndex
                ) {
                    "Only the final travel band may have no upper limit."
                }

                return@forEachIndexed
            }

            require(
                limit > previous
            ) {
                "Travel-band limits must be strictly increasing."
            }

            previous =
                limit
        }

        require(
            limits.last() == null
        ) {
            "The final travel band must have no upper limit."
        }
    }

    private fun parseRestaurantWaits(
        objectValue: JSONObject
    ): Map<String, RestaurantWaitParameters> {
        val waits =
            linkedMapOf<
                String,
                RestaurantWaitParameters
            >()

        val keys =
            objectValue.keys()

        while (
            keys.hasNext()
        ) {
            val key =
                keys.next()

            val value =
                objectValue.optJSONObject(
                    key
                ) ?: continue

            val blended =
                value.optFiniteDouble(
                    "blended_wait_minutes"
                ) ?: continue

            if (
                blended < 0.0 ||
                blended > 120.0
            ) {
                continue
            }

            waits[key] =
                RestaurantWaitParameters(
                    restaurantPlaceId =
                        value.optNullableString(
                            "restaurant_place_id"
                        ),

                    restaurantName =
                        value.optNullableString(
                            "restaurant_name"
                        ),

                    samples =
                        value.optInt(
                            "samples",
                            0
                        ).coerceAtLeast(
                            0
                        ),

                    blendedWaitMinutes =
                        blended
                )
        }

        return waits
    }

    private fun JSONObject.requiredObject(
        key: String
    ): JSONObject {
        return optJSONObject(
            key
        ) ?: throw IllegalArgumentException(
            "Missing object: $key"
        )
    }

    private fun JSONObject.requiredArray(
        key: String
    ): JSONArray {
        return optJSONArray(
            key
        ) ?: throw IllegalArgumentException(
            "Missing array: $key"
        )
    }

    private fun JSONObject.requiredBoolean(
        key: String
    ): Boolean {
        if (
            !has(
                key
            )
        ) {
            throw IllegalArgumentException(
                "Missing Boolean: $key"
            )
        }

        return getBoolean(
            key
        )
    }

    private fun JSONObject.requiredInt(
        key: String,
        minimum: Int
    ): Int {
        if (
            !has(
                key
            )
        ) {
            throw IllegalArgumentException(
                "Missing integer: $key"
            )
        }

        val value =
            getInt(
                key
            )

        require(
            value >= minimum
        ) {
            "$key must be at least $minimum."
        }

        return value
    }

    private fun JSONObject.requiredDouble(
        key: String,
        minimum: Double,
        maximum: Double
    ): Double {
        val value =
            optFiniteDouble(
                key
            ) ?: throw IllegalArgumentException(
                "Missing or invalid number: $key"
            )

        require(
            value in
                minimum..maximum
        ) {
            "$key is outside its allowed range."
        }

        return value
    }

    private fun JSONObject.optFiniteDouble(
        key: String
    ): Double? {
        if (
            !has(
                key
            ) ||
            isNull(
                key
            )
        ) {
            return null
        }

        val value =
            optDouble(
                key,
                Double.NaN
            )

        return value.takeIf {
            it.isFinite()
        }
    }

    private fun JSONObject.optNullableDouble(
        key: String
    ): Double? {
        return optFiniteDouble(
            key
        )
    }

    private fun JSONObject.optNullableString(
        key: String
    ): String? {
        if (
            !has(
                key
            ) ||
            isNull(
                key
            )
        ) {
            return null
        }

        return optString(
            key
        )
            .trim()
            .takeIf {
                it.isNotEmpty()
            }
    }
}
