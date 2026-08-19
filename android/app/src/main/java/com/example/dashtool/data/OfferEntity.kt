package com.example.dashtool.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * One row represents one unique, normal restaurant
 * offer detected by DashTool.
 */
@Entity(
    tableName = "offers",

    foreignKeys = [
        ForeignKey(
            entity = DashSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],

    indices = [
        Index(
            value = ["sessionId"]
        ),

        Index(
            value = ["restaurantPlaceId"]
        )
    ]
)
data class OfferEntity(

    @PrimaryKey
    val offerId: String,

    val sessionId: String,

    /*
     * The earliest reliable time at which DashTool
     * detected this offer.
     *
     * For an automatic scan, this will eventually use
     * the notification-listener time.
     *
     * For a manual scan, it will use the scan-request
     * time.
     */
    val detectedAtWallTime: Long,

    val detectedAtElapsedTime: Long,

    /*
     * Expected values:
     *
     * NOTIFICATION
     * MANUAL_SCAN
     */
    val detectionSource: String,

    /*
     * Google's display name will be preferred after a
     * successful restaurant match. Otherwise, the OCR
     * name will be saved.
     */
    val restaurantName: String,

    /*
     * Null when Google does not return a successful
     * restaurant match.
     */
    val restaurantPlaceId: String? = null,

    /*
     * HIGH, MEDIUM, LOW, or UNKNOWN.
     */
    val restaurantMatchConfidence: String =
        RestaurantMatchConfidence.UNKNOWN,

    val offeredPayout: Double,

    val displayedTotalMiles: Double,

    /*
     * These version fields allow historical records to
     * be interpreted after the app changes.
     */
    val appVersion: String,

    val parserVersion: Int,

    val engineVersion: Int,

    /*
     * The settings used when this offer was scored.
     * They must be saved because the user can change
     * them later.
     */
    val gasPriceUsed: Double,

    val vehicleMpgUsed: Double,

    /*
     * This is technically reproducible from the engine
     * version and inputs, but it is saved as an audit
     * value so we can verify what the user actually saw.
     */
    val scoreShown: Double,

    /*
     * Added later when the completed-order payout can
     * be detected. It may differ from offeredPayout.
     */
    val finalPayout: Double? = null,

    /*
     * Added later if actual mileage tracking is built.
     */
    val actualDistanceMiles: Double? = null,

    /*
     * A questionable record can remain in history
     * without being used to train a future model.
     */
    val excludeFromTraining: Boolean = false,

    val qualityNote: String? = null
)