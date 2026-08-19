package com.example.dashtool.data

/*
 * These constants prevent slightly different strings
 * from being saved for the same category.
 */

object OfferDetectionSource {

    const val NOTIFICATION =
        "NOTIFICATION"

    const val MANUAL_SCAN =
        "MANUAL_SCAN"
}

object OfferKind {

    /*
     * The only order type DashTool currently supports.
     */
    const val NORMAL_RESTAURANT =
        "NORMAL_RESTAURANT"

    const val UNSUPPORTED =
        "UNSUPPORTED"

    const val UNKNOWN =
        "UNKNOWN"
}

object RestaurantMatchConfidence {

    const val HIGH =
        "HIGH"

    const val MEDIUM =
        "MEDIUM"

    const val LOW =
        "LOW"

    const val UNKNOWN =
        "UNKNOWN"
}

object RouteLeg {

    const val CURRENT_TO_RESTAURANT =
        "CURRENT_TO_RESTAURANT"

    /*
     * Reserved for future use.
     *
     * DashTool will not make this request during the
     * initial data-collection implementation.
     */
    const val RESTAURANT_TO_CUSTOMER =
        "RESTAURANT_TO_CUSTOMER"
}

object RouteSource {

    const val GOOGLE_TRAFFIC_AWARE =
        "GOOGLE_TRAFFIC_AWARE"

    const val DISTANCE_FALLBACK =
        "DISTANCE_FALLBACK"

    /*
     * Used when no meaningful route measurement exists.
     */
    const val NOT_AVAILABLE =
        "NOT_AVAILABLE"
}

object RouteStatus {

    const val SUCCESS =
        "SUCCESS"

    const val FAILED =
        "FAILED"

    const val TIMED_OUT =
        "TIMED_OUT"

    const val NOT_AVAILABLE =
        "NOT_AVAILABLE"
}

object OrderEventType {

    const val OFFER_ACCEPTED =
        "OFFER_ACCEPTED"

    const val OFFER_NOT_ACCEPTED =
        "OFFER_NOT_ACCEPTED"

    const val ARRIVED_AT_RESTAURANT =
        "ARRIVED_AT_RESTAURANT"

    const val PICKUP_CONFIRMED =
        "PICKUP_CONFIRMED"

    const val ARRIVED_AT_CUSTOMER =
        "ARRIVED_AT_CUSTOMER"

    const val DELIVERY_COMPLETED =
        "DELIVERY_COMPLETED"

    /*
     * Used when an accepted order disappears without
     * evidence that it was completed normally.
     */
    const val ORDER_ENDED_INCOMPLETE =
        "ORDER_ENDED_INCOMPLETE"
}

object OrderEventSource {

    const val NOTIFICATION =
        "NOTIFICATION"

    const val SCREEN_STATE =
        "SCREEN_STATE"

    const val SCREEN_CHANGE_OCR =
        "SCREEN_CHANGE_OCR"

    const val LOCATION =
        "LOCATION"

    const val SCREEN_AND_LOCATION =
        "SCREEN_AND_LOCATION"

    const val MANUAL =
        "MANUAL"
}

object DataConfidence {

    const val HIGH =
        "HIGH"

    const val MEDIUM =
        "MEDIUM"

    const val LOW =
        "LOW"
}