package com.example.dashtool

import java.net.HttpURLConnection
import java.net.URL

object EngineConfigClient {

    private const val CONNECT_TIMEOUT_MS =
        2_500

    private const val READ_TIMEOUT_MS =
        4_000

    private const val MAX_RESPONSE_CHARACTERS =
        2_000_000

    fun downloadLatest():
            EngineConfigDownload {
        val connection =
            URL(
                OrderUploadConfig
                    .engineConfigUrl()
            )
                .openConnection()
                    as HttpURLConnection

        try {
            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val responseCode =
                connection.responseCode

            val responseStream =
                if (
                    responseCode in
                        200..299
                ) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseText =
                responseStream
                    ?.bufferedReader(
                        Charsets.UTF_8
                    )
                    ?.use {
                        reader ->

                        reader.readText()
                    }
                    .orEmpty()

            require(
                responseText.length <=
                    MAX_RESPONSE_CHARACTERS
            ) {
                "Engine-config response is unexpectedly large."
            }

            if (
                responseCode !in
                    200..299
            ) {
                throw IllegalStateException(
                    "Engine-config request failed with HTTP " +
                        responseCode +
                        ": " +
                        responseText.take(
                            300
                        )
                )
            }

            val parsed =
                EngineConfigParser.parse(
                    responseText
                )

            return EngineConfigDownload(
                config =
                    parsed,

                jsonText =
                    responseText
            )
        } finally {
            connection.disconnect()
        }
    }
}

data class EngineConfigDownload(
    val config: EngineConfig,
    val jsonText: String
)
