package com.example.dashtool.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DashToolDao {

    /*
     * DASH SESSIONS
     */

    @Upsert
    suspend fun upsertSession(
        session: DashSessionEntity
    )

    @Query(
        """
        SELECT *
        FROM dash_sessions
        WHERE endedAtWallTime IS NULL
        ORDER BY startedAtWallTime DESC
        LIMIT 1
        """
    )
    suspend fun getActiveSession():
            DashSessionEntity?

    @Query(
        """
        UPDATE dash_sessions
        SET
            endedAtWallTime = :endedAtWallTime,
            endedAtElapsedTime = :endedAtElapsedTime
        WHERE sessionId = :sessionId
        """
    )
    suspend fun closeSession(
        sessionId: String,
        endedAtWallTime: Long,
        endedAtElapsedTime: Long
    ): Int

    @Query(
        """
        SELECT *
        FROM dash_sessions
        ORDER BY startedAtWallTime DESC
        """
    )
    suspend fun getAllSessions():
            List<DashSessionEntity>

    /*
     * OFFERS
     */

    @Upsert
    suspend fun upsertOffer(
        offer: OfferEntity
    )

    @Query(
        """
        SELECT *
        FROM offers
        WHERE offerId = :offerId
        LIMIT 1
        """
    )
    suspend fun getOffer(
        offerId: String
    ): OfferEntity?

    @Query(
        """
        SELECT *
        FROM offers
        ORDER BY detectedAtWallTime DESC
        """
    )
    suspend fun getAllOffers():
            List<OfferEntity>

    @Query(
        """
        SELECT *
        FROM offers
        WHERE sessionId = :sessionId
        ORDER BY detectedAtWallTime ASC
        """
    )
    suspend fun getOffersForSession(
        sessionId: String
    ): List<OfferEntity>

    /*
     * Prevents a second scan of the same visible offer
     * from creating another permanent order row.
     *
     * The comparison uses only information already
     * stored in the offer table.
     */
    @Query(
        """
        SELECT *
        FROM offers
        WHERE
            sessionId = :sessionId
            AND restaurantName = :restaurantName
            AND ABS(offeredPayout - :offeredPayout) < 0.001
            AND ABS(displayedTotalMiles - :displayedTotalMiles) < 0.001
            AND detectedAtWallTime >= :earliestWallTime
        ORDER BY detectedAtWallTime DESC
        LIMIT 1
        """
    )
    suspend fun findRecentMatchingOffer(
        sessionId: String,
        restaurantName: String,
        offeredPayout: Double,
        displayedTotalMiles: Double,
        earliestWallTime: Long
    ): OfferEntity?

    @Query(
        """
        UPDATE offers
        SET finalPayout = :finalPayout
        WHERE offerId = :offerId
        """
    )
    suspend fun updateFinalPayout(
        offerId: String,
        finalPayout: Double
    ): Int

    @Query(
        """
        UPDATE offers
        SET actualDistanceMiles = :actualDistanceMiles
        WHERE offerId = :offerId
        """
    )
    suspend fun updateActualDistance(
        offerId: String,
        actualDistanceMiles: Double
    ): Int

    @Query(
        """
        UPDATE offers
        SET
            excludeFromTraining = :excludeFromTraining,
            qualityNote = :qualityNote
        WHERE offerId = :offerId
        """
    )
    suspend fun updateTrainingEligibility(
        offerId: String,
        excludeFromTraining: Boolean,
        qualityNote: String?
    ): Int

    /*
     * ROUTE SNAPSHOTS
     */

    @Upsert
    suspend fun upsertRouteSnapshot(
        routeSnapshot: RouteSnapshotEntity
    )

    @Query(
        """
        SELECT *
        FROM route_snapshots
        WHERE offerId = :offerId
        ORDER BY capturedAtWallTime ASC
        """
    )
    suspend fun getRoutesForOffer(
        offerId: String
    ): List<RouteSnapshotEntity>

    /*
     * ORDER EVENTS
     */

    @Upsert
    suspend fun upsertOrderEvent(
        orderEvent: OrderEventEntity
    )

    @Query(
        """
        SELECT *
        FROM order_events
        WHERE offerId = :offerId
        ORDER BY elapsedTime ASC
        """
    )
    suspend fun getEventsForOffer(
        offerId: String
    ): List<OrderEventEntity>

    @Query(
        """
        DELETE FROM order_events
        WHERE
            offerId = :offerId
            AND eventType = :eventType
        """
    )
    suspend fun deleteOrderEvent(
        offerId: String,
        eventType: String
    ): Int
}