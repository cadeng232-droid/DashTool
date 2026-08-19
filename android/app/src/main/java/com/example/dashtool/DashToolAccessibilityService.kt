package com.example.dashtool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale

// Accessibility Service is main lifecycle stage detector.
// Existing screenshot/OCR is fallback
@SuppressLint("AccessibilityPolicy")
class DashToolAccessibilityService :
    AccessibilityService() {

    companion object {

        const val ACTION_CAPTURE_SCREENSHOT =
            "com.example.dashtool.action.CAPTURE_ACCESSIBILITY_SCREENSHOT"

        const val ACTION_SCREENSHOT_READY =
            "com.example.dashtool.action.ACCESSIBILITY_SCREENSHOT_READY"

        const val ACTION_SCREENSHOT_FAILED =
            "com.example.dashtool.action.ACCESSIBILITY_SCREENSHOT_FAILED"

        const val EXTRA_SCREENSHOT_PATH =
            "accessibility_screenshot_path"

        const val EXTRA_SCREENSHOT_ERROR_CODE =
            "accessibility_screenshot_error_code"

        const val ACTION_ACCESSIBILITY_STAGE_DETECTED =
            "com.example.dashtool.action.ACCESSIBILITY_STAGE_DETECTED"

        const val EXTRA_LIFECYCLE_STAGE =
            "accessibility_lifecycle_stage"

        const val EXTRA_SCREEN_STATE =
            "accessibility_screen_state"

        const val EXTRA_NODE_TEXT =
            "accessibility_node_text"

        const val EXTRA_EVENT_WALL_TIME =
            "accessibility_event_wall_time"

        const val EXTRA_EVENT_ELAPSED_TIME =
            "accessibility_event_elapsed_time"

        private const val LOG_TAG =
            "DashToolAccessibility"

        private const val FIRST_NODE_READ_DELAY_MS =
            90L

        private const val CONFIRM_NODE_READ_DELAY_MS =
            320L

        private const val DUPLICATE_BROADCAST_WINDOW_MS =
            800L

        private const val MAX_NODES_TO_READ =
            1200

        private const val GOOGLE_MAPS_PACKAGE_NAME =
            "com.google.android.apps.maps"

        //A long-press inside DoorDash opens a location in Google Maps.
        private const val LONG_CLICK_MAPS_SUPPRESSION_MS =
            8000L

        @Volatile
        private var connected =
            false

        fun isConnected(): Boolean {
            return connected
        }
        //Ensures Dashtool enabled in settings
        fun isEnabled(
            context: Context
        ): Boolean {
            val accessibilityManager =
                context.getSystemService(
                    AccessibilityManager::class.java
                )

            val expectedComponent =
                ComponentName(
                    context,
                    DashToolAccessibilityService::class.java
                )

            return accessibilityManager
                .getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo
                        .FEEDBACK_ALL_MASK
                )
                .any { enabledService ->
                    ComponentName.unflattenFromString(
                        enabledService.id
                    ) == expectedComponent
                }
        }
    }

    private data class NodeClassification(
        val screenState: String,
        val lifecycleStage: String =
            ScreenCaptureService
                .LIFECYCLE_STAGE_NONE
    )

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var screenshotReceiverRegistered =
        false

    @Volatile
    private var screenshotInProgress =
        false

    private var lastBroadcastSignature =
        ""

    private var lastBroadcastElapsedTime =
        0L

    private var lastDoorDashLongClickElapsedTime =
        0L

    private val firstNodeReadRunnable =
        Runnable {
            inspectCurrentDoorDashWindow()
        }

    private val confirmNodeReadRunnable =
        Runnable {
            inspectCurrentDoorDashWindow()
        }

    private val firstGoogleMapsReadRunnable =
        Runnable {
            inspectCurrentGoogleMapsWindow()
        }

    private val confirmGoogleMapsReadRunnable =
        Runnable {
            inspectCurrentGoogleMapsWindow()
        }

    private val screenshotRequestReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    ACTION_CAPTURE_SCREENSHOT
                ) {
                    return
                }

                val scanMode =
                    intent.getStringExtra(
                        ScreenCaptureService
                            .EXTRA_SCAN_MODE
                    )
                        ?: ScreenCaptureService
                            .SCAN_MODE_FULL_OFFER

                captureScreenshotForOcr(
                    scanMode =
                        scanMode
                )
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        connected =
            true

        configureAccessibilityEvents()
        registerScreenshotReceiver()

        Log.d(
            LOG_TAG,
            "Accessibility service connected."
        )
    }

    private fun configureAccessibilityEvents() {
        val updatedInfo =
            serviceInfo.apply {
                eventTypes =
                    AccessibilityEvent
                        .TYPE_WINDOW_STATE_CHANGED or
                            AccessibilityEvent
                                .TYPE_WINDOW_CONTENT_CHANGED or
                            AccessibilityEvent
                                .TYPE_WINDOWS_CHANGED or
                            AccessibilityEvent
                                .TYPE_VIEW_CLICKED or
                            AccessibilityEvent
                                .TYPE_VIEW_LONG_CLICKED or
                            AccessibilityEvent
                                .TYPE_VIEW_SCROLLED or
                            AccessibilityEvent
                                .TYPE_VIEW_TEXT_CHANGED

                feedbackType =
                    AccessibilityServiceInfo
                        .FEEDBACK_GENERIC

                notificationTimeout =
                    50L

                flags =
                    flags or
                            AccessibilityServiceInfo
                                .FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo
                                .FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo
                                .FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }

        setServiceInfo(
            updatedInfo
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        val accessibilityEvent =
            event ?: return

        val eventPackage =
            accessibilityEvent
                .packageName
                ?.toString()

        if (
            isDoorDashPackage(
                eventPackage
            )
        ) {
            val eventElapsedTime =
                SystemClock.elapsedRealtime()

            if (
                accessibilityEvent.eventType ==
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            ) {
                lastDoorDashLongClickElapsedTime =
                    eventElapsedTime

                Log.d(
                    LOG_TAG,
                    "DoorDash long-click observed; Maps acceptance " +
                            "fallback temporarily suppressed."
                )
            }

            /*
             * This is the fastest and strongest acceptance signal. DoorDash can
             * launch external Google Maps immediately after this click, before
             * the delayed node-tree reads ever see Pickup from / Directions.
             * The OverlayService still requires a monitored pending offer, so an
             * unrelated DoorDash button named Accept cannot start a lifecycle.
             */
            if (
                isDoorDashAcceptClick(
                    accessibilityEvent
                )
            ) {
                Log.d(
                    LOG_TAG,
                    "DoorDash Accept button click detected."
                )

                broadcastAccessibilityClassification(
                    classification =
                        NodeClassification(
                            screenState =
                                ScreenCaptureService
                                    .SCREEN_STATE_ACTIVE_DELIVERY,

                            lifecycleStage =
                                ScreenCaptureService
                                    .LIFECYCLE_STAGE_TO_RESTAURANT
                        ),

                    completeNodeText =
                        "DoorDash Accept button click"
                )
            }

            /*
             * One DoorDash redraw can produce many events. Debouncing waits for
             * the node tree to settle, while the second read catches late Compose
             * updates. These remain useful for every later lifecycle stage.
             */
            mainHandler.removeCallbacks(
                firstNodeReadRunnable
            )

            mainHandler.removeCallbacks(
                confirmNodeReadRunnable
            )

            mainHandler.postDelayed(
                firstNodeReadRunnable,
                FIRST_NODE_READ_DELAY_MS
            )

            mainHandler.postDelayed(
                confirmNodeReadRunnable,
                CONFIRM_NODE_READ_DELAY_MS
            )

            return
        }

        if (
            isGoogleMapsPackage(
                eventPackage
            )
        ) {
            /*
             * External Maps is only a fallback. Merely opening Maps is NOT an
             * acceptance signal. We read the Maps node tree and require an active
             * turn-by-turn-navigation signature before broadcasting NAVIGATION.
             */
            mainHandler.removeCallbacks(
                firstGoogleMapsReadRunnable
            )

            mainHandler.removeCallbacks(
                confirmGoogleMapsReadRunnable
            )

            mainHandler.postDelayed(
                firstGoogleMapsReadRunnable,
                FIRST_NODE_READ_DELAY_MS
            )

            mainHandler.postDelayed(
                confirmGoogleMapsReadRunnable,
                CONFIRM_NODE_READ_DELAY_MS
            )
        }
    }

    private fun isDoorDashAcceptClick(
        event: AccessibilityEvent
    ): Boolean {
        if (
            event.eventType !=
            AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return false
        }

        val readableValues =
            linkedSetOf<String>()

        event.text.forEach {
                value ->

            addReadableValue(
                target =
                    readableValues,

                value =
                    value?.toString()
            )
        }

        addReadableValue(
            target =
                readableValues,

            value =
                event.contentDescription
                    ?.toString()
        )

        event.source?.let {
                sourceNode ->

            addReadableValue(
                target =
                    readableValues,

                value =
                    sourceNode.text
                        ?.toString()
            )

            addReadableValue(
                target =
                    readableValues,

                value =
                    sourceNode.contentDescription
                        ?.toString()
            )
        }

        return readableValues.any {
                value ->

            when (
                normalizeText(
                    value
                )
            ) {
                "accept",
                "accept offer",
                "accept order" ->
                    true

                else ->
                    false
            }
        }
    }

    private fun isDoorDashPackage(
        packageName: String?
    ): Boolean {
        return packageName
            ?.lowercase(
                Locale.US
            )
            ?.contains(
                "doordash"
            ) == true
    }

    private fun isGoogleMapsPackage(
        packageName: String?
    ): Boolean {
        return packageName
            ?.lowercase(
                Locale.US
            ) == GOOGLE_MAPS_PACKAGE_NAME
    }

    private fun inspectCurrentGoogleMapsWindow() {
        val rootNode =
            rootInActiveWindow
                ?: return

        if (
            !isGoogleMapsPackage(
                rootNode.packageName
                    ?.toString()
            )
        ) {
            return
        }

        val nowElapsedTime =
            SystemClock.elapsedRealtime()

        if (
            lastDoorDashLongClickElapsedTime > 0L &&
            nowElapsedTime -
            lastDoorDashLongClickElapsedTime <
            LONG_CLICK_MAPS_SUPPRESSION_MS
        ) {
            Log.d(
                LOG_TAG,
                "Google Maps navigation signal ignored because a " +
                        "recent DoorDash long-click could have opened Maps."
            )

            return
        }

        val completeNodeText =
            collectVisibleNodeText(
                rootNode
            )

        if (
            completeNodeText.isBlank()
        ) {
            return
        }

        if (
            !looksLikeActiveGoogleMapsNavigation(
                completeNodeText
            )
        ) {
            Log.d(
                LOG_TAG,
                "Google Maps is foreground, but it does not look like " +
                        "active turn-by-turn navigation."
            )

            return
        }

        Log.d(
            LOG_TAG,
            "Active Google Maps navigation detected."
        )

        broadcastAccessibilityClassification(
            classification =
                NodeClassification(
                    screenState =
                        ScreenCaptureService
                            .SCREEN_STATE_NAVIGATION
                ),

            completeNodeText =
                completeNodeText
        )
    }

    private fun looksLikeActiveGoogleMapsNavigation(
        completeText: String
    ): Boolean {
        val normalizedText =
            normalizeText(
                completeText
            )

        val hasExitControl =
            Regex(
                """\bexit(?:\s+navigation)?\b""",
                RegexOption.IGNORE_CASE
            )
                .containsMatchIn(
                    normalizedText
                )

        val hasNavigationMileage =
            Regex(
                """\b\d+(?:\.\d+)?\s*(?:mi|mile|miles)\b""",
                RegexOption.IGNORE_CASE
            )
                .containsMatchIn(
                    normalizedText
                )

        val hasRemainingTravelTime =
            Regex(
                """\b\d+\s*(?:min|mins|minute|minutes)\b""",
                RegexOption.IGNORE_CASE
            )
                .containsMatchIn(
                    normalizedText
                )

        val hasArrivalTime =
            Regex(
                """\b(?:1[0-2]|0?[1-9]):[0-5]\d(?:\s*[ap]\.?m\.?)?\b""",
                RegexOption.IGNORE_CASE
            )
                .containsMatchIn(
                    normalizedText
                )

        return hasExitControl &&
                hasNavigationMileage &&
                (
                        hasRemainingTravelTime ||
                                hasArrivalTime
                        )
    }

    private fun inspectCurrentDoorDashWindow() {
        val rootNode =
            rootInActiveWindow
                ?: return

        if (
            !isDoorDashPackage(
                rootNode.packageName
                    ?.toString()
            )
        ) {
            return
        }

        val completeNodeText =
            collectVisibleNodeText(
                rootNode
            )

        if (
            completeNodeText.isBlank()
        ) {
            Log.d(
                LOG_TAG,
                "DoorDash node tree contained no readable text."
            )

            return
        }

        val observedAtWallTimeMs =
            System.currentTimeMillis()

        /*
         * Capture DoorDash's live average-offer-wait range from Accessibility
         * even when the current screen is not one of the lifecycle screens.
         * This keeps the most recently exposed demand range available to the
         * scoring system without OCR.
         */
        DoorDashDemandTracker.observeDoorDashText(
            context = applicationContext,
            completeNodeText = completeNodeText,
            observedAtWallTimeMs = observedAtWallTimeMs
        )

        val classification =
            classifyDoorDashNodeText(
                completeNodeText
            )

        if (
            classification.screenState ==
            ScreenCaptureService
                .SCREEN_STATE_WAITING
        ) {
            DoorDashDemandTracker.onWaitingScreen(
                context = applicationContext,
                wallTimeMs = observedAtWallTimeMs
            )
        }

        if (
            classification.screenState ==
            ScreenCaptureService
                .SCREEN_STATE_UNKNOWN
        ) {
            Log.d(
                LOG_TAG,
                "No lifecycle marker in node text: " +
                        completeNodeText.take(
                            220
                        )
            )

            return
        }

        broadcastAccessibilityClassification(
            classification =
                classification,

            completeNodeText =
                completeNodeText
        )
    }

    private fun collectVisibleNodeText(
        rootNode: AccessibilityNodeInfo
    ): String {
        val collectedText =
            linkedSetOf<String>()

        val pendingNodes =
            ArrayDeque<AccessibilityNodeInfo>()

        pendingNodes.add(
            rootNode
        )

        var visitedNodes =
            0

        while (
            pendingNodes.isNotEmpty() &&
            visitedNodes < MAX_NODES_TO_READ
        ) {
            val node =
                pendingNodes.removeFirst()

            visitedNodes +=
                1

            addReadableValue(
                target =
                    collectedText,

                value =
                    node.text
                        ?.toString()
            )

            addReadableValue(
                target =
                    collectedText,

                value =
                    node.contentDescription
                        ?.toString()
            )

            for (
            childIndex in
            0 until node.childCount
            ) {
                node.getChild(
                    childIndex
                )
                    ?.let {
                            childNode ->

                        pendingNodes.addLast(
                            childNode
                        )
                    }
            }
        }

        return collectedText
            .joinToString(
                separator = "\n"
            )
    }

    private fun addReadableValue(
        target: MutableSet<String>,
        value: String?
    ) {
        value
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                target.add(
                    it
                )
            }
    }

    private fun classifyDoorDashNodeText(
        completeText: String
    ): NodeClassification {
        val normalizedText =
            normalizeText(
                completeText
            )

        fun containsAll(
            vararg markers: String
        ): Boolean {
            return markers.all {
                    marker ->

                normalizedText.contains(
                    marker
                )
            }
        }

        /*
         * Completed-order and normal searching screens.
         */
        if (
            containsAll(
                "this dash so far",
                "continue dashing"
            )
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_WAITING
            )
        }

        val waitingMarkers =
            listOf(
                "finding offers",
                "looking for orders",
                "searching for offers",
                "waiting for offers",
                "finding orders",
                "looking for offers"
            )

        if (
            waitingMarkers.any {
                    marker ->

                normalizedText.contains(
                    marker
                )
            }
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_WAITING
            )
        }

        /*
         * Customer arrival must be checked before the more
         * general Directions rule. DoorDash may expose either
         * Continue + Directions or Handed order to customer.
         */
        if (
            containsAll(
                "continue",
                "directions"
            ) ||
            normalizedText.contains(
                "handed order to customer"
            )
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_AT_CUSTOMER
            )
        }

        /*
         * DoorDash currently exposes Start pickup at the
         * restaurant. The additional exact restaurant
         * variants are harmless fallbacks for UI wording.
         */
        val hasRestaurantArrivalMarker =
            normalizedText.contains(
                "start pickup"
            ) ||
                    normalizedText.contains(
                        "arrived at restaurant"
                    ) ||
                    normalizedText.contains(
                        "arrived at store"
                    )

        if (
            hasRestaurantArrivalMarker
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_AT_RESTAURANT
            )
        }

        if (
            normalizedText.contains(
                "pickup from"
            )
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_TO_RESTAURANT
            )
        }

        if (
            containsAll(
                "deliver to",
                "directions"
            ) ||
            normalizedText.contains(
                "directions"
            )
        ) {
            return NodeClassification(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_TO_CUSTOMER
            )
        }

        return NodeClassification(
            screenState =
                ScreenCaptureService
                    .SCREEN_STATE_UNKNOWN
        )
    }

    private fun normalizeText(
        text: String
    ): String {
        return text
            .lowercase(
                Locale.US
            )
            .replace(
                '’',
                '\''
            )
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
    }

    private fun broadcastAccessibilityClassification(
        classification: NodeClassification,
        completeNodeText: String
    ) {
        val elapsedTime =
            SystemClock.elapsedRealtime()

        val signature =
            classification.screenState +
                    "|" +
                    classification.lifecycleStage

        if (
            signature ==
            lastBroadcastSignature &&
            elapsedTime -
            lastBroadcastElapsedTime <
            DUPLICATE_BROADCAST_WINDOW_MS
        ) {
            return
        }

        lastBroadcastSignature =
            signature

        lastBroadcastElapsedTime =
            elapsedTime

        val resultIntent =
            Intent(
                ACTION_ACCESSIBILITY_STAGE_DETECTED
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    EXTRA_SCREEN_STATE,
                    classification.screenState
                )

                putExtra(
                    EXTRA_LIFECYCLE_STAGE,
                    classification.lifecycleStage
                )

                putExtra(
                    EXTRA_NODE_TEXT,
                    completeNodeText
                )

                putExtra(
                    EXTRA_EVENT_WALL_TIME,
                    System.currentTimeMillis()
                )

                putExtra(
                    EXTRA_EVENT_ELAPSED_TIME,
                    elapsedTime
                )
            }

        sendBroadcast(
            resultIntent
        )

        Log.d(
            LOG_TAG,
            "Accessibility classified DoorDash as " +
                    "${classification.screenState} / " +
                    classification.lifecycleStage
        )
    }

    private fun registerScreenshotReceiver() {
        if (
            screenshotReceiverRegistered
        ) {
            return
        }

        ContextCompat.registerReceiver(
            this,
            screenshotRequestReceiver,
            IntentFilter(
                ACTION_CAPTURE_SCREENSHOT
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        screenshotReceiverRegistered =
            true
    }

    private fun captureScreenshotForOcr(
        scanMode: String
    ) {
        if (
            screenshotInProgress
        ) {
            sendScreenshotFailure(
                scanMode =
                    scanMode,

                errorCode =
                    AccessibilityService
                        .ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
            )

            return
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {
            sendScreenshotFailure(
                scanMode =
                    scanMode,

                errorCode =
                    AccessibilityService
                        .ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            )

            return
        }

        screenshotInProgress =
            true

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object :
                    TakeScreenshotCallback {

                    override fun onSuccess(
                        screenshot:
                        ScreenshotResult
                    ) {
                        screenshotInProgress =
                            false

                        saveScreenshotAndBroadcast(
                            screenshot =
                                screenshot,

                            scanMode =
                                scanMode
                        )
                    }

                    override fun onFailure(
                        errorCode: Int
                    ) {
                        screenshotInProgress =
                            false

                        sendScreenshotFailure(
                            scanMode =
                                scanMode,

                            errorCode =
                                errorCode
                        )
                    }
                }
            )
        } catch (
            exception: Exception
        ) {
            screenshotInProgress =
                false

            Log.e(
                LOG_TAG,
                "takeScreenshot failed.",
                exception
            )

            sendScreenshotFailure(
                scanMode =
                    scanMode,

                errorCode =
                    AccessibilityService
                        .ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            )
        }
    }

    private fun saveScreenshotAndBroadcast(
        screenshot: ScreenshotResult,
        scanMode: String
    ) {
        var bitmap:
                Bitmap? = null

        try {
            val hardwareBuffer =
                screenshot.hardwareBuffer

            bitmap =
                Bitmap.wrapHardwareBuffer(
                    hardwareBuffer,
                    screenshot.colorSpace
                )
                    ?.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )
                    ?: error(
                        "Could not convert screenshot buffer."
                    )

            hardwareBuffer.close()

            val screenshotDirectory =
                File(
                    cacheDir,
                    "accessibility_screenshots"
                ).apply {
                    mkdirs()
                }

            val screenshotFile =
                File(
                    screenshotDirectory,
                    "dashtool_${SystemClock.elapsedRealtime()}.png"
                )

            FileOutputStream(
                screenshotFile
            ).use {
                    outputStream ->

                val saved =
                    bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        outputStream
                    )

                if (
                    !saved
                ) {
                    error(
                        "Could not save screenshot."
                    )
                }
            }

            val readyIntent =
                Intent(
                    ACTION_SCREENSHOT_READY
                ).apply {
                    setPackage(
                        packageName
                    )

                    putExtra(
                        EXTRA_SCREENSHOT_PATH,
                        screenshotFile.absolutePath
                    )

                    putExtra(
                        ScreenCaptureService
                            .EXTRA_SCAN_MODE,
                        scanMode
                    )
                }

            sendBroadcast(
                readyIntent
            )
        } catch (
            exception: Exception
        ) {
            Log.e(
                LOG_TAG,
                "Could not save accessibility screenshot.",
                exception
            )

            sendScreenshotFailure(
                scanMode =
                    scanMode,

                errorCode =
                    AccessibilityService
                        .ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            )
        } finally {
            bitmap
                ?.takeIf {
                    !it.isRecycled
                }
                ?.recycle()
        }
    }

    private fun sendScreenshotFailure(
        scanMode: String,
        errorCode: Int
    ) {
        val failureIntent =
            Intent(
                ACTION_SCREENSHOT_FAILED
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    EXTRA_SCREENSHOT_ERROR_CODE,
                    errorCode
                )

                putExtra(
                    ScreenCaptureService
                        .EXTRA_SCAN_MODE,
                    scanMode
                )
            }

        sendBroadcast(
            failureIntent
        )
    }

    override fun onInterrupt() {
        Log.w(
            LOG_TAG,
            "Accessibility service interrupted."
        )
    }

    override fun onDestroy() {
        connected =
            false

        mainHandler.removeCallbacksAndMessages(
            null
        )

        if (
            screenshotReceiverRegistered
        ) {
            unregisterReceiver(
                screenshotRequestReceiver
            )

            screenshotReceiverRegistered =
                false
        }

        super.onDestroy()
    }
}