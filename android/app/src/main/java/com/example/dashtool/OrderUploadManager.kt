package com.example.dashtool

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OrderUploadRetrySummary(
    val uploadedCount: Int,
    val remainingCount: Int
)

/**
 * Persistent upload queue.
 *
 * The completed JSON is saved privately before the network
 * request begins. A successful 2xx server response removes
 * the private pending file. Failed requests remain queued.
 */
object OrderUploadManager {

    private const val LOG_TAG =
        "DashToolUpload"

    private val retryMutex =
        Mutex()

    suspend fun enqueueAndTryUpload(
        context: Context,
        offerId: String,
        jsonText: String
    ): Boolean {
        val applicationContext =
            context.applicationContext

        val pendingFile =
            PendingOrderUploadStore.save(
                context =
                    applicationContext,

                offerId =
                    offerId,

                jsonText =
                    jsonText
            )

        Log.d(
            LOG_TAG,
            "Queued completed order $offerId at " +
                    pendingFile.name
        )

        retryPending(
            applicationContext
        )

        return !pendingFile.exists()
    }

    suspend fun retryPending(
        context: Context
    ): OrderUploadRetrySummary {
        val applicationContext =
            context.applicationContext

        return retryMutex.withLock {
            var uploadedCount =
                0

            val pendingFiles =
                PendingOrderUploadStore
                    .listPending(
                        applicationContext
                    )

            for (
                pendingFile in
                pendingFiles
            ) {
                val attempt =
                    uploadPendingFile(
                        pendingFile
                    )

                if (
                    attempt == null
                ) {
                    /*
                     * The local queue file could not be read.
                     * Leave it in place and continue so it does
                     * not block every later record.
                     */
                    continue
                }

                if (
                    attempt.successful
                ) {
                    if (
                        PendingOrderUploadStore
                            .delete(
                                pendingFile
                            )
                    ) {
                        uploadedCount +=
                            1
                    } else {
                        Log.e(
                            LOG_TAG,
                            "Server accepted ${pendingFile.name}, " +
                                    "but DashTool could not remove " +
                                    "the pending file."
                        )
                    }

                    continue
                }

                val statusCode =
                    attempt.statusCode

                /*
                 * No HTTP status normally means the PC,
                 * server, or network is unavailable. Stop so
                 * a large queue does not repeatedly time out.
                 */
                if (
                    statusCode == null
                ) {
                    break
                }

                /*
                 * Stop on server errors and retry later.
                 * A client error such as 422 is logged, but
                 * later queued records may still be valid.
                 */
                if (
                    statusCode >=
                    500
                ) {
                    break
                }
            }

            val remainingCount =
                PendingOrderUploadStore
                    .listPending(
                        applicationContext
                    )
                    .size

            OrderUploadRetrySummary(
                uploadedCount =
                    uploadedCount,

                remainingCount =
                    remainingCount
            )
        }
    }

    private suspend fun uploadPendingFile(
        pendingFile: File
    ): OrderUploadAttempt? {
        val jsonText =
            try {
                pendingFile.readText(
                    Charsets.UTF_8
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    LOG_TAG,
                    "Could not read ${pendingFile.name}.",
                    exception
                )

                return null
            }

        val attempt =
            OrderUploadClient.upload(
                jsonText
            )

        if (
            attempt.successful
        ) {
            Log.d(
                LOG_TAG,
                "Uploaded ${pendingFile.name}; " +
                        "server returned " +
                        "${attempt.statusCode}: " +
                        attempt.responseBody
            )
        } else if (
            attempt.exception != null
        ) {
            Log.w(
                LOG_TAG,
                "Upload pending for ${pendingFile.name}; " +
                        "server or network unavailable.",
                attempt.exception
            )
        } else {
            Log.w(
                LOG_TAG,
                "Upload rejected for ${pendingFile.name}; " +
                        "server returned " +
                        "${attempt.statusCode}: " +
                        attempt.responseBody
            )
        }

        return attempt
    }
}
