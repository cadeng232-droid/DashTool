package com.example.dashtool

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Converts DoorDash offer-map screen pixels into an estimated latitude/longitude.
 *
 * Two geographic anchors are used:
 *  - blue driver dot center <-> phone GPS captured near screenshot time
 *  - restaurant pin bottom tip <-> restaurant GPS
 *
 * The map is modeled as a flat Web-Mercator map with uniform scale and rotation.
 * Screen Y grows downward, so screen points are converted to a conventional
 * Cartesian coordinate system by negating Y before solving the similarity
 * transform.
 */
object DoorDashMapCoordinateMath {

    data class GeoPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class Estimate(
        val customer: GeoPoint,
        val calibrationPixelDistance: Double,
        val customerPixelDistance: Double,
        val extrapolationRatio: Double,
        val anchorStraightLineMeters: Double,
        val approximateMetersPerPixel: Double
    )

    private data class ProjectedPoint(
        val x: Double,
        val y: Double
    )

    private const val EARTH_RADIUS_METERS =
        6_378_137.0

    private const val MAX_MERCATOR_LATITUDE =
        85.05112878

    /*
     * Below this distance, a one-pixel marker error is amplified too much to
     * make a useful two-anchor calibration. The server applies a stricter
     * learning threshold; this merely prevents obviously unstable estimates.
     */
    private const val MIN_ANCHOR_PIXEL_DISTANCE =
        40.0

    fun estimateCustomer(
        driverPixel: DoorDashOfferMapLocator.PixelPoint,
        restaurantPixel: DoorDashOfferMapLocator.PixelPoint,
        customerPixel: DoorDashOfferMapLocator.PixelPoint,
        driverGeo: GeoPoint,
        restaurantGeo: GeoPoint
    ): Estimate? {
        if (
            !isValidGeo(driverGeo) ||
            !isValidGeo(restaurantGeo)
        ) {
            return null
        }

        val screenRestaurantX =
            (restaurantPixel.x - driverPixel.x).toDouble()

        // Convert Android's downward-positive Y into normal upward-positive Y.
        val screenRestaurantY =
            -(restaurantPixel.y - driverPixel.y).toDouble()

        val calibrationPixelDistance =
            sqrt(
                screenRestaurantX * screenRestaurantX +
                        screenRestaurantY * screenRestaurantY
            )

        if (
            calibrationPixelDistance <
            MIN_ANCHOR_PIXEL_DISTANCE
        ) {
            return null
        }

        val driverProjected =
            project(driverGeo)

        val restaurantProjected =
            project(restaurantGeo)

        val geoRestaurantX =
            restaurantProjected.x - driverProjected.x

        val geoRestaurantY =
            restaurantProjected.y - driverProjected.y

        val denominator =
            screenRestaurantX * screenRestaurantX +
                    screenRestaurantY * screenRestaurantY

        if (denominator <= 0.0) {
            return null
        }

        /*
         * Complex-number form of a 2D similarity transform.
         *
         * q = geoVector / screenVector = a + i*b
         * geoX = a*screenX - b*screenY
         * geoY = b*screenX + a*screenY
         */
        val a =
            (
                    geoRestaurantX * screenRestaurantX +
                            geoRestaurantY * screenRestaurantY
                    ) / denominator

        val b =
            (
                    geoRestaurantY * screenRestaurantX -
                            geoRestaurantX * screenRestaurantY
                    ) / denominator

        val screenCustomerX =
            (customerPixel.x - driverPixel.x).toDouble()

        val screenCustomerY =
            -(customerPixel.y - driverPixel.y).toDouble()

        val customerPixelDistance =
            sqrt(
                screenCustomerX * screenCustomerX +
                        screenCustomerY * screenCustomerY
            )

        val customerProjected =
            ProjectedPoint(
                x =
                    driverProjected.x +
                            a * screenCustomerX -
                            b * screenCustomerY,
                y =
                    driverProjected.y +
                            b * screenCustomerX +
                            a * screenCustomerY
            )

        val customer =
            unproject(customerProjected)

        if (!isValidGeo(customer)) {
            return null
        }

        val anchorStraightLineMeters =
            haversineMeters(
                driverGeo,
                restaurantGeo
            )

        if (
            !anchorStraightLineMeters.isFinite() ||
            anchorStraightLineMeters <= 0.0
        ) {
            return null
        }

        return Estimate(
            customer = customer,
            calibrationPixelDistance =
                calibrationPixelDistance,
            customerPixelDistance =
                customerPixelDistance,
            extrapolationRatio =
                customerPixelDistance /
                        calibrationPixelDistance,
            anchorStraightLineMeters =
                anchorStraightLineMeters,
            approximateMetersPerPixel =
                anchorStraightLineMeters /
                        calibrationPixelDistance
        )
    }

    private fun project(
        point: GeoPoint
    ): ProjectedPoint {
        val latitude =
            point.latitude
                .coerceIn(
                    -MAX_MERCATOR_LATITUDE,
                    MAX_MERCATOR_LATITUDE
                )

        val latRadians =
            Math.toRadians(latitude)

        val lonRadians =
            Math.toRadians(point.longitude)

        return ProjectedPoint(
            x = EARTH_RADIUS_METERS * lonRadians,
            y =
                EARTH_RADIUS_METERS *
                        ln(
                            tan(
                                PI / 4.0 +
                                        latRadians / 2.0
                            )
                        )
        )
    }

    private fun unproject(
        point: ProjectedPoint
    ): GeoPoint {
        val longitude =
            Math.toDegrees(
                point.x /
                        EARTH_RADIUS_METERS
            )

        val latitude =
            Math.toDegrees(
                2.0 *
                        atan(
                            exp(
                                point.y /
                                        EARTH_RADIUS_METERS
                            )
                        ) -
                        PI / 2.0
            )

        return GeoPoint(
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun isValidGeo(
        point: GeoPoint
    ): Boolean {
        return point.latitude.isFinite() &&
                point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 &&
                point.longitude in -180.0..180.0
    }

    private fun haversineMeters(
        first: GeoPoint,
        second: GeoPoint
    ): Double {
        val lat1 =
            Math.toRadians(first.latitude)

        val lat2 =
            Math.toRadians(second.latitude)

        val deltaLat =
            lat2 - lat1

        val deltaLon =
            Math.toRadians(
                second.longitude -
                        first.longitude
            )

        val sinLat =
            kotlin.math.sin(
                deltaLat / 2.0
            )

        val sinLon =
            kotlin.math.sin(
                deltaLon / 2.0
            )

        val value =
            sinLat * sinLat +
                    kotlin.math.cos(lat1) *
                    kotlin.math.cos(lat2) *
                    sinLon * sinLon

        val centralAngle =
            2.0 *
                    kotlin.math.asin(
                        sqrt(
                            min(
                                1.0,
                                max(
                                    0.0,
                                    value
                                )
                            )
                        )
                    )

        return EARTH_RADIUS_METERS *
                centralAngle
    }
}
