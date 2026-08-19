package com.example.dashtool

import kotlin.math.min

data class OrderRatingInput(
    val payout: Double,
    val displayedMiles: Double,
    val gasPricePerGallon: Double,
    val vehicleMpg: Double,

    val timeToRestaurantMinutes: Double? = null,
    val distanceToRestaurantMiles: Double? = null,

    val restaurantWaitMinutes: Double? = null,
    val dropoffMinutes: Double? = null,

    val repositionMiles: Double = 0.0,
    val repositionMinutes: Double = 0.0
)

data class OrderRatingResult(
    val engineVersion: Int,

    val fuelCost: Double,
    val netProfit: Double,
    val estimatedMinutes: Double,
    val netHourlyRate: Double,
    val netDollarsPerMile: Double,

    val hourlyRateScore: Double,
    val mileageScore: Double,
    val profitScore: Double,

    val finalScore: Double
)

object OrderRatingEngine {

    private const val ROUTE_DISTANCE_TOLERANCE_MILES =
        0.5

    private const val AT_RESTAURANT_DISTANCE_MILES =
        0.25

    private const val AT_RESTAURANT_MAXIMUM_MINUTES =
        2.0

    fun calculate(
        input: OrderRatingInput,
        config: EngineConfig
    ): OrderRatingResult {

        require(input.payout >= 0.0) {
            "Payout cannot be negative."
        }

        require(input.displayedMiles > 0.0) {
            "Displayed miles must be greater than zero."
        }

        require(input.gasPricePerGallon >= 0.0) {
            "Gas price cannot be negative."
        }

        require(input.vehicleMpg > 0.0) {
            "Vehicle MPG must be greater than zero."
        }

        val includeRepositioning =
            config.features.includeRepositioningInScore

        val repositionMiles =
            if (includeRepositioning) {
                input.repositionMiles.coerceAtLeast(0.0) +
                        config.global.repositioningMiles
                            .coerceAtLeast(0.0)
            } else {
                0.0
            }

        val repositionMinutes =
            if (includeRepositioning) {
                input.repositionMinutes.coerceAtLeast(0.0) +
                        config.global.repositioningMinutes
                            .coerceAtLeast(0.0)
            } else {
                0.0
            }

        val nextOfferWaitMinutes =
            if (
                config.features
                    .includeNextOfferWaitInScore
            ) {
                config.global.nextOfferWaitMinutes
                    .coerceAtLeast(0.0)
            } else {
                0.0
            }

        val restaurantWaitMinutes =
            (
                    input.restaurantWaitMinutes
                        ?: config.global
                            .restaurantWaitMinutes
                    )
                .coerceAtLeast(0.0)

        val dropoffMinutes =
            (
                    input.dropoffMinutes
                        ?: config.global
                            .customerDropoffMinutes
                    )
                .coerceAtLeast(0.0)

        val effectiveMiles =
            input.displayedMiles +
                    repositionMiles

        val fuelCost =
            (
                    effectiveMiles /
                            input.vehicleMpg
                    ) *
                    input.gasPricePerGallon

        val netProfit =
            input.payout -
                    fuelCost

        val drivingMinutes =
            calculateDrivingMinutes(
                input =
                    input,

                config =
                    config
            )

        val estimatedMinutes =
            drivingMinutes +
                    restaurantWaitMinutes +
                    dropoffMinutes +
                    repositionMinutes +
                    nextOfferWaitMinutes

        val netHourlyRate =
            if (estimatedMinutes > 0.0) {
                netProfit /
                        estimatedMinutes *
                        60.0
            } else {
                0.0
            }

        val netDollarsPerMile =
            if (effectiveMiles > 0.0) {
                netProfit /
                        effectiveMiles
            } else {
                0.0
            }

        val hourlyRateScore =
            thresholdScore(
                value =
                    netHourlyRate,

                thresholds =
                    config.scoring
                        .hourlyRateThresholds
            )

        val mileageScore =
            thresholdScore(
                value =
                    netDollarsPerMile,

                thresholds =
                    config.scoring
                        .dollarsPerMileThresholds
            )

        val profitScore =
            thresholdScore(
                value =
                    netProfit,

                thresholds =
                    config.scoring
                        .netProfitThresholds
            )

        val weightedScore =
            hourlyRateScore *
                    config.scoring
                        .hourlyRateWeight +
                    mileageScore *
                    config.scoring
                        .dollarsPerMileWeight +
                    profitScore *
                    config.scoring
                        .netProfitWeight

        val finalScore =
            if (netProfit <= 0.0) {
                0.0
            } else {
                weightedScore.coerceIn(
                    0.0,
                    10.0
                )
            }

        return OrderRatingResult(
            engineVersion =
                config.engineVersion,

            fuelCost =
                fuelCost,

            netProfit =
                netProfit,

            estimatedMinutes =
                estimatedMinutes,

            netHourlyRate =
                netHourlyRate,

            netDollarsPerMile =
                netDollarsPerMile,

            hourlyRateScore =
                hourlyRateScore,

            mileageScore =
                mileageScore,

            profitScore =
                profitScore,

            finalScore =
                finalScore
        )
    }

    private fun calculateDrivingMinutes(
        input: OrderRatingInput,
        config: EngineConfig
    ): Double {

        val timeToRestaurant =
            input.timeToRestaurantMinutes

        val distanceToRestaurant =
            input.distanceToRestaurantMiles

        val routeIsPlausible =
            timeToRestaurant != null &&
                    distanceToRestaurant != null &&
                    timeToRestaurant.isFinite() &&
                    distanceToRestaurant.isFinite() &&
                    timeToRestaurant >= 0.0 &&
                    distanceToRestaurant >= 0.0 &&
                    distanceToRestaurant <=
                    input.displayedMiles +
                    ROUTE_DISTANCE_TOLERANCE_MILES

        if (!routeIsPlausible) {
            return estimateBaselineTravelMinutes(
                distanceMiles =
                    input.displayedMiles,

                config =
                    config
            )
        }

        val safeRestaurantDistance =
            distanceToRestaurant!!
                .coerceIn(
                    0.0,
                    input.displayedMiles
                )

        val remainingCustomerDistance =
            (
                    input.displayedMiles -
                            safeRestaurantDistance
                    )
                .coerceAtLeast(0.0)

        val rawRestaurantMinutes =
            timeToRestaurant!!

        val correctedRestaurantMinutes =
            if (
                safeRestaurantDistance <=
                AT_RESTAURANT_DISTANCE_MILES
            ) {
                rawRestaurantMinutes.coerceAtMost(
                    AT_RESTAURANT_MAXIMUM_MINUTES
                )
            } else if (
                config.features
                    .useServerRouteCorrection
            ) {
                (
                        rawRestaurantMinutes *
                                config.global
                                    .routeMultiplier +
                                config.global
                                    .routeFixedDelayMinutes
                        )
                    .coerceAtLeast(0.0)
            } else {
                rawRestaurantMinutes
            }

        /*
         * Use the uncorrected Google traffic rate for
         * the customer leg. The learned fixed delay is
         * specific to reaching the restaurant and should
         * not be duplicated on the customer drive.
         */
        val googleMinutesPerMile =
            if (safeRestaurantDistance > 0.1) {
                (
                        rawRestaurantMinutes /
                                safeRestaurantDistance
                        )
                    .coerceIn(
                        config.travelModel
                            .googleMinutesPerMileMinimum,

                        config.travelModel
                            .googleMinutesPerMileMaximum
                    )
            } else {
                null
            }

        val customerTravelMinutes =
            estimateCustomerTravelMinutes(
                distanceMiles =
                    remainingCustomerDistance,

                googleMinutesPerMile =
                    googleMinutesPerMile,

                config =
                    config
            )

        return correctedRestaurantMinutes +
                customerTravelMinutes
    }

    private fun estimateBaselineTravelMinutes(
        distanceMiles: Double,
        config: EngineConfig
    ): Double {
        val safeDistance =
            distanceMiles.coerceAtLeast(0.0)

        var completedMiles =
            0.0

        var totalMinutes =
            0.0

        for (
        band in
        config.travelModel
            .fallbackMinutesPerMileBands
        ) {
            if (completedMiles >= safeDistance) {
                break
            }

            val upperLimit =
                band.throughMiles
                    ?: safeDistance

            val segmentEnd =
                min(
                    safeDistance,
                    upperLimit
                )

            val segmentMiles =
                (
                        segmentEnd -
                                completedMiles
                        )
                    .coerceAtLeast(0.0)

            totalMinutes +=
                segmentMiles *
                        band.minutesPerMile

            completedMiles =
                segmentEnd
        }

        return totalMinutes
    }

    private fun estimateCustomerTravelMinutes(
        distanceMiles: Double,
        googleMinutesPerMile: Double?,
        config: EngineConfig
    ): Double {
        if (distanceMiles <= 0.0) {
            return 0.0
        }

        val baselineMinutes =
            estimateBaselineTravelMinutes(
                distanceMiles =
                    distanceMiles,

                config =
                    config
            )

        if (googleMinutesPerMile == null) {
            return baselineMinutes
        }

        val googleWeight =
            config.travelModel
                .customerGoogleWeightBands
                .first { band ->
                    band.throughMiles == null ||
                            distanceMiles <=
                            band.throughMiles
                }
                .googleWeight

        val googleBasedMinutes =
            distanceMiles *
                    googleMinutesPerMile

        return googleBasedMinutes *
                googleWeight +
                baselineMinutes *
                (1.0 - googleWeight)
    }

    private fun thresholdScore(
        value: Double,
        thresholds: ScoreThresholds
    ): Double {
        return when {
            value <= thresholds.poor ->
                0.0

            value < thresholds.middle ->
                (
                        value -
                                thresholds.poor
                        ) /
                        (
                                thresholds.middle -
                                        thresholds.poor
                                ) *
                        5.0

            value < thresholds.excellent ->
                5.0 +
                        (
                                value -
                                        thresholds.middle
                                ) /
                        (
                                thresholds.excellent -
                                        thresholds.middle
                                ) *
                        5.0

            else ->
                10.0
        }
            .coerceIn(
                0.0,
                10.0
            )
    }
}
