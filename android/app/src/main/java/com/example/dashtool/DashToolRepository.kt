package com.example.dashtool.data

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import java.util.UUID

/*
 * Provides one central place for DashTool to read
 * and write data.
 *
 * Services and activities should use this repository
 * instead of communicating with Room directly.
 */
class DashToolRepository private constructor(
    context: Context
) {

    companion object {

        private const val SESSION_PREFS_NAME =
            "dash_tool_session_state"

        private const val KEY_ACTIVE_SESSION_ID =
            "active_session_id"

        /*
         * A manual rescan or repeated notification
         * within this period is treated as the same
         * visible offer when restaurant, payout, and
         * mileage also match.
         */
        private const val DUPLICATE_OFFER_WINDOW_MS =
            45_000L

        @Volatile
        private var instance:
                DashToolRepository? = null

        fun getInstance(
            context: Context
        ): DashToolRepository {

            return instance
                ?: synchronized(this) {

                    instance
                        ?: DashToolRepository(
                            context.applicationContext
                        )
                            .also {
                                    repository ->

                                instance =
                                    repository
                            }
                }
        }
    }

    private val appContext =
        context.applicationContext

    private val database =
        DashToolDatabase.getInstance(
            appContext
        )

    private val dao =
        database.dashToolDao()

    /*
     * SharedPreferences stores only the ID of the
     * currently active dash session.
     *
     * The full session record remains in Room.
     */
    private val sessionPreferences =
        appContext.getSharedPreferences(
            SESSION_PREFS_NAME,
            Context.MODE_PRIVATE
        )

    suspend fun startNewSession():
            DashSessionEntity {

        val currentWallTime =
            System.currentTimeMillis()

        val currentElapsedTime =
            SystemClock.elapsedRealtime()

        val previousActiveSession =
            dao.getActiveSession()

        if (
            previousActiveSession != null
        ) {
            dao.closeSession(
                sessionId =
                    previousActiveSession
                        .sessionId,

                endedAtWallTime =
                    currentWallTime,

                endedAtElapsedTime =
                    currentElapsedTime
            )
        }

        val session =
            DashSessionEntity(
                sessionId =
                    "session_" +
                            UUID.randomUUID()
                                .toString(),

                startedAtWallTime =
                    currentWallTime,

                startedAtElapsedTime =
                    currentElapsedTime
            )

        dao.upsertSession(
            session
        )

        sessionPreferences
            .edit()
            .putString(
                KEY_ACTIVE_SESSION_ID,
                session.sessionId
            )
            .apply()

        return session
    }

    suspend fun closeActiveSession():
            Boolean {

        val activeSession =
            dao.getActiveSession()
                ?: run {

                    clearCachedSessionId()

                    return false
                }

        val rowsUpdated =
            dao.closeSession(
                sessionId =
                    activeSession
                        .sessionId,

                endedAtWallTime =
                    System.currentTimeMillis(),

                endedAtElapsedTime =
                    SystemClock
                        .elapsedRealtime()
            )

        clearCachedSessionId()

        return rowsUpdated > 0
    }

    fun getCachedActiveSessionId():
            String? {

        return sessionPreferences
            .getString(
                KEY_ACTIVE_SESSION_ID,
                null
            )
    }

    suspend fun recoverActiveSessionId():
            String? {

        val activeSession =
            dao.getActiveSession()

        if (
            activeSession == null
        ) {
            clearCachedSessionId()

            return null
        }

        sessionPreferences
            .edit()
            .putString(
                KEY_ACTIVE_SESSION_ID,
                activeSession.sessionId
            )
            .apply()

        return activeSession.sessionId
    }

    suspend fun getAllSessions():
            List<DashSessionEntity> {

        return dao.getAllSessions()
    }

    suspend fun getAllOffers():
            List<OfferEntity> {

        return dao.getAllOffers()
    }

    suspend fun getOffersForSession(
        sessionId: String
    ): List<OfferEntity> {

        return dao.getOffersForSession(
            sessionId
        )
    }

    suspend fun getOffer(
        offerId: String
    ): OfferEntity? {

        return dao.getOffer(
            offerId
        )
    }

    suspend fun getRoutesForOffer(
        offerId: String
    ): List<RouteSnapshotEntity> {

        return dao.getRoutesForOffer(
            offerId
        )
    }

    suspend fun getEventsForOffer(
        offerId: String
    ): List<OrderEventEntity> {

        return dao.getEventsForOffer(
            offerId
        )
    }

    suspend fun saveOffer(
        offer: OfferEntity
    ) {
        dao.upsertOffer(
            offer
        )
    }

    suspend fun saveRouteSnapshot(
        routeSnapshot:
        RouteSnapshotEntity
    ) {
        dao.upsertRouteSnapshot(
            routeSnapshot
        )
    }

    /*
     * Saves one new offer and its restaurant-route
     * snapshot as a single database operation.
     *
     * Returns the ID of the newly saved offer.
     *
     * If the same offer was already recorded during
     * the duplicate window, no new row is created and
     * the existing offer ID is returned.
     */
    suspend fun saveOfferWithRouteIfNew(
        offer: OfferEntity,
        routeSnapshot:
        RouteSnapshotEntity
    ): String {

        require(
            offer.offerId ==
                    routeSnapshot.offerId
        ) {
            "Offer and route must use the same offer ID."
        }

        val earliestMatchingTime =
            offer.detectedAtWallTime -
                    DUPLICATE_OFFER_WINDOW_MS

        val existingOffer =
            dao.findRecentMatchingOffer(
                sessionId =
                    offer.sessionId,

                restaurantName =
                    offer.restaurantName,

                offeredPayout =
                    offer.offeredPayout,

                displayedTotalMiles =
                    offer.displayedTotalMiles,

                earliestWallTime =
                    earliestMatchingTime
            )

        if (
            existingOffer != null
        ) {
            return existingOffer.offerId
        }

        database.withTransaction {

            dao.upsertOffer(
                offer
            )

            dao.upsertRouteSnapshot(
                routeSnapshot
            )
        }

        return offer.offerId
    }

    suspend fun saveOrderEvent(
        orderEvent:
        OrderEventEntity
    ) {
        dao.upsertOrderEvent(
            orderEvent
        )
    }

    suspend fun updateFinalPayout(
        offerId: String,
        finalPayout: Double
    ): Boolean {

        return dao.updateFinalPayout(
            offerId =
                offerId,

            finalPayout =
                finalPayout
        ) > 0
    }

    suspend fun updateActualDistance(
        offerId: String,
        actualDistanceMiles: Double
    ): Boolean {

        return dao.updateActualDistance(
            offerId =
                offerId,

            actualDistanceMiles =
                actualDistanceMiles
        ) > 0
    }

    suspend fun updateTrainingEligibility(
        offerId: String,
        excludeFromTraining: Boolean,
        qualityNote: String?
    ): Boolean {

        return dao.updateTrainingEligibility(
            offerId =
                offerId,

            excludeFromTraining =
                excludeFromTraining,

            qualityNote =
                qualityNote
        ) > 0
    }

    private fun clearCachedSessionId() {

        sessionPreferences
            .edit()
            .remove(
                KEY_ACTIVE_SESSION_ID
            )
            .apply()
    }
}