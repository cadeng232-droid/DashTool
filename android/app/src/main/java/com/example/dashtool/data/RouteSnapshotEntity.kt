package com.example.dashtool.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/*
 * One offer may eventually have one restaurant route
 * and one customer route.
 *
 * offerId + routeLeg form the primary key, preventing
 * duplicate rows for the same route leg.
 */
@Entity(
    tableName = "route_snapshots",

    primaryKeys = [
        "offerId",
        "routeLeg"
    ],

    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["offerId"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],

    indices = [
        Index(
            value = ["offerId"]
        )
    ]
)
data class RouteSnapshotEntity(

    val offerId: String,

    /*
     * Initially:
     * CURRENT_TO_RESTAURANT
     *
     * Reserved for later:
     * RESTAURANT_TO_CUSTOMER
     */
    val routeLeg: String,

    /*
     * Route estimates depend on current traffic, so
     * the time at which the route was captured matters.
     */
    val capturedAtWallTime: Long,

    /*
     * These are null when routing failed or timed out.
     */
    val etaMinutes: Double? = null,

    val distanceMiles: Double? = null,

    /*
     * GOOGLE_TRAFFIC_AWARE or DISTANCE_FALLBACK.
     */
    val routeSource: String,

    /*
     * SUCCESS, FAILED, TIMED_OUT, or NOT_AVAILABLE.
     */
    val routeStatus: String
)