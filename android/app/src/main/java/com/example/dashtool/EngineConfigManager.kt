package com.example.dashtool

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class EngineConfigSyncStatus {
    DOWNLOADED,
    UNCHANGED,
    USING_CACHED,
    USING_BASELINE
}

data class EngineConfigSyncResult(
    val status: EngineConfigSyncStatus,
    val config: EngineConfig,
    val message: String
)

object EngineConfigManager {

    private const val LOG_TAG =
        "DashToolEngineConfig"

    suspend fun refresh(
        context: Context
    ): EngineConfigSyncResult {
        return withContext(
            Dispatchers.IO
        ) {
            val applicationContext =
                context.applicationContext

            val before =
                EngineConfigStore.load(
                    applicationContext
                )

            runCatching {
                EngineConfigClient
                    .downloadLatest()
            }
                .fold(
                    onSuccess = {
                        download ->

                        val saved =
                            EngineConfigStore
                                .saveValidated(
                                    context =
                                        applicationContext,

                                    jsonText =
                                        download.jsonText
                                )

                        val status =
                            if (
                                saved.engineVersion >
                                    before.engineVersion ||
                                before.status ==
                                    "offline_baseline"
                            ) {
                                EngineConfigSyncStatus
                                    .DOWNLOADED
                            } else {
                                EngineConfigSyncStatus
                                    .UNCHANGED
                            }

                        val message =
                            when (
                                status
                            ) {
                                EngineConfigSyncStatus
                                    .DOWNLOADED ->

                                    "Downloaded engine config v" +
                                        saved.engineVersion +
                                        "."

                                else ->

                                    "Engine config v" +
                                        saved.engineVersion +
                                        " is current."
                            }

                        Log.d(
                            LOG_TAG,
                            message
                        )

                        EngineConfigSyncResult(
                            status =
                                status,

                            config =
                                saved,

                            message =
                                message
                        )
                    },

                    onFailure = {
                        exception ->

                        val fallback =
                            EngineConfigStore.load(
                                applicationContext
                            )

                        val hasStoredFile =
                            EngineConfigStore
                                .storedFile(
                                    applicationContext
                                )
                                .exists()

                        val status =
                            if (
                                hasStoredFile
                            ) {
                                EngineConfigSyncStatus
                                    .USING_CACHED
                            } else {
                                EngineConfigSyncStatus
                                    .USING_BASELINE
                            }

                        val message =
                            if (
                                hasStoredFile
                            ) {
                                "Server unavailable; using cached engine config v" +
                                    fallback.engineVersion +
                                    "."
                            } else {
                                "Server unavailable; using offline baseline engine config v" +
                                    fallback.engineVersion +
                                    "."
                            }

                        Log.w(
                            LOG_TAG,
                            message,
                            exception
                        )

                        EngineConfigSyncResult(
                            status =
                                status,

                            config =
                                fallback,

                            message =
                                message
                        )
                    }
                )
        }
    }
}
