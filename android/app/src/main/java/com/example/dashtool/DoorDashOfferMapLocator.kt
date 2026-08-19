package com.example.dashtool

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight proof-of-concept detector for the three location markers shown on
 * a DoorDash offer map.
 *
 * This class does NOT use OCR, Google Places, OpenCV, or any network request.
 * It only examines pixels from the full-screen screenshot that DashTool already
 * captures.
 */
object DoorDashOfferMapLocator {

    data class PixelPoint(
        val x: Int,
        val y: Int
    )

    data class PinDetection(
        val bodyCenter: PixelPoint,
        val anchor: PixelPoint,
        val whiteDensity: Double,
        val houseShapeScore: Double
    )

    data class Result(
        val driver: PixelPoint?,
        val restaurant: PixelPoint?,
        val customer: PixelPoint?,
        val pins: List<PinDetection>
    ) {
        fun toLogString(): String {
            fun pointText(point: PixelPoint?): String =
                point?.let { "(${it.x},${it.y})" } ?: "not_found"

            val pinText =
                pins.joinToString(
                    prefix = "[",
                    postfix = "]"
                ) { pin ->
                    "anchor=(${pin.anchor.x},${pin.anchor.y})," +
                            " density=${"%.2f".format(pin.whiteDensity)}," +
                            " house=${"%.2f".format(pin.houseShapeScore)}"
                }

            return "driver=${pointText(driver)} " +
                    "restaurant=${pointText(restaurant)} " +
                    "customer=${pointText(customer)} " +
                    "pins=$pinText"
        }
    }

    private data class WhitePeak(
        val x: Int,
        val y: Int,
        val count: Int,
        val density: Double
    )

    fun locate(bitmap: Bitmap): Result {
        if (
            bitmap.width < 200 ||
            bitmap.height < 300 ||
            bitmap.isRecycled
        ) {
            return Result(
                driver = null,
                restaurant = null,
                customer = null,
                pins = emptyList()
            )
        }

        val width = bitmap.width
        val height = bitmap.height

        val pixels =
            IntArray(
                width * height
            )

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        /*
         * On the current DoorDash offer layout the route map occupies the upper
         * portion of the screen. Keeping the detector inside this band prevents
         * the white offer-card text and Accept button from becoming candidates.
         */
        val mapTop =
            (height * 0.08)
                .roundToInt()
                .coerceIn(
                    0,
                    height - 1
                )

        val mapBottom =
            (height * 0.58)
                .roundToInt()
                .coerceIn(
                    mapTop + 1,
                    height
                )

        val driver =
            findDriverBlueDot(
                pixels = pixels,
                width = width,
                mapTop = mapTop,
                mapBottom = mapBottom
            )

        val pinPeaks =
            findWhitePinPeaks(
                pixels = pixels,
                width = width,
                mapTop = mapTop,
                mapBottom = mapBottom
            )

        val pins =
            pinPeaks.map { peak ->
                buildPinDetection(
                    pixels = pixels,
                    width = width,
                    height = height,
                    peak = peak
                )
            }

        /*
         * The house icon has a much wider dark roof row than the restaurant bag
         * icon. On the reference DoorDash layout this produces a clearly larger
         * houseShapeScore. If the classification is ambiguous we deliberately
         * leave restaurant/customer null rather than guessing.
         */
        val customerCandidates =
            pins.filter {
                it.houseShapeScore >=
                        HOUSE_SCORE_THRESHOLD
            }

        val customer =
            if (
                customerCandidates.size == 1
            ) {
                customerCandidates.first()
            } else {
                null
            }

        val restaurant =
            if (
                customer != null &&
                pins.size == 2
            ) {
                pins.firstOrNull {
                    it !== customer
                }
            } else {
                null
            }

        return Result(
            driver = driver,
            restaurant = restaurant?.anchor,
            customer = customer?.anchor,
            pins = pins
        )
    }

    private fun findDriverBlueDot(
        pixels: IntArray,
        width: Int,
        mapTop: Int,
        mapBottom: Int
    ): PixelPoint? {
        var count =
            0

        var sumX =
            0L

        var sumY =
            0L

        var minX =
            width

        var maxX =
            -1

        var minY =
            mapBottom

        var maxY =
            -1

        for (
        y in
        mapTop until mapBottom
        ) {
            val rowOffset =
                y * width

            for (
            x in
            0 until width
            ) {
                val color =
                    pixels[
                        rowOffset + x
                    ]

                val red =
                    color shr 16 and 0xFF

                val green =
                    color shr 8 and 0xFF

                val blue =
                    color and 0xFF

                if (
                    blue > 180 &&
                    green > 90 &&
                    red < 130 &&
                    blue - green > 45 &&
                    green - red > 10
                ) {
                    count +=
                        1

                    sumX +=
                        x.toLong()

                    sumY +=
                        y.toLong()

                    if (
                        x < minX
                    ) {
                        minX =
                            x
                    }

                    if (
                        x > maxX
                    ) {
                        maxX =
                            x
                    }

                    if (
                        y < minY
                    ) {
                        minY =
                            y
                    }

                    if (
                        y > maxY
                    ) {
                        maxY =
                            y
                    }
                }
            }
        }

        if (
            count < 80 ||
            count > 5_000 ||
            maxX < minX ||
            maxY < minY
        ) {
            return null
        }

        val detectedWidth =
            maxX - minX + 1

        val detectedHeight =
            maxY - minY + 1

        val maximumDotSize =
            max(
                40,
                (width * 0.10)
                    .roundToInt()
            )

        if (
            detectedWidth > maximumDotSize ||
            detectedHeight > maximumDotSize
        ) {
            return null
        }

        return PixelPoint(
            x =
                (
                        sumX.toDouble() /
                                count.toDouble()
                        )
                    .roundToInt(),

            y =
                (
                        sumY.toDouble() /
                                count.toDouble()
                        )
                    .roundToInt()
        )
    }

    private fun findWhitePinPeaks(
        pixels: IntArray,
        width: Int,
        mapTop: Int,
        mapBottom: Int
    ): List<WhitePeak> {
        val mapHeight =
            mapBottom - mapTop

        val integralWidth =
            width + 1

        val integral =
            IntArray(
                integralWidth *
                        (mapHeight + 1)
            )

        for (
        localY in
        0 until mapHeight
        ) {
            var rowRunningTotal =
                0

            val sourceY =
                mapTop + localY

            val sourceOffset =
                sourceY * width

            val integralRow =
                (localY + 1) *
                        integralWidth

            val previousIntegralRow =
                localY *
                        integralWidth

            for (
            x in
            0 until width
            ) {
                if (
                    isNearWhite(
                        pixels[
                            sourceOffset + x
                        ]
                    )
                ) {
                    rowRunningTotal +=
                        1
                }

                integral[
                    integralRow +
                            x + 1
                ] =
                    integral[
                        previousIntegralRow +
                                x + 1
                    ] +
                            rowRunningTotal
            }
        }

        val radius =
            max(
                24,
                (width * 0.045)
                    .roundToInt()
            )

        val step =
            max(
                3,
                width / 180
            )

        val windowSide =
            radius * 2 + 1

        val windowArea =
            windowSide * windowSide

        val minimumWhiteCount =
            (
                    windowArea *
                            MINIMUM_PIN_WHITE_DENSITY
                    )
                .roundToInt()

        val candidates =
            ArrayList<WhitePeak>()

        var y =
            mapTop + radius

        while (
            y <
            mapBottom - radius
        ) {
            var x =
                radius

            while (
                x <
                width - radius
            ) {
                val count =
                    rectangleSum(
                        integral = integral,
                        integralWidth = integralWidth,
                        mapTop = mapTop,
                        x0 = x - radius,
                        y0 = y - radius,
                        x1Exclusive = x + radius + 1,
                        y1Exclusive = y + radius + 1
                    )

                if (
                    count >=
                    minimumWhiteCount
                ) {
                    candidates.add(
                        WhitePeak(
                            x = x,
                            y = y,
                            count = count,
                            density =
                                count.toDouble() /
                                        windowArea.toDouble()
                        )
                    )
                }

                x +=
                    step
            }

            y +=
                step
        }

        candidates.sortByDescending {
            it.count
        }

        val selected =
            ArrayList<WhitePeak>()

        val minimumSeparation =
            max(
                90,
                (width * 0.16)
                    .roundToInt()
            )

        val minimumSeparationSquared =
            minimumSeparation.toLong() *
                    minimumSeparation.toLong()

        for (
        candidate in
        candidates
        ) {
            val farEnough =
                selected.all { existing ->
                    val dx =
                        candidate.x -
                                existing.x

                    val dy =
                        candidate.y -
                                existing.y

                    dx.toLong() *
                            dx.toLong() +
                            dy.toLong() *
                            dy.toLong() >=
                            minimumSeparationSquared
                }

            if (
                !farEnough
            ) {
                continue
            }

            selected.add(
                candidate
            )

            if (
                selected.size ==
                2
            ) {
                break
            }
        }

        return selected
    }

    private fun buildPinDetection(
        pixels: IntArray,
        width: Int,
        height: Int,
        peak: WhitePeak
    ): PinDetection {
        val innerRadius =
            max(
                18,
                (width * 0.034)
                    .roundToInt()
            )

        val x0 =
            (
                    peak.x -
                            innerRadius
                    )
                .coerceAtLeast(
                    0
                )

        val x1 =
            (
                    peak.x +
                            innerRadius
                    )
                .coerceAtMost(
                    width - 1
                )

        val y0 =
            (
                    peak.y -
                            innerRadius
                    )
                .coerceAtLeast(
                    0
                )

        val y1 =
            (
                    peak.y +
                            innerRadius
                    )
                .coerceAtMost(
                    height - 1
                )

        var maximumDarkPixelsInOneRow =
            0

        for (
        y in
        y0..y1
        ) {
            var rowDarkPixels =
                0

            val rowOffset =
                y * width

            for (
            x in
            x0..x1
            ) {
                if (
                    isVeryDark(
                        pixels[
                            rowOffset + x
                        ]
                    )
                ) {
                    rowDarkPixels +=
                        1
                }
            }

            if (
                rowDarkPixels >
                maximumDarkPixelsInOneRow
            ) {
                maximumDarkPixelsInOneRow =
                    rowDarkPixels
            }
        }

        val innerWidth =
            x1 - x0 + 1

        val houseShapeScore =
            if (
                innerWidth > 0
            ) {
                maximumDarkPixelsInOneRow.toDouble() /
                        innerWidth.toDouble()
            } else {
                0.0
            }

        val anchor =
            estimatePinAnchor(
                pixels = pixels,
                width = width,
                height = height,
                centerX = peak.x,
                centerY = peak.y
            )

        return PinDetection(
            bodyCenter =
                PixelPoint(
                    x = peak.x,
                    y = peak.y
                ),

            anchor =
                anchor,

            whiteDensity =
                peak.density,

            houseShapeScore =
                houseShapeScore
        )
    }

    private fun estimatePinAnchor(
        pixels: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int
    ): PixelPoint {
        val halfStripWidth =
            max(
                18,
                (width * 0.043)
                    .roundToInt()
            )

        val x0 =
            (
                    centerX -
                            halfStripWidth
                    )
                .coerceAtLeast(
                    0
                )

        val x1 =
            (
                    centerX +
                            halfStripWidth
                    )
                .coerceAtMost(
                    width - 1
                )

        val maximumSearchY =
            (
                    centerY +
                            (width * 0.12)
                                .roundToInt()
                    )
                .coerceAtMost(
                    height - 1
                )

        var lastStrongWhiteRow =
            centerY

        var weakRowRun =
            0

        for (
        y in
        centerY..maximumSearchY
        ) {
            var whiteCount =
                0

            val rowOffset =
                y * width

            for (
            x in
            x0..x1
            ) {
                if (
                    isNearWhite(
                        pixels[
                            rowOffset + x
                        ]
                    )
                ) {
                    whiteCount +=
                        1
                }
            }

            if (
                whiteCount >=
                2
            ) {
                lastStrongWhiteRow =
                    y

                weakRowRun =
                    0
            } else {
                weakRowRun +=
                    1

                if (
                    weakRowRun >=
                    4
                ) {
                    break
                }
            }
        }

        return PixelPoint(
            x = centerX,
            y = lastStrongWhiteRow
        )
    }

    private fun rectangleSum(
        integral: IntArray,
        integralWidth: Int,
        mapTop: Int,
        x0: Int,
        y0: Int,
        x1Exclusive: Int,
        y1Exclusive: Int
    ): Int {
        val localY0 =
            y0 - mapTop

        val localY1 =
            y1Exclusive - mapTop

        val topLeft =
            integral[
                localY0 *
                        integralWidth +
                        x0
            ]

        val topRight =
            integral[
                localY0 *
                        integralWidth +
                        x1Exclusive
            ]

        val bottomLeft =
            integral[
                localY1 *
                        integralWidth +
                        x0
            ]

        val bottomRight =
            integral[
                localY1 *
                        integralWidth +
                        x1Exclusive
            ]

        return bottomRight -
                bottomLeft -
                topRight +
                topLeft
    }

    private fun isNearWhite(
        color: Int
    ): Boolean {
        val red =
            color shr 16 and 0xFF

        val green =
            color shr 8 and 0xFF

        val blue =
            color and 0xFF

        val maximum =
            maxOf(
                red,
                green,
                blue
            )

        val minimum =
            minOf(
                red,
                green,
                blue
            )

        return red > 225 &&
                green > 225 &&
                blue > 225 &&
                maximum - minimum < 25
    }

    private fun isVeryDark(
        color: Int
    ): Boolean {
        val red =
            color shr 16 and 0xFF

        val green =
            color shr 8 and 0xFF

        val blue =
            color and 0xFF

        return red < 100 &&
                green < 100 &&
                blue < 100
    }

    private const val MINIMUM_PIN_WHITE_DENSITY =
        0.40

    private const val HOUSE_SCORE_THRESHOLD =
        0.72
}
