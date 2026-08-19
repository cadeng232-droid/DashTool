package com.example.dashtool

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Private persistent retry queue for restaurant/waiting-area uploads.
 * Files are deleted only after the server returns a 2xx response.
 */
object WaitingDataUploadManager {

    data class RetrySummary(
        val uploadedCount: Int,
        val remainingCount: Int
    )

    private const val LOG_TAG = "DashToolWaitingQueue"
    private const val QUEUE_DIRECTORY = "waiting_data_upload_queue"

    @Synchronized
    fun enqueueAndTryUpload(
        context: Context,
        endpoint: String,
        payload: JSONObject
    ): Boolean {
        val queueDirectory =
            queueDirectory(
                context.applicationContext
            )

        val queueFile =
            File(
                queueDirectory,
                "waiting_${System.currentTimeMillis()}_${UUID.randomUUID()}.json"
            )

        val envelope =
            JSONObject().apply {
                put(
                    "endpoint",
                    endpoint
                )
                put(
                    "payload",
                    payload
                )
            }

        return try {
            queueFile.writeText(
                envelope.toString(),
                Charsets.UTF_8
            )

            val uploaded =
                WaitingDataClient.postJson(
                    endpoint = endpoint,
                    payload = payload
                )

            if (uploaded) {
                queueFile.delete()
            }

            uploaded
        } catch (exception: Exception) {
            Log.w(
                LOG_TAG,
                "Could not enqueue waiting data: " +
                        (exception.message
                            ?: exception.javaClass.simpleName),
                exception
            )
            false
        }
    }

    @Synchronized
    fun retryPending(
        context: Context
    ): RetrySummary {
        val directory =
            queueDirectory(
                context.applicationContext
            )

        val files =
            directory.listFiles()
                ?.filter { file ->
                    file.isFile &&
                            file.extension.equals(
                                "json",
                                ignoreCase = true
                            )
                }
                ?.sortedBy { file ->
                    file.lastModified()
                }
                ?: emptyList()

        var uploadedCount = 0

        files.forEach { file ->
            val uploaded =
                runCatching {
                    val envelope =
                        JSONObject(
                            file.readText(
                                Charsets.UTF_8
                            )
                        )

                    val endpoint =
                        envelope.getString(
                            "endpoint"
                        )

                    val payload =
                        envelope.getJSONObject(
                            "payload"
                        )

                    WaitingDataClient.postJson(
                        endpoint = endpoint,
                        payload = payload
                    )
                }.getOrElse { exception ->
                    Log.w(
                        LOG_TAG,
                        "Could not retry ${file.name}: " +
                                (exception.message
                                    ?: exception.javaClass.simpleName),
                        exception
                    )
                    false
                }

            if (uploaded) {
                if (file.delete()) {
                    uploadedCount += 1
                }
            }
        }

        val remainingCount =
            directory.listFiles()
                ?.count { file ->
                    file.isFile &&
                            file.extension.equals(
                                "json",
                                ignoreCase = true
                            )
                }
                ?: 0

        return RetrySummary(
            uploadedCount = uploadedCount,
            remainingCount = remainingCount
        )
    }

    private fun queueDirectory(
        context: Context
    ): File {
        return File(
            context.filesDir,
            QUEUE_DIRECTORY
        ).apply {
            mkdirs()
        }
    }
}