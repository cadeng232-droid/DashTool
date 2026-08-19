package com.example.dashtool

import org.junit.Test
import java.util.Locale

class OrderRatingEngineTest {

    private data class TestOrder(
        val payout: Double,
        val miles: Double
    )

    @Test
    fun printRatingTable() {
        val gasPrice = 4.40
        val vehicleMpg = 30.0

        val testOrders =
            listOf(
                TestOrder(3.00, 0.5),
                TestOrder(5.00, 1.0),
                TestOrder(5.75, 0.7),
                TestOrder(7.00, 2.7),
                TestOrder(8.00, 4.0),
                TestOrder(10.00, 5.0),
                TestOrder(12.00, 8.0),
                TestOrder(15.00, 10.0),
                TestOrder(20.00, 15.0),
                TestOrder(4.00, 5.0),
                TestOrder(8.00, 10.0),
                TestOrder(15.00, 12.0)
            )

        println()
        println(
            "Payout | Miles | Fuel | Net Profit | " +
                    "Minutes | Net $/hr | Net $/mi | Score"
        )

        println(
            "------------------------------------------------" +
                    "--------------------------------"
        )

        testOrders.forEach { order ->

            val result =
                OrderRatingEngine.calculate(
                    OrderRatingInput(
                        payout = order.payout,
                        displayedMiles = order.miles,
                        gasPricePerGallon = gasPrice,
                        vehicleMpg = vehicleMpg
                    )
                )

            println(
                String.format(
                    Locale.US,
                    "$%6.2f | %5.1f | $%4.2f | $%10.2f | " +
                            "%7.2f | $%8.2f | $%7.2f | %4.1f/10",
                    order.payout,
                    order.miles,
                    result.fuelCost,
                    result.netProfit,
                    result.estimatedMinutes,
                    result.netHourlyRate,
                    result.netDollarsPerMile,
                    result.finalScore
                )
            )
        }
    }
}