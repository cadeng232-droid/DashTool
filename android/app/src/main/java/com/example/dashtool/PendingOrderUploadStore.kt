package com.example.dashtool

import android.content.Context
import java.io.File

/**
 * Stores pending uploads in app-private storage.
 *
 * Users do not see these files in Documents or Downloads.
 * Android removes them when DashTool is uninstalled.
 */
internal object PendingOrderUploadStore {

    private const val DIRECTORY_NAME =
        "pending_order_uploads"

    private fun directory(
        context: Context
    ): File {
        val directory =
            File(
                context.filesDir,
                DIRECTORY_NAME
            )

        check(
            directory.exists() ||
                    directory.mkdirs()
        ) {
            "Could not create pending upload directory."
        }

        return directory
    }

    private fun safeOfferId(
        offerId: String
    ): String {
        return offerId.replace(
            Regex(
                """[^A-Za-z0-9._-]"""
            ),
            "_"
        )
    }

    fun pendingFile(
        context: Context,
        offerId: String
    ): File {
        return File(
            directory(
                context
            ),
            "${safeOfferId(offerId)}.json"
        )
    }

    fun save(
        context: Context,
        offerId: String,
        jsonText: String
    ): File {
        val targetFile =
            pendingFile(
                context =
                    context,

                offerId =
                    offerId
            )

        val temporaryFile =
            File(
                targetFile.parentFile,
                "${targetFile.name}.tmp"
            )

        temporaryFile.writeText(
            text =
                jsonText,

            charset =
                Charsets.UTF_8
        )

        if (
            targetFile.exists() &&
            !targetFile.delete()
        ) {
            temporaryFile.delete()

            error(
                "Could not replace pending upload ${targetFile.name}."
            )
        }

        if (
            !temporaryFile.renameTo(
                targetFile
            )
        ) {
            /*
             * Some storage implementations cannot rename
             * atomically. Fall back to a direct copy.
             */
            targetFile.writeText(
                text =
                    jsonText,

                charset =
                    Charsets.UTF_8
            )

            temporaryFile.delete()
        }

        return targetFile
    }

    fun listPending(
        context: Context
    ): List<File> {
        return directory(
            context
        )
            .listFiles { file ->
                file.isFile &&
                        file.extension.equals(
                            "json",
                            ignoreCase = true
                        )
            }
            ?.sortedBy {
                it.lastModified()
            }
            .orEmpty()
    }

    fun delete(
        pendingFile: File
    ): Boolean {
        return !pendingFile.exists() ||
                pendingFile.delete()
    }
}
