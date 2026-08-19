package com.example.dashtool

import android.content.Context
import android.util.Log
import java.io.File

object EngineConfigStore {

    private const val LOG_TAG =
        "DashToolEngineConfig"

    private const val DIRECTORY_NAME =
        "engine"

    private const val FILE_NAME =
        "current_engine_config.json"

    private const val TEMP_FILE_NAME =
        "current_engine_config.tmp"

    @Volatile
    private var cachedConfig:
            EngineConfig? = null

    @Synchronized
    fun load(
        context: Context
    ): EngineConfig {
        cachedConfig
            ?.let {
                return it
            }

        val stored =
            loadStoredOrNull(
                context
            )

        val selected =
            stored
                ?: EngineConfigDefaults
                    .baseline()

        cachedConfig =
            selected

        return selected
    }

    @Synchronized
    fun saveValidated(
        context: Context,
        jsonText: String
    ): EngineConfig {
        val incoming =
            EngineConfigParser.parse(
                jsonText
            )

        val existing =
            loadStoredOrNull(
                context
            )

        if (
            existing != null &&
            incoming.engineVersion <
                existing.engineVersion
        ) {
            Log.w(
                LOG_TAG,
                "Ignored older engine config v" +
                    incoming.engineVersion +
                    "; current version is v" +
                    existing.engineVersion +
                    "."
            )

            cachedConfig =
                existing

            return existing
        }

        val directory =
            configDirectory(
                context
            )

        directory.mkdirs()

        val destination =
            File(
                directory,
                FILE_NAME
            )

        val temporary =
            File(
                directory,
                TEMP_FILE_NAME
            )

        temporary.writeText(
            incoming.rawJson,
            Charsets.UTF_8
        )

        if (
            destination.exists() &&
            !destination.delete()
        ) {
            temporary.delete()

            throw IllegalStateException(
                "Could not replace the previous engine configuration."
            )
        }

        if (
            !temporary.renameTo(
                destination
            )
        ) {
            temporary.delete()

            throw IllegalStateException(
                "Could not finalize the engine configuration."
            )
        }

        cachedConfig =
            incoming

        Log.d(
            LOG_TAG,
            "Saved engine config v" +
                incoming.engineVersion +
                " to " +
                destination.absolutePath +
                "."
        )

        return incoming
    }

    @Synchronized
    fun clearCacheForTesting() {
        cachedConfig =
            null
    }

    fun storedFile(
        context: Context
    ): File {
        return File(
            configDirectory(
                context
            ),
            FILE_NAME
        )
    }

    private fun configDirectory(
        context: Context
    ): File {
        return File(
            context.applicationContext
                .filesDir,
            DIRECTORY_NAME
        )
    }

    private fun loadStoredOrNull(
        context: Context
    ): EngineConfig? {
        val file =
            storedFile(
                context
            )

        if (
            !file.exists()
        ) {
            return null
        }

        return runCatching {
            EngineConfigParser.parse(
                file.readText(
                    Charsets.UTF_8
                )
            )
        }
            .onFailure {
                exception ->

                Log.e(
                    LOG_TAG,
                    "Stored engine configuration is invalid; " +
                        "using the offline baseline.",
                    exception
                )
            }
            .getOrNull()
    }
}
