package com.example.dashtool

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

class DoorDashNotificationListenerService :
    NotificationListenerService() {

    companion object {
        private const val DOORDASH_PACKAGE_NAME =
            "com.doordash.driverapp"

        const val ACTION_AUTO_SCAN_REQUEST =
            "com.example.dashtool.action.AUTO_SCAN_REQUEST"

        private const val AUTO_SCAN_DELAY_MS =
            650L

        private const val DUPLICATE_WINDOW_MS =
            3_000L

        private const val LOG_TAG =
            "DashToolNotification"
    }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var lastAutomaticScanTime =
        0L

    private var pendingScanRunnable:
            Runnable? = null

    override fun onNotificationPosted(
        statusBarNotification:
        StatusBarNotification?
    ) {
        val notificationEntry =
            statusBarNotification ?: return

        if (
            notificationEntry.packageName !=
            DOORDASH_PACKAGE_NAME
        ) {
            return
        }

        val notificationText =
            collectNotificationText(
                notificationEntry.notification
            )

        Log.d(
            LOG_TAG,
            buildString {
                appendLine(
                    "DoorDash notification received."
                )

                appendLine(
                    "Key: ${notificationEntry.key}"
                )

                append(
                    "Text: $notificationText"
                )
            }
        )

        if (
            !looksLikeNewOfferNotification(
                notificationText
            )
        ) {
            Log.d(
                LOG_TAG,
                "Notification did not look like a new offer."
            )

            return
        }

        if (
            !isScreenReaderActive()
        ) {
            Log.d(
                LOG_TAG,
                "Automatic scan skipped because " +
                        "the accessibility scanner is inactive."
            )

            return
        }

        val currentTime =
            SystemClock.elapsedRealtime()

        if (
            currentTime -
            lastAutomaticScanTime <
            DUPLICATE_WINDOW_MS
        ) {
            Log.d(
                LOG_TAG,
                "Duplicate DoorDash notification ignored."
            )

            return
        }

        lastAutomaticScanTime =
            currentTime

        pendingScanRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        val scanRunnable =
            Runnable {
                pendingScanRunnable = null

                if (
                    !isScreenReaderActive()
                ) {
                    Log.d(
                        LOG_TAG,
                        "Delayed automatic scan canceled " +
                                "because the scanner stopped."
                    )

                    return@Runnable
                }

                Log.d(
                    LOG_TAG,
                    "Requesting one automatic offer scan."
                )

                val automaticScanIntent =
                    Intent(
                        ACTION_AUTO_SCAN_REQUEST
                    ).apply {
                        setPackage(
                            packageName
                        )
                    }

                sendBroadcast(
                    automaticScanIntent
                )
            }

        pendingScanRunnable =
            scanRunnable

        mainHandler.postDelayed(
            scanRunnable,
            AUTO_SCAN_DELAY_MS
        )
    }

    private fun collectNotificationText(
        notification: Notification
    ): String {
        val extras =
            notification.extras

        val textParts =
            mutableListOf<String>()

        val textKeys =
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT
            )

        textKeys.forEach { key ->
            extras
                .getCharSequence(key)
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    textParts.add(it)
                }
        }

        extras
            .getCharSequenceArray(
                Notification.EXTRA_TEXT_LINES
            )
            ?.forEach { line ->
                line
                    ?.toString()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        textParts.add(it)
                    }
            }

        return textParts
            .distinct()
            .joinToString(
                separator = " | "
            )
    }

    private fun looksLikeNewOfferNotification(
        notificationText: String
    ): Boolean {
        val normalizedText =
            notificationText
                .lowercase(
                    Locale.US
                )
                .replace(
                    Regex("""\s+"""),
                    " "
                )
                .trim()

        if (
            normalizedText.isBlank()
        ) {
            return false
        }

        val directOfferMarkers =
            listOf(
                "new order",
                "new offer",
                "new delivery",
                "order available",
                "offer available",
                "delivery opportunity",
                "new opportunity",
                "new dash offer"
            )

        if (
            directOfferMarkers.any {
                normalizedText.contains(it)
            }
        ) {
            return true
        }

        val containsOrderLanguage =
            normalizedText.contains(
                "order"
            ) ||
                    normalizedText.contains(
                        "offer"
                    )

        val containsOfferAction =
            normalizedText.contains(
                "go to"
            ) ||
                    normalizedText.contains(
                        "accept"
                    ) ||
                    normalizedText.contains(
                        "available"
                    )

        return containsOrderLanguage &&
                containsOfferAction
    }

    private fun isScreenReaderActive(): Boolean {
        val dashToolWasStarted =
            getSharedPreferences(
                ScreenCaptureService.PREFS_NAME,
                MODE_PRIVATE
            )
                .getBoolean(
                    ScreenCaptureService
                        .KEY_READER_ACTIVE,
                    false
                )

        return dashToolWasStarted &&
                DashToolAccessibilityService.isConnected()
    }

    override fun onDestroy() {
        pendingScanRunnable?.let {
            mainHandler.removeCallbacks(it)
        }

        pendingScanRunnable = null

        super.onDestroy()
    }
}