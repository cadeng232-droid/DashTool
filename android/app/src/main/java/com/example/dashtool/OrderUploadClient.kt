package com.example.dashtool

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OrderUploadAttempt(
    val successful: Boolean,
    val statusCode: Int?,
    val responseBody: String,
    val exception: Exception?
)

internal object OrderUploadClient {

    suspend fun upload(
        jsonText: String
    ): OrderUploadAttempt {
        return withContext(
            Dispatchers.IO
        ) {
            var connection:
                    HttpURLConnection? = null

            try {
                val requestBytes =
                    jsonText.toByteArray(
                        Charsets.UTF_8
                    )

                connection =
                    (
                            URL(
                                OrderUploadConfig
                                    .ordersUrl()
                            )
                                .openConnection()
                                    as HttpURLConnection
                            )
                        .apply {
                            requestMethod =
                                "POST"

                            connectTimeout =
                                OrderUploadConfig
                                    .CONNECT_TIMEOUT_MS

                            readTimeout =
                                OrderUploadConfig
                                    .READ_TIMEOUT_MS

                            doOutput =
                                true

                            doInput =
                                true

                            useCaches =
                                false

                            setRequestProperty(
                                "Content-Type",
                                "application/json; charset=utf-8"
                            )

                            setRequestProperty(
                                "Accept",
                                "application/json"
                            )

                            setFixedLengthStreamingMode(
                                requestBytes.size
                            )
                        }

                connection
                    .outputStream
                    .use {
                            output ->

                        output.write(
                            requestBytes
                        )
                    }

                val statusCode =
                    connection.responseCode

                val responseStream =
                    if (
                        statusCode in
                        200..299
                    ) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseBody =
                    responseStream
                        ?.bufferedReader(
                            Charsets.UTF_8
                        )
                        ?.use {
                            it.readText()
                        }
                        .orEmpty()

                OrderUploadAttempt(
                    successful =
                        statusCode in
                                200..299,

                    statusCode =
                        statusCode,

                    responseBody =
                        responseBody,

                    exception =
                        null
                )
            } catch (
                exception: Exception
            ) {
                OrderUploadAttempt(
                    successful =
                        false,

                    statusCode =
                        null,

                    responseBody =
                        "",

                    exception =
                        exception
                )
            } finally {
                connection?.disconnect()
            }
        }
    }
}
