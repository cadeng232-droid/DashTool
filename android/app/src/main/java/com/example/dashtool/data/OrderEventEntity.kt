package com.example.dashtool.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/*
 * One row represents one meaningful stage transition.
 *
 * offerId + eventType form the primary key. Therefore,
 * an event can be corrected by replacing the existing
 * event instead of adding a duplicate.
 */
@Entity(
    tableName = "order_events",

    primaryKeys = [
        "offerId",
        "eventType"
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
data class OrderEventEntity(

    val offerId: String,

    /*
     * Examples:
     *
     * OFFER_ACCEPTED
     * ARRIVED_AT_RESTAURANT
     * PICKUP_CONFIRMED
     * ARRIVED_AT_CUSTOMER
     * DELIVERY_COMPLETED
     */
    val eventType: String,

    val wallTime: Long,

    val elapsedTime: Long,

    /*
     * Examples:
     *
     * NOTIFICATION
     * SCREEN_STATE
     * SCREEN_CHANGE_OCR
     * LOCATION
     * SCREEN_AND_LOCATION
     * MANUAL
     */
    val source: String,

    /*
     * HIGH, MEDIUM, or LOW.
     */
    val confidence: String
)