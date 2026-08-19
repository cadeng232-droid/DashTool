package com.example.dashtool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.dashtool.data.DashToolRepository
import com.example.dashtool.data.OfferEntity
import com.example.dashtool.data.OrderEventEntity
import com.example.dashtool.data.OrderEventType
import com.example.dashtool.data.RouteSnapshotEntity
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

object CompletedOrderExporter {

    private const val LOG_TAG =
        "DashToolExport"

    private const val EXPORT_SCHEMA_VERSION =
        2

    private const val EXPORT_DIRECTORY_NAME =
        "DashTool"

    private const val MAX_EXPORT_ATTEMPTS =
        3

    private const val LIFECYCLE_STATUS_COMPLETE =
        "COMPLETE"

    private const val LIFECYCLE_STATUS_INCOMPLETE =
        "INCOMPLETE"

    private val EXPECTED_LIFECYCLE_EVENTS =
        listOf(
            OrderEventType
                .OFFER_ACCEPTED,

            OrderEventType
                .ARRIVED_AT_RESTAURANT,

            OrderEventType
                .PICKUP_CONFIRMED,

            OrderEventType
                .ARRIVED_AT_CUSTOMER,

            OrderEventType
                .DELIVERY_COMPLETED
        )

    private data class LifecycleDiagnostics(
        val status: String,
        val endEventType: String,
        val reachedEvents: List<String>,
        val missingEvents: List<String>,
        val qualityNote: String
    )

    suspend fun exportCompletedOrderWithRetry(
        context: Context,
        repository: DashToolRepository,
        offerId: String,
        routeSnapshot: RouteSnapshotEntity?
    ): Boolean {
        var lastException: Exception? =
            null

        repeat(
            MAX_EXPORT_ATTEMPTS
        ) { attemptIndex ->
            try {
                exportCompletedOrder(
                    context =
                        context,

                    repository =
                        repository,

                    offerId =
                        offerId,

                    routeSnapshot =
                        routeSnapshot
                )

                Log.d(
                    LOG_TAG,
                    "Exported ended order $offerId."
                )

                return true
            } catch (
                exception: Exception
            ) {
                lastException =
                    exception

                Log.e(
                    LOG_TAG,
                    "Export attempt ${attemptIndex + 1} failed for $offerId.",
                    exception
                )

                if (
                    attemptIndex <
                    MAX_EXPORT_ATTEMPTS - 1
                ) {
                    delay(
                        2_000L *
                                (attemptIndex + 1)
                    )
                }
            }
        }

        Log.e(
            LOG_TAG,
            "Ended order $offerId remains stored in Room but was not exported.",
            lastException
        )

        return false
    }

    private suspend fun exportCompletedOrder(
        context: Context,
        repository: DashToolRepository,
        offerId: String,
        routeSnapshot: RouteSnapshotEntity?
    ) {
        val offer =
            repository.getOffer(
                offerId
            )
                ?: error(
                    "Offer $offerId was not found."
                )

        val events =
            repository
                .getEventsForOffer(
                    offerId
                )
                .sortedBy {
                    it.elapsedTime
                }

        val lifecycleDiagnostics =
            buildLifecycleDiagnostics(
                events
            )

        val completedOrderJson =
            buildCompletedOrderJson(
                offer =
                    offer,

                routeSnapshot =
                    routeSnapshot,

                events =
                    events,

                lifecycleDiagnostics =
                    lifecycleDiagnostics
            )

        completedOrderJson.put(
            "decision_telemetry",
            OrderDecisionTelemetryStore
                .load(
                    context =
                        context,

                    offerId =
                        offerId
                ) ?: JSONObject.NULL
        )

        completedOrderJson.put(
            "linked_data_keys",
            JSONObject().apply {
                put(
                    "restaurant_observations_offer_id",
                    offerId
                )

                put(
                    "customer_map_samples_offer_id",
                    offerId
                )

                put(
                    "offer_wait_samples_next_offer_id",
                    offerId
                )

                put(
                    "waiting_sessions_previous_or_next_offer_id",
                    offerId
                )
            }
        )

        val jsonText =
            completedOrderJson.toString(
                2
            )

        val uploadedImmediately =
            OrderUploadManager
                .enqueueAndTryUpload(
                    context =
                        context,

                    offerId =
                        offerId,

                    jsonText =
                        jsonText
                )

        Log.d(
            LOG_TAG,
            if (
                uploadedImmediately
            ) {
                "Uploaded ended order $offerId."
            } else {
                "Ended order $offerId is queued for upload."
            }
        )

        val filePrefix =
            if (
                lifecycleDiagnostics.status ==
                LIFECYCLE_STATUS_COMPLETE
            ) {
                "completed"
            } else {
                "incomplete"
            }

        val fileName =
            "${filePrefix}_${sanitizeFileName(offerId)}.json"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            writeUsingMediaStore(
                context =
                    context,

                fileName =
                    fileName,

                jsonText =
                    jsonText
            )
        } else {
            writeToLegacyAppFolder(
                context =
                    context,

                fileName =
                    fileName,

                jsonText =
                    jsonText
            )
        }
    }

    private fun buildCompletedOrderJson(
        offer: OfferEntity,
        routeSnapshot: RouteSnapshotEntity?,
        events: List<OrderEventEntity>,
        lifecycleDiagnostics: LifecycleDiagnostics
    ): JSONObject {
        val eventArray =
            JSONArray()

        events.forEach {
                event ->

            eventArray.put(
                JSONObject().apply {
                    put(
                        "event_type",
                        event.eventType
                    )

                    put(
                        "wall_time_ms",
                        event.wallTime
                    )

                    put(
                        "elapsed_time_ms",
                        event.elapsedTime
                    )

                    put(
                        "source",
                        event.source
                    )

                    put(
                        "confidence",
                        event.confidence
                    )
                }
            )
        }

        return JSONObject().apply {
            put(
                "schema_version",
                EXPORT_SCHEMA_VERSION
            )

            put(
                "exported_at_wall_time_ms",
                System.currentTimeMillis()
            )

            put(
                "lifecycle_status",
                lifecycleDiagnostics.status
            )

            put(
                "lifecycle_end_event",
                lifecycleDiagnostics.endEventType
            )

            put(
                "reached_lifecycle_events",
                stringListToJson(
                    lifecycleDiagnostics
                        .reachedEvents
                )
            )

            put(
                "missing_lifecycle_events",
                stringListToJson(
                    lifecycleDiagnostics
                        .missingEvents
                )
            )

            put(
                "offer",
                offerToJson(
                    offer =
                        offer,

                    lifecycleDiagnostics =
                        lifecycleDiagnostics
                )
            )

            put(
                "route_snapshot",
                routeSnapshot?.let {
                    routeToJson(
                        it
                    )
                } ?: JSONObject.NULL
            )

            put(
                "events",
                eventArray
            )

            /*
             * Any duration whose start or end event was
             * missed is written as JSON null.
             */
            put(
                "derived_durations_ms",
                buildDerivedDurations(
                    offer =
                        offer,

                    events =
                        events
                )
            )
        }
    }

    private fun offerToJson(
        offer: OfferEntity,
        lifecycleDiagnostics: LifecycleDiagnostics
    ): JSONObject {
        val lifecycleIsIncomplete =
            lifecycleDiagnostics.status !=
                    LIFECYCLE_STATUS_COMPLETE

        val mergedQualityNote =
            mergeQualityNotes(
                existingNote =
                    offer.qualityNote,

                lifecycleNote =
                    if (
                        lifecycleIsIncomplete
                    ) {
                        lifecycleDiagnostics
                            .qualityNote
                    } else {
                        null
                    }
            )

        return JSONObject().apply {
            put(
                "offer_id",
                offer.offerId
            )

            put(
                "session_id",
                offer.sessionId
            )

            put(
                "detected_at_wall_time_ms",
                offer.detectedAtWallTime
            )

            put(
                "detected_at_elapsed_time_ms",
                offer.detectedAtElapsedTime
            )

            put(
                "detection_source",
                offer.detectionSource
            )

            put(
                "restaurant_name",
                offer.restaurantName
            )

            putNullable(
                key =
                    "restaurant_place_id",

                value =
                    offer.restaurantPlaceId
            )

            put(
                "restaurant_match_confidence",
                offer.restaurantMatchConfidence
            )

            put(
                "offered_payout",
                offer.offeredPayout
            )

            put(
                "displayed_total_miles",
                offer.displayedTotalMiles
            )

            put(
                "app_version",
                offer.appVersion
            )

            put(
                "parser_version",
                offer.parserVersion
            )

            put(
                "engine_version",
                offer.engineVersion
            )

            put(
                "gas_price_used",
                offer.gasPriceUsed
            )

            put(
                "vehicle_mpg_used",
                offer.vehicleMpgUsed
            )

            put(
                "score_shown",
                offer.scoreShown
            )

            putNullable(
                key =
                    "final_payout",

                value =
                    offer.finalPayout
            )

            putNullable(
                key =
                    "actual_distance_miles",

                value =
                    offer.actualDistanceMiles
            )

            put(
                "exclude_from_training",
                offer.excludeFromTraining ||
                        lifecycleIsIncomplete
            )

            putNullable(
                key =
                    "quality_note",

                value =
                    mergedQualityNote
            )
        }
    }

    private fun buildLifecycleDiagnostics(
        events: List<OrderEventEntity>
    ): LifecycleDiagnostics {
        val reachedEvents =
            events
                .map {
                    it.eventType
                }
                .distinct()

        val endEvent =
            events.lastOrNull {
                it.eventType ==
                        OrderEventType
                            .DELIVERY_COMPLETED ||
                        it.eventType ==
                        OrderEventType
                            .ORDER_ENDED_INCOMPLETE
            }
                ?: error(
                    "Order has no lifecycle-ending event."
                )

        val missingEvents =
            EXPECTED_LIFECYCLE_EVENTS
                .filterNot {
                        expectedEvent ->

                    reachedEvents.contains(
                        expectedEvent
                    )
                }

        val isComplete =
            endEvent.eventType ==
                    OrderEventType
                        .DELIVERY_COMPLETED &&
                    missingEvents.isEmpty()

        val status =
            if (
                isComplete
            ) {
                LIFECYCLE_STATUS_COMPLETE
            } else {
                LIFECYCLE_STATUS_INCOMPLETE
            }

        val qualityNote =
            if (
                missingEvents.isEmpty()
            ) {
                "Lifecycle ended without satisfying all completion requirements."
            } else {
                "Lifecycle ended with missing events: " +
                        missingEvents.joinToString(
                            separator =
                                ", "
                        ) +
                        "."
            }

        return LifecycleDiagnostics(
            status =
                status,

            endEventType =
                endEvent.eventType,

            reachedEvents =
                reachedEvents,

            missingEvents =
                missingEvents,

            qualityNote =
                qualityNote
        )
    }

    private fun stringListToJson(
        values: List<String>
    ): JSONArray {
        return JSONArray().apply {
            values.forEach {
                    value ->

                put(
                    value
                )
            }
        }
    }

    private fun mergeQualityNotes(
        existingNote: String?,
        lifecycleNote: String?
    ): String? {
        val notes =
            listOfNotNull(
                existingNote
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    },

                lifecycleNote
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
            )

        return notes
            .takeIf {
                it.isNotEmpty()
            }
            ?.joinToString(
                separator =
                    " "
            )
    }

    private fun routeToJson(
        route: RouteSnapshotEntity
    ): JSONObject {
        return JSONObject().apply {
            put(
                "offer_id",
                route.offerId
            )

            put(
                "route_leg",
                route.routeLeg
            )

            put(
                "captured_at_wall_time_ms",
                route.capturedAtWallTime
            )

            putNullable(
                key =
                    "eta_minutes",

                value =
                    route.etaMinutes
            )

            putNullable(
                key =
                    "distance_miles",

                value =
                    route.distanceMiles
            )

            put(
                "route_source",
                route.routeSource
            )

            put(
                "route_status",
                route.routeStatus
            )
        }
    }

    private fun buildDerivedDurations(
        offer: OfferEntity,
        events: List<OrderEventEntity>
    ): JSONObject {
        fun elapsedTimeFor(
            eventType: String
        ): Long? {
            return events
                .firstOrNull {
                    it.eventType ==
                            eventType
                }
                ?.elapsedTime
        }

        fun durationBetween(
            startEventType: String,
            endEventType: String
        ): Long? {
            val start =
                elapsedTimeFor(
                    startEventType
                )
                    ?: return null

            val end =
                elapsedTimeFor(
                    endEventType
                )
                    ?: return null

            return (
                    end - start
                    )
                .coerceAtLeast(
                    0L
                )
        }

        val acceptedAt =
            elapsedTimeFor(
                OrderEventType
                    .OFFER_ACCEPTED
            )

        val decisionDuration =
            acceptedAt?.let {
                (
                        it -
                                offer.detectedAtElapsedTime
                        )
                    .coerceAtLeast(
                        0L
                    )
            }

        return JSONObject().apply {
            putNullable(
                key =
                    "offer_decision_ms",

                value =
                    decisionDuration
            )

            putNullable(
                key =
                    "drive_to_restaurant_ms",

                value =
                    durationBetween(
                        OrderEventType
                            .OFFER_ACCEPTED,

                        OrderEventType
                            .ARRIVED_AT_RESTAURANT
                    )
            )

            putNullable(
                key =
                    "restaurant_wait_ms",

                value =
                    durationBetween(
                        OrderEventType
                            .ARRIVED_AT_RESTAURANT,

                        OrderEventType
                            .PICKUP_CONFIRMED
                    )
            )

            putNullable(
                key =
                    "drive_to_customer_ms",

                value =
                    durationBetween(
                        OrderEventType
                            .PICKUP_CONFIRMED,

                        OrderEventType
                            .ARRIVED_AT_CUSTOMER
                    )
            )

            putNullable(
                key =
                    "dropoff_ms",

                value =
                    durationBetween(
                        OrderEventType
                            .ARRIVED_AT_CUSTOMER,

                        OrderEventType
                            .DELIVERY_COMPLETED
                    )
            )

            putNullable(
                key =
                    "total_order_ms",

                value =
                    durationBetween(
                        OrderEventType
                            .OFFER_ACCEPTED,

                        OrderEventType
                            .DELIVERY_COMPLETED
                    )
            )
        }
    }

    private fun writeUsingMediaStore(
        context: Context,
        fileName: String,
        jsonText: String
    ) {
        val resolver =
            context.contentResolver

        val collection =
            MediaStore.Files.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )

        val relativePath =
            Environment.DIRECTORY_DOCUMENTS +
                    "/" +
                    EXPORT_DIRECTORY_NAME +
                    "/"

        val existingUri =
            findExistingMediaStoreFile(
                context =
                    context,

                collection =
                    collection,

                fileName =
                    fileName,

                relativePath =
                    relativePath
            )

        val targetUri =
            existingUri
                ?: resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            fileName
                        )

                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            "application/json"
                        )

                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            relativePath
                        )

                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            1
                        )
                    }
                )
                ?: error(
                    "Could not create export file."
                )

        try {
            resolver.openOutputStream(
                targetUri,
                "wt"
            )
                ?.bufferedWriter()
                ?.use {
                        writer ->

                    writer.write(
                        jsonText
                    )
                }
                ?: error(
                    "Could not open export file."
                )

            if (
                existingUri == null
            ) {
                resolver.update(
                    targetUri,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            0
                        )
                    },
                    null,
                    null
                )
            }
        } catch (
            exception: Exception
        ) {
            if (
                existingUri == null
            ) {
                runCatching {
                    resolver.delete(
                        targetUri,
                        null,
                        null
                    )
                }
            }

            throw exception
        }
    }

    private fun findExistingMediaStoreFile(
        context: Context,
        collection: Uri,
        fileName: String,
        relativePath: String
    ): Uri? {
        val resolver =
            context.contentResolver

        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID
            )

        val selection =
            MediaStore.MediaColumns.DISPLAY_NAME +
                    " = ? AND " +
                    MediaStore.MediaColumns.RELATIVE_PATH +
                    " = ?"

        val selectionArguments =
            arrayOf(
                fileName,
                relativePath
            )

        resolver.query(
            collection,
            projection,
            selection,
            selectionArguments,
            null
        )?.use {
                cursor ->

            if (
                cursor.moveToFirst()
            ) {
                val id =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns._ID
                        )
                    )

                return Uri.withAppendedPath(
                    collection,
                    id.toString()
                )
            }
        }

        return null
    }

    private fun writeToLegacyAppFolder(
        context: Context,
        fileName: String,
        jsonText: String
    ) {
        val documentsDirectory =
            context.getExternalFilesDir(
                Environment.DIRECTORY_DOCUMENTS
            )
                ?: context.filesDir

        val exportDirectory =
            File(
                documentsDirectory,
                EXPORT_DIRECTORY_NAME
            )

        check(
            exportDirectory.exists() ||
                    exportDirectory.mkdirs()
        ) {
            "Could not create export directory."
        }

        File(
            exportDirectory,
            fileName
        ).writeText(
            jsonText
        )
    }

    private fun sanitizeFileName(
        value: String
    ): String {
        return value.replace(
            Regex(
                "[^A-Za-z0-9._-]"
            ),
            "_"
        )
    }

    private fun JSONObject.putNullable(
        key: String,
        value: Any?
    ) {
        put(
            key,
            value ?: JSONObject.NULL
        )
    }
}