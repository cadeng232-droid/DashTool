package com.example.dashtool

import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

class OrderRatingAnalysisTest {

    companion object {
        private const val GAS_PRICE = 4.40
        private const val VEHICLE_MPG = 30.0
    }

    private data class AnalysisRow(
        val payout: Double,
        val miles: Double,
        val fuelCost: Double,
        val netProfit: Double,
        val estimatedMinutes: Double,
        val netHourlyRate: Double,
        val netDollarsPerMile: Double,
        val hourlyScore: Double,
        val mileageScore: Double,
        val profitScore: Double,
        val finalScore: Double
    )

    /*
     * Add orders here after you decide what score
     * you personally believe each order deserves.
     *
     * actualMinutes is optional but useful after
     * completing a real order.
     */
    private data class LabeledOrder(
        val payout: Double,
        val miles: Double,
        val personalScore: Double,
        val actualMinutes: Double? = null
    )

    private val labeledOrders =
        listOf<LabeledOrder>(
            /*
             * Examples of the format:
             *
             * LabeledOrder(
             *     payout = 7.00,
             *     miles = 2.7,
             *     personalScore = 8.0,
             *     actualMinutes = 15.0
             * )
             */
        )

    @Test
    fun analyzeSyntheticScoreDistribution() {
        val rows =
            generateAnalysisRows()

        verifyBasicRequirements(rows)
        printSummary(rows)
        printHistogram(rows)
        printSuspiciousCases(rows)
        printMonotonicityChecks(rows)
        exportCsv(rows)
    }

    @Test
    fun compareAlgorithmToPersonalRatings() {
        if (labeledOrders.isEmpty()) {
            println()
            println(
                "No personally rated orders have been added yet."
            )
            println(
                "Add LabeledOrder entries to labeledOrders, " +
                        "then run this test again."
            )
            return
        }

        val comparisons =
            labeledOrders.map { order ->

                val result =
                    calculateOrder(
                        payout = order.payout,
                        miles = order.miles
                    )

                val scoreError =
                    result.finalScore -
                            order.personalScore

                Triple(
                    order,
                    result,
                    scoreError
                )
            }

        val meanAbsoluteError =
            comparisons
                .map { (_, _, error) ->
                    abs(error)
                }
                .average()

        val rootMeanSquaredError =
            sqrt(
                comparisons
                    .map { (_, _, error) ->
                        error.pow(2)
                    }
                    .average()
            )

        /*
         * Positive bias means the algorithm generally
         * rates orders higher than you do.
         *
         * Negative bias means it rates them lower.
         */
        val averageBias =
            comparisons
                .map { (_, _, error) ->
                    error
                }
                .average()

        val withinOnePoint =
            comparisons.count { (_, _, error) ->
                abs(error) <= 1.0
            }

        println()
        println("PERSONAL-RATING ACCURACY")
        println("------------------------")

        comparisons.forEach { (order, result, error) ->
            println(
                String.format(
                    Locale.US,
                    "$%.2f for %.1f mi: " +
                            "algorithm %.1f, personal %.1f, " +
                            "error %+.1f",
                    order.payout,
                    order.miles,
                    result.finalScore,
                    order.personalScore,
                    error
                )
            )
        }

        println()
        println(
            String.format(
                Locale.US,
                "Mean absolute error: %.2f points",
                meanAbsoluteError
            )
        )

        println(
            String.format(
                Locale.US,
                "Root mean squared error: %.2f points",
                rootMeanSquaredError
            )
        )

        println(
            String.format(
                Locale.US,
                "Average bias: %+.2f points",
                averageBias
            )
        )

        println(
            String.format(
                Locale.US,
                "Within 1 point: %d/%d (%.1f%%)",
                withinOnePoint,
                comparisons.size,
                withinOnePoint.toDouble() /
                        comparisons.size *
                        100.0
            )
        )

        val ordersWithActualTime =
            comparisons.filter { (order, _, _) ->
                order.actualMinutes != null
            }

        if (ordersWithActualTime.isNotEmpty()) {
            val timeErrors =
                ordersWithActualTime.map { (order, result, _) ->
                    result.estimatedMinutes -
                            order.actualMinutes!!
                }

            val timeMae =
                timeErrors
                    .map(::abs)
                    .average()

            val timeBias =
                timeErrors.average()

            println()
            println(
                String.format(
                    Locale.US,
                    "Estimated-time MAE: %.2f minutes",
                    timeMae
                )
            )

            println(
                String.format(
                    Locale.US,
                    "Estimated-time bias: %+.2f minutes",
                    timeBias
                )
            )
        }
    }

    private fun generateAnalysisRows():
            List<AnalysisRow> {

        val rows =
            mutableListOf<AnalysisRow>()

        /*
         * Payouts from $2.50 through $30.00,
         * increasing by $0.50.
         */
        for (payoutHalfDollars in 5..60) {
            val payout =
                payoutHalfDollars / 2.0

            /*
             * Distances from 0.5 through 20 miles,
             * increasing by 0.5 mile.
             */
            for (halfMiles in 1..40) {
                val miles =
                    halfMiles / 2.0

                val result =
                    calculateOrder(
                        payout = payout,
                        miles = miles
                    )

                rows +=
                    AnalysisRow(
                        payout = payout,
                        miles = miles,
                        fuelCost = result.fuelCost,
                        netProfit = result.netProfit,
                        estimatedMinutes =
                            result.estimatedMinutes,
                        netHourlyRate =
                            result.netHourlyRate,
                        netDollarsPerMile =
                            result.netDollarsPerMile,
                        hourlyScore =
                            result.hourlyRateScore,
                        mileageScore =
                            result.mileageScore,
                        profitScore =
                            result.profitScore,
                        finalScore =
                            result.finalScore
                    )
            }
        }

        return rows
    }

    private fun calculateOrder(
        payout: Double,
        miles: Double
    ): OrderRatingResult {
        return OrderRatingEngine.calculate(
            OrderRatingInput(
                payout = payout,
                displayedMiles = miles,
                gasPricePerGallon = GAS_PRICE,
                vehicleMpg = VEHICLE_MPG
            )
        )
    }

    private fun verifyBasicRequirements(
        rows: List<AnalysisRow>
    ) {
        rows.forEach { row ->
            require(row.finalScore.isFinite()) {
                "Non-finite score for $row"
            }

            require(row.finalScore in 0.0..10.0) {
                "Score outside 0–10 for $row"
            }

            require(row.estimatedMinutes > 0.0) {
                "Non-positive estimated time for $row"
            }
        }
    }

    private fun printSummary(
        rows: List<AnalysisRow>
    ) {
        val scores =
            rows
                .map { row ->
                    row.finalScore
                }
                .sorted()

        val averageScore =
            scores.average()

        val medianScore =
            if (scores.size % 2 == 0) {
                (
                        scores[scores.size / 2 - 1] +
                                scores[scores.size / 2]
                        ) / 2.0
            } else {
                scores[scores.size / 2]
            }

        val standardDeviation =
            sqrt(
                scores
                    .map { score ->
                        (
                                score -
                                        averageScore
                                ).pow(2)
                    }
                    .average()
            )

        println()
        println("SCORE DISTRIBUTION SUMMARY")
        println("--------------------------")
        println("Orders tested: ${rows.size}")

        println(
            String.format(
                Locale.US,
                "Average score: %.2f",
                averageScore
            )
        )

        println(
            String.format(
                Locale.US,
                "Median score: %.2f",
                medianScore
            )
        )

        println(
            String.format(
                Locale.US,
                "Standard deviation: %.2f",
                standardDeviation
            )
        )

        println(
            String.format(
                Locale.US,
                "Minimum score: %.2f",
                scores.first()
            )
        )

        println(
            String.format(
                Locale.US,
                "Maximum score: %.2f",
                scores.last()
            )
        )

        printScoreRegion(
            label = "Very poor, 0–2",
            rows = rows,
            minimum = 0.0,
            maximum = 2.0
        )

        printScoreRegion(
            label = "Poor, 2–4",
            rows = rows,
            minimum = 2.0,
            maximum = 4.0
        )

        printScoreRegion(
            label = "Average, 4–6",
            rows = rows,
            minimum = 4.0,
            maximum = 6.0
        )

        printScoreRegion(
            label = "Good, 6–8",
            rows = rows,
            minimum = 6.0,
            maximum = 8.0
        )

        printScoreRegion(
            label = "Excellent, 8–10",
            rows = rows,
            minimum = 8.0,
            maximum = 10.000001
        )
    }

    private fun printScoreRegion(
        label: String,
        rows: List<AnalysisRow>,
        minimum: Double,
        maximum: Double
    ) {
        val count =
            rows.count { row ->
                row.finalScore >= minimum &&
                        row.finalScore < maximum
            }

        println(
            String.format(
                Locale.US,
                "%-18s: %4d (%5.1f%%)",
                label,
                count,
                count.toDouble() /
                        rows.size *
                        100.0
            )
        )
    }

    private fun printHistogram(
        rows: List<AnalysisRow>
    ) {
        val histogram =
            IntArray(10)

        rows.forEach { row ->
            val bucket =
                floor(row.finalScore)
                    .toInt()
                    .coerceIn(
                        0,
                        9
                    )

            histogram[bucket]++
        }

        val largestBucket =
            histogram.maxOrNull()
                ?.coerceAtLeast(1)
                ?: 1

        println()
        println("DETAILED HISTOGRAM")
        println("------------------")

        histogram.forEachIndexed { index, count ->
            val barLength =
                (
                        count.toDouble() /
                                largestBucket *
                                40
                        )
                    .toInt()

            val label =
                if (index == 9) {
                    "9.0–10.0"
                } else {
                    "$index.0–${index + 1}.0"
                }

            println(
                String.format(
                    Locale.US,
                    "%-8s | %-40s %4d",
                    label,
                    "#".repeat(barLength),
                    count
                )
            )
        }
    }

    private fun printSuspiciousCases(
        rows: List<AnalysisRow>
    ) {
        println()
        println("POTENTIALLY SUSPICIOUS CASES")
        println("----------------------------")

        val lowPayoutHighScore =
            rows
                .filter { row ->
                    row.payout < 5.00 &&
                            row.finalScore >= 8.0
                }
                .sortedByDescending { row ->
                    row.finalScore
                }

        printCases(
            title = "Payout below $5 but score at least 8",
            rows = lowPayoutHighScore
        )

        val lowMileageValueHighScore =
            rows
                .filter { row ->
                    row.netDollarsPerMile < 1.00 &&
                            row.finalScore >= 7.0
                }
                .sortedByDescending { row ->
                    row.finalScore
                }

        printCases(
            title =
                "Net rate below $1/mi but score at least 7",
            rows = lowMileageValueHighScore
        )

        val highHourlyLowScore =
            rows
                .filter { row ->
                    row.netHourlyRate >= 25.0 &&
                            row.netDollarsPerMile >= 1.50 &&
                            row.finalScore < 5.0
                }
                .sortedBy { row ->
                    row.finalScore
                }

        printCases(
            title =
                "At least $25/hr and $1.50/mi but score below 5",
            rows = highHourlyLowScore
        )

        val longDistanceHighScore =
            rows
                .filter { row ->
                    row.miles >= 15.0 &&
                            row.finalScore >= 8.0
                }
                .sortedByDescending { row ->
                    row.finalScore
                }

        printCases(
            title =
                "At least 15 miles but score at least 8",
            rows = longDistanceHighScore
        )
    }

    private fun printCases(
        title: String,
        rows: List<AnalysisRow>,
        maximumPrinted: Int = 10
    ) {
        println()
        println("$title: ${rows.size}")

        rows
            .take(maximumPrinted)
            .forEach { row ->
                println(
                    String.format(
                        Locale.US,
                        "  $%.2f, %.1f mi → " +
                                "%.1f/10, $%.2f/mi, " +
                                "$%.2f/hr, %.1f min",
                        row.payout,
                        row.miles,
                        row.finalScore,
                        row.netDollarsPerMile,
                        row.netHourlyRate,
                        row.estimatedMinutes
                    )
                )
            }
    }

    private fun printMonotonicityChecks(
        rows: List<AnalysisRow>
    ) {
        /*
         * Holding mileage constant, increasing payout
         * should never lower the score.
         */
        val payoutViolations =
            rows
                .groupBy { row ->
                    row.miles
                }
                .values
                .flatMap { group ->
                    group
                        .sortedBy { row ->
                            row.payout
                        }
                        .zipWithNext()
                        .filter { (lower, higher) ->
                            higher.finalScore + 0.0001 <
                                    lower.finalScore
                        }
                }

        /*
         * Holding payout constant, increasing mileage
         * normally should not improve the score.
         *
         * We report rather than fail the test because
         * your distance-dependent speed model could
         * theoretically create a small exception.
         */
        val distanceViolations =
            rows
                .groupBy { row ->
                    row.payout
                }
                .values
                .flatMap { group ->
                    group
                        .sortedBy { row ->
                            row.miles
                        }
                        .zipWithNext()
                        .filter { (shorter, longer) ->
                            longer.finalScore >
                                    shorter.finalScore +
                                    0.05
                        }
                }

        println()
        println("CONSISTENCY CHECKS")
        println("------------------")
        println(
            "Higher payout lowered score: " +
                    payoutViolations.size
        )

        println(
            "Higher mileage raised score: " +
                    distanceViolations.size
        )

        distanceViolations
            .take(10)
            .forEach { (shorter, longer) ->
                println(
                    String.format(
                        Locale.US,
                        "  $%.2f: %.1f mi = %.2f, " +
                                "%.1f mi = %.2f",
                        shorter.payout,
                        shorter.miles,
                        shorter.finalScore,
                        longer.miles,
                        longer.finalScore
                    )
                )
            }
    }

    private fun exportCsv(
        rows: List<AnalysisRow>
    ) {
        val outputFile =
            File(
                System.getProperty("user.dir"),
                "rating-analysis.csv"
            )

        outputFile.writeText(
            buildString {
                appendLine(
                    "payout,miles,fuel_cost,net_profit," +
                            "estimated_minutes,net_hourly_rate," +
                            "net_dollars_per_mile,hourly_score," +
                            "mileage_score,profit_score," +
                            "final_score,flags"
                )

                rows.forEach { row ->
                    appendLine(
                        String.format(
                            Locale.US,
                            "%.2f,%.1f,%.4f,%.4f," +
                                    "%.4f,%.4f,%.4f,%.4f," +
                                    "%.4f,%.4f,%.4f,%s",
                            row.payout,
                            row.miles,
                            row.fuelCost,
                            row.netProfit,
                            row.estimatedMinutes,
                            row.netHourlyRate,
                            row.netDollarsPerMile,
                            row.hourlyScore,
                            row.mileageScore,
                            row.profitScore,
                            row.finalScore,
                            createFlags(row)
                        )
                    )
                }
            }
        )

        println()
        println("CSV created at:")
        println(outputFile.absolutePath)
    }

    private fun createFlags(
        row: AnalysisRow
    ): String {
        val flags =
            mutableListOf<String>()

        if (
            row.payout < 5.0 &&
            row.finalScore >= 8.0
        ) {
            flags +=
                "LOW_PAYOUT_HIGH_SCORE"
        }

        if (
            row.netDollarsPerMile < 1.0 &&
            row.finalScore >= 7.0
        ) {
            flags +=
                "LOW_RATE_HIGH_SCORE"
        }

        if (
            row.miles >= 15.0 &&
            row.finalScore >= 8.0
        ) {
            flags +=
                "LONG_DISTANCE_HIGH_SCORE"
        }

        if (row.finalScore >= 9.95) {
            flags +=
                "SCORE_SATURATED_AT_10"
        }

        return flags.joinToString("|")
    }
}

