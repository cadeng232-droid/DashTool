package com.example.dashtool

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Small HTTP client for the new waiting-area data path.
 *
 * It intentionally reuses OrderUploadConfig.ORDERS_URL so there is only one
 * server address to maintain in the Android project.
 */
object WaitingDataClient {

    private const val LOG_TAG = "DashToolWaitingApi"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val GET_CONNECT_TIMEOUT_MS = 1_500
    private const val GET_READ_TIMEOUT_MS = 2_000

    private fun baseUrl(): String {
        return OrderUploadConfig.ORDERS_URL
            .trimEnd('/')
            .removeSuffix("/orders")
    }

    fun postRestaurantObservation(payload: JSONObject): Boolean {
        return postJson(
            endpoint = "/restaurants/observations",
            payload = payload
        )
    }

    fun postWaitingSession(payload: JSONObject): Boolean {
        return postJson(
            endpoint = "/waiting-sessions",
            payload = payload
        )
    }

    fun upsertWaitingCenter(payload: JSONObject): Boolean {
        return postJson(
            endpoint = "/waiting-centers",
            payload = payload
        )
    }

    fun getJson(
        endpoint: String
    ): JSONObject? {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = (
                    URL(baseUrl() + endpoint)
                        .openConnection() as HttpURLConnection
                    ).apply {
                    requestMethod = "GET"
                    connectTimeout = GET_CONNECT_TIMEOUT_MS
                    readTimeout = GET_READ_TIMEOUT_MS
                    doInput = true
                }

            connection = activeConnection

            val responseCode = activeConnection.responseCode
            if (responseCode !in 200..299) {
                Log.w(
                    LOG_TAG,
                    "GET $endpoint failed ($responseCode)."
                )
                return null
            }

            val responseText = BufferedReader(
                InputStreamReader(activeConnection.inputStream)
            ).use { reader ->
                reader.readText()
            }

            JSONObject(responseText)
        } catch (exception: Exception) {
            Log.w(
                LOG_TAG,
                "GET $endpoint failed: " +
                        (exception.message
                            ?: exception.javaClass.simpleName),
                exception
            )
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun postJson(
        endpoint: String,
        payload: JSONObject
    ): Boolean {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = (
                    URL(baseUrl() + endpoint)
                        .openConnection() as HttpURLConnection
                    ).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )
                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

            connection = activeConnection

            activeConnection.outputStream.use { output ->
                output.write(
                    payload.toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val responseCode = activeConnection.responseCode
            val success = responseCode in 200..299

            val responseText = runCatching {
                val stream =
                    if (success) {
                        activeConnection.inputStream
                    } else {
                        activeConnection.errorStream
                    }

                if (stream == null) {
                    ""
                } else {
                    BufferedReader(
                        InputStreamReader(stream)
                    ).use { reader ->
                        reader.readText()
                    }
                }
            }.getOrDefault("")

            if (success) {
                Log.d(
                    LOG_TAG,
                    "POST $endpoint succeeded ($responseCode)."
                )
            } else {
                Log.w(
                    LOG_TAG,
                    "POST $endpoint failed ($responseCode): $responseText"
                )
            }

            success
        } catch (exception: Exception) {
            Log.w(
                LOG_TAG,
                "POST $endpoint failed: " +
                        (exception.message
                            ?: exception.javaClass.simpleName),
                exception
            )
            false
        } finally {
            connection?.disconnect()
        }
    }
}