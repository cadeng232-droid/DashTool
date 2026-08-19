package com.example.dashtool

/**
 * Final DashTool grading rule.
 *
 * The score is based only on projected net dollars per hour for the complete
 * work cycle. The popup's displayed order $/hr remains separate and excludes
 * post-delivery repositioning/waiting time.
 */
data class EffectiveHourlyScoreResult(
    val score: Double,
    val effectiveHourlyRate: Double,
    val orderFuelCost: Double,
    val postDeliveryFuelCost: Double,
    val effectiveNetEarnings: Double,
    val orderMinutes: Double,
    val postDeliveryDriveMinutes: Double,
    val postDeliveryWaitMinutes: Double,
    val effectiveMinutes: Double
)

object EffectiveHourlyScoreEngine {

    /**
     * Agreed score curve:
     *
     *  $5/hr or less -> 0
     *  $15/hr        -> 5
     *  $30/hr+       -> 10
     *
     * Below $15/hr each $2/hr changes the score by one point.
     * Above $15/hr each $3/hr changes the score by one point.
     */
    fun scoreFromEffectiveHourlyRate(
        hourlyRate: Double
    ): Double {
        if (!hourlyRate.isFinite()) {
            return 0.0
        }

        return when {
            hourlyRate <= 5.0 ->
                0.0

            hourlyRate <= 15.0 ->
                (hourlyRate - 5.0) / 2.0

            hourlyRate < 30.0 ->
                5.0 +
                        (hourlyRate - 15.0) / 3.0

            else ->
                10.0
        }.coerceIn(
            0.0,
            10.0
        )
    }

    fun calculate(
        payout: Double,
        orderDisplayedMiles: Double,
        orderMinutes: Double,
        postDeliveryDriveMinutes: Double,
        postDeliveryDriveMiles: Double,
        postDeliveryWaitMinutes: Double,
        gasPricePerGallon: Double,
        vehicleMpg: Double
    ): EffectiveHourlyScoreResult {
        require(payout >= 0.0) {
            "Payout cannot be negative."
        }

        require(orderDisplayedMiles >= 0.0) {
            "Order miles cannot be negative."
        }

        require(orderMinutes >= 0.0) {
            "Order minutes cannot be negative."
        }

        require(gasPricePerGallon >= 0.0) {
            "Gas price cannot be negative."
        }

        require(vehicleMpg > 0.0) {
            "Vehicle MPG must be greater than zero."
        }

        val safePostDriveMinutes =
            postDeliveryDriveMinutes
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0.0)
                ?: 0.0

        val safePostDriveMiles =
            postDeliveryDriveMiles
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0.0)
                ?: 0.0

        val safePostWaitMinutes =
            postDeliveryWaitMinutes
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0.0)
                ?: 0.0

        val orderFuelCost =
            (orderDisplayedMiles / vehicleMpg) *
                    gasPricePerGallon

        val postDeliveryFuelCost =
            (safePostDriveMiles / vehicleMpg) *
                    gasPricePerGallon

        val effectiveNetEarnings =
            payout -
                    orderFuelCost -
                    postDeliveryFuelCost

        val effectiveMinutes =
            orderMinutes +
                    safePostDriveMinutes +
                    safePostWaitMinutes

        val effectiveHourlyRate =
            if (
                effectiveMinutes > 0.0 &&
                effectiveNetEarnings > 0.0
            ) {
                effectiveNetEarnings /
                        effectiveMinutes *
                        60.0
            } else {
                0.0
            }

        return EffectiveHourlyScoreResult(
            score =
                scoreFromEffectiveHourlyRate(
                    effectiveHourlyRate
                ),
            effectiveHourlyRate =
                effectiveHourlyRate,
            orderFuelCost =
                orderFuelCost,
            postDeliveryFuelCost =
                postDeliveryFuelCost,
            effectiveNetEarnings =
                effectiveNetEarnings,
            orderMinutes =
                orderMinutes,
            postDeliveryDriveMinutes =
                safePostDriveMinutes,
            postDeliveryWaitMinutes =
                safePostWaitMinutes,
            effectiveMinutes =
                effectiveMinutes
        )
    }
}