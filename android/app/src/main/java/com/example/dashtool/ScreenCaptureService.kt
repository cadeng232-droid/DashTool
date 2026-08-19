package com.example.dashtool

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.dashtool.data.RouteSource
import com.example.dashtool.data.RouteStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

class ScreenCaptureService : Service() {

    companion object {

        const val EXTRA_RESULT_CODE =
            "screen_capture_result_code"

        const val EXTRA_RESULT_DATA =
            "screen_capture_result_data"

        const val ACTION_SCAN_NOW =
            "com.example.dashtool.action.SCAN_NOW"

        const val ACTION_START_ACCESSIBILITY_PROCESSOR =
            "com.example.dashtool.action.START_ACCESSIBILITY_PROCESSOR"

        const val ACTION_SCAN_RESULT =
            "com.example.dashtool.action.SCAN_RESULT"

        /*
         * Determines whether a scan should fully parse
         * an offer or only classify the DoorDash screen.
         */
        const val EXTRA_SCAN_MODE =
            "scan_mode"

        const val SCAN_MODE_FULL_OFFER =
            "full_offer"

        const val SCAN_MODE_CLASSIFY_ONLY =
            "classify_only"

        const val EXTRA_RESTAURANT =
            "scan_restaurant"

        const val EXTRA_RESTAURANT_PLACE_ID =
            "scan_restaurant_place_id"

        const val EXTRA_PAY =
            "scan_pay"

        const val EXTRA_MILES =
            "scan_miles"

        const val EXTRA_SCAN_STATUS =
            "scan_status"

        const val EXTRA_TIME_TO_RESTAURANT =
            "time_to_restaurant_minutes"

        const val EXTRA_DISTANCE_TO_RESTAURANT =
            "distance_to_restaurant_miles"

        const val EXTRA_ROUTE_CAPTURED_AT_WALL_TIME =
            "route_captured_at_wall_time"

        const val EXTRA_ROUTE_SOURCE =
            "route_source"

        const val EXTRA_ROUTE_STATUS =
            "route_status"

        const val EXTRA_SCREEN_STATE =
            "screen_state"

        const val EXTRA_LIFECYCLE_STAGE =
            "lifecycle_stage"

        const val LIFECYCLE_STAGE_NONE =
            "none"

        const val LIFECYCLE_STAGE_TO_RESTAURANT =
            "to_restaurant"

        const val LIFECYCLE_STAGE_AT_RESTAURANT =
            "at_restaurant"

        const val LIFECYCLE_STAGE_TO_CUSTOMER =
            "to_customer"

        const val LIFECYCLE_STAGE_AT_CUSTOMER =
            "at_customer"

        const val SCREEN_STATE_OFFER =
            "offer_available"

        const val SCREEN_STATE_WAITING =
            "waiting"

        const val SCREEN_STATE_ACTIVE_DELIVERY =
            "active_delivery"

        const val SCREEN_STATE_NAVIGATION =
            "navigation"

        const val SCREEN_STATE_UNKNOWN =
            "unknown"

        const val PREFS_NAME =
            "dash_tool_scan"

        const val KEY_READER_ACTIVE =
            "reader_active"

        const val KEY_RESTAURANT =
            "restaurant"

        const val KEY_RESTAURANT_PLACE_ID =
            "restaurant_place_id"

        const val KEY_PAY =
            "pay"

        const val KEY_MILES =
            "miles"

        const val KEY_TIME_TO_RESTAURANT =
            "saved_time_to_restaurant"

        const val KEY_DISTANCE_TO_RESTAURANT =
            "saved_distance_to_restaurant"

        const val KEY_HAS_GOOGLE_ROUTE =
            "saved_has_google_route"

        const val KEY_ROUTE_CAPTURED_AT_WALL_TIME =
            "saved_route_captured_at_wall_time"

        const val KEY_ROUTE_SOURCE =
            "saved_route_source"

        const val KEY_ROUTE_STATUS =
            "saved_route_status"

        private const val CHANNEL_ID =
            "screen_capture_channel"

        private const val NOTIFICATION_ID =
            2

        private const val LOG_TAG =
            "DashToolOCR"

        /*
         * Reads only the bottom half of the screen.
         * The overlay is constrained to the top half,
         * so DashTool never OCRs its own popup.
         */
        private const val BOTTOM_CROP_START_FRACTION =
            0.50f

        private const val SCAN_TIMEOUT_MS =
            12_000L
    }

    private data class ParsedOffer(
        val restaurant: String,
        val pay: String,
        val miles: String
    )

    private data class OfferSignals(
        val pay: String?,
        val miles: String?
    ) {
        val isValidOffer: Boolean
            get() =
                pay != null &&
                        miles != null
    }

    private data class ScreenClassification(
        val screenState: String,
        val lifecycleStage: String =
            LIFECYCLE_STAGE_NONE
    )

    private var mediaProjection:
            MediaProjection? = null

    private var virtualDisplay:
            VirtualDisplay? = null

    private var imageReader:
            ImageReader? = null

    private lateinit var backgroundThread:
            HandlerThread

    private lateinit var backgroundHandler:
            Handler

    private var backgroundInfrastructureReady =
        false

    @Volatile
    private var captureReady =
        false

    @Volatile
    private var scanRequested =
        false

    @Volatile
    private var ocrInProgress =
        false

    @Volatile
    private var nextScanId =
        0L

    @Volatile
    private var activeScanId =
        0L

    /*
     * requestedScanMode belongs to the next frame that
     * has been requested.
     *
     * activeScanMode belongs to the OCR operation that
     * is currently running.
     */
    @Volatile
    private var requestedScanMode =
        SCAN_MODE_FULL_OFFER

    @Volatile
    private var activeScanMode =
        SCAN_MODE_FULL_OFFER

    private var scanTimeoutRunnable:
            Runnable? = null

    private var pendingParsedOffer:
            ParsedOffer? = null

    private var pendingRouteCapturedAtWallTime:
            Long? = null

    private var pendingOfferMapMarkers:
            DoorDashOfferMapLocator.Result? = null

    private var pendingOfferMapCapturedAtWallTime:
            Long? = null

    private val textRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private lateinit var travelEstimator:
            GoogleTravelEstimator

    private lateinit var customerMapCoordinateEstimator:
            CustomerMapCoordinateEstimator

    private val scanRequestReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                when (
                    intent?.action
                ) {
                    ACTION_SCAN_NOW -> {
                        requestScan(
                            scanMode =
                                intent.getStringExtra(
                                    EXTRA_SCAN_MODE
                                ) ?: SCAN_MODE_FULL_OFFER
                        )
                    }

                    DashToolAccessibilityService
                        .ACTION_SCREENSHOT_READY -> {
                        val screenshotPath =
                            intent.getStringExtra(
                                DashToolAccessibilityService
                                    .EXTRA_SCREENSHOT_PATH
                            )

                        val scanMode =
                            intent.getStringExtra(
                                EXTRA_SCAN_MODE
                            ) ?: SCAN_MODE_FULL_OFFER

                        if (screenshotPath != null) {
                            backgroundHandler.post {
                                processAccessibilityScreenshot(
                                    screenshotPath =
                                        screenshotPath,
                                    scanMode =
                                        scanMode
                                )
                            }
                        } else {
                            handleAccessibilityScreenshotFailure(
                                scanMode =
                                    scanMode,
                                errorCode =
                                    -1
                            )
                        }
                    }

                    DashToolAccessibilityService
                        .ACTION_SCREENSHOT_FAILED -> {
                        handleAccessibilityScreenshotFailure(
                            scanMode =
                                intent.getStringExtra(
                                    EXTRA_SCAN_MODE
                                ) ?: SCAN_MODE_FULL_OFFER,
                            errorCode =
                                intent.getIntExtra(
                                    DashToolAccessibilityService
                                        .EXTRA_SCREENSHOT_ERROR_CODE,
                                    -1
                                )
                        )
                    }
                }
            }
        }

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                setReaderActive(
                    false
                )

                clearCaptureResources(
                    stopProjection = false
                )

                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()

        travelEstimator =
            GoogleTravelEstimator(
                this
            )

        customerMapCoordinateEstimator =
            CustomerMapCoordinateEstimator(
                this
            )

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(
                    R.string.screen_reader_waiting
                )
            )
        )

        backgroundThread =
            HandlerThread(
                "DashToolScreenCapture"
            )

        backgroundThread.start()

        backgroundHandler =
            Handler(
                backgroundThread.looper
            )

        backgroundInfrastructureReady =
            true

        val scanIntentFilter =
            IntentFilter(
                ACTION_SCAN_NOW
            ).apply {
                addAction(
                    DashToolAccessibilityService
                        .ACTION_SCREENSHOT_READY
                )

                addAction(
                    DashToolAccessibilityService
                        .ACTION_SCREENSHOT_FAILED
                )
            }

        ContextCompat.registerReceiver(
            this,
            scanRequestReceiver,
            scanIntentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {
            ACTION_SCAN_NOW -> {
                requestScan(
                    scanMode =
                        intent.getStringExtra(
                            EXTRA_SCAN_MODE
                        ) ?: SCAN_MODE_FULL_OFFER
                )

                return START_STICKY
            }

            ACTION_START_ACCESSIBILITY_PROCESSOR -> {
                val accessibilityEnabled =
                    DashToolAccessibilityService
                        .isEnabled(
                            this
                        )

                setReaderActive(
                    accessibilityEnabled
                )

                updateNotification(
                    if (accessibilityEnabled) {
                        getString(
                            R.string.screen_reader_ready
                        )
                    } else {
                        getString(
                            R.string
                                .screen_reader_permission_missing
                        )
                    }
                )

                return START_STICKY
            }

            DashToolAccessibilityService
                .ACTION_SCREENSHOT_READY -> {
                val screenshotPath =
                    intent.getStringExtra(
                        DashToolAccessibilityService
                            .EXTRA_SCREENSHOT_PATH
                    )

                val scanMode =
                    intent.getStringExtra(
                        EXTRA_SCAN_MODE
                    ) ?: SCAN_MODE_FULL_OFFER

                if (screenshotPath != null) {
                    backgroundHandler.post {
                        processAccessibilityScreenshot(
                            screenshotPath =
                                screenshotPath,
                            scanMode =
                                scanMode
                        )
                    }
                } else {
                    handleAccessibilityScreenshotFailure(
                        scanMode = scanMode,
                        errorCode = -1
                    )
                }

                return START_STICKY
            }

            DashToolAccessibilityService
                .ACTION_SCREENSHOT_FAILED -> {
                handleAccessibilityScreenshotFailure(
                    scanMode =
                        intent.getStringExtra(
                            EXTRA_SCAN_MODE
                        ) ?: SCAN_MODE_FULL_OFFER,
                    errorCode =
                        intent.getIntExtra(
                            DashToolAccessibilityService
                                .EXTRA_SCREENSHOT_ERROR_CODE,
                            -1
                        )
                )

                return START_STICKY
            }
        }

        val accessibilityEnabled =
            DashToolAccessibilityService
                .isEnabled(
                    this
                )

        setReaderActive(
            accessibilityEnabled
        )

        updateNotification(
            if (accessibilityEnabled) {
                getString(
                    R.string.screen_reader_ready
                )
            } else {
                getString(
                    R.string
                        .screen_reader_permission_missing
                )
            }
        )

        return START_STICKY
    }

    private fun startScreenCapture() {
        val projection =
            mediaProjection
                ?: return

        val windowManager =
            getSystemService(
                WindowManager::class.java
            )

        val screenBounds =
            windowManager
                .maximumWindowMetrics
                .bounds

        val screenWidth =
            screenBounds.width()

        val screenHeight =
            screenBounds.height()

        val screenDensity =
            resources
                .configuration
                .densityDpi

        imageReader =
            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

        imageReader
            ?.setOnImageAvailableListener(
                { reader ->
                    handleAvailableImage(
                        reader = reader,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                },
                backgroundHandler
            )

        virtualDisplay =
            projection.createVirtualDisplay(
                "DashToolScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )

        backgroundHandler.postDelayed(
            {
                captureReady =
                    true

                setReaderActive(
                    true
                )

                updateNotification(
                    getString(
                        R.string.screen_reader_ready
                    )
                )
            },
            750L
        )
    }

    private fun requestScan(
        scanMode: String =
            SCAN_MODE_FULL_OFFER
    ) {
        val normalizedScanMode =
            if (
                scanMode ==
                SCAN_MODE_CLASSIFY_ONLY
            ) {
                SCAN_MODE_CLASSIFY_ONLY
            } else {
                SCAN_MODE_FULL_OFFER
            }

        if (
            !DashToolAccessibilityService
                .isConnected()
        ) {
            activeScanMode =
                normalizedScanMode

            sendScanResult(
                restaurant =
                    getString(
                        R.string.restaurant_not_found
                    ),

                pay =
                    getString(
                        R.string.pay_not_found
                    ),

                miles =
                    getString(
                        R.string.mileage_not_found
                    ),

                status =
                    "Accessibility screenshot service is not connected",

                screenState =
                    SCREEN_STATE_UNKNOWN,

                routeSource =
                    RouteSource.NOT_AVAILABLE,

                routeStatus =
                    RouteStatus.NOT_AVAILABLE
            )

            return
        }

        if (
            scanRequested ||
            ocrInProgress
        ) {
            Log.d(
                LOG_TAG,
                "Scan ignored because another scan is active."
            )

            return
        }

        requestedScanMode =
            normalizedScanMode

        scanRequested =
            true

        updateNotification(
            if (
                normalizedScanMode ==
                SCAN_MODE_CLASSIFY_ONLY
            ) {
                "Checking order state"
            } else {
                getString(
                    R.string.scan_status_scanning
                )
            }
        )

        val screenshotRequest =
            Intent(
                DashToolAccessibilityService
                    .ACTION_CAPTURE_SCREENSHOT
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    EXTRA_SCAN_MODE,
                    normalizedScanMode
                )
            }

        sendBroadcast(
            screenshotRequest
        )
    }

    private fun processAccessibilityScreenshot(
        screenshotPath: String,
        scanMode: String
    ) {
        val screenshotFile =
            File(
                screenshotPath
            )

        if (
            !scanRequested ||
            ocrInProgress
        ) {
            screenshotFile.delete()
            return
        }

        requestedScanMode =
            if (
                scanMode ==
                SCAN_MODE_CLASSIFY_ONLY
            ) {
                SCAN_MODE_CLASSIFY_ONLY
            } else {
                SCAN_MODE_FULL_OFFER
            }

        scanRequested =
            false

        val scanId =
            beginScan(
                scanMode =
                    requestedScanMode
            )

        var fullScreenBitmapToRecycle:
                Bitmap? = null

        var croppedBitmapToRecycle:
                Bitmap? = null

        try {
            val decodedBitmap =
                BitmapFactory.decodeFile(
                    screenshotFile.absolutePath
                )
                    ?: error(
                        "Could not decode accessibility screenshot."
                    )

            fullScreenBitmapToRecycle =
                decodedBitmap

            val cropStartY =
                (
                        decodedBitmap.height *
                                BOTTOM_CROP_START_FRACTION
                        )
                    .toInt()
                    .coerceIn(
                        0,
                        decodedBitmap.height - 1
                    )

            val croppedBitmap =
                Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    cropStartY,
                    decodedBitmap.width,
                    decodedBitmap.height -
                            cropStartY
                )

            croppedBitmapToRecycle =
                croppedBitmap

            runTextRecognition(
                scanId =
                    scanId,
                fullScreenBitmap =
                    decodedBitmap,
                croppedBitmap =
                    croppedBitmap
            )

            /*
             * runTextRecognition owns both bitmaps from
             * this point and recycles them after ML Kit
             * finishes.
             */
            fullScreenBitmapToRecycle =
                null

            croppedBitmapToRecycle =
                null
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "Could not process accessibility screenshot.",
                exception
            )

            if (
                isCurrentScan(
                    scanId
                )
            ) {
                sendScanResult(
                    restaurant =
                        getString(
                            R.string.restaurant_not_found
                        ),

                    pay =
                        getString(
                            R.string.pay_not_found
                        ),

                    miles =
                        getString(
                            R.string.mileage_not_found
                        ),

                    status =
                        "Accessibility screenshot could not be processed",

                    screenState =
                        SCREEN_STATE_UNKNOWN,

                    routeSource =
                        RouteSource.NOT_AVAILABLE,

                    routeStatus =
                        RouteStatus.FAILED
                )

                finishScan(
                    scanId
                )
            }
        } finally {
            croppedBitmapToRecycle
                ?.takeIf {
                    !it.isRecycled
                }
                ?.recycle()

            fullScreenBitmapToRecycle
                ?.takeIf {
                    !it.isRecycled
                }
                ?.recycle()

            screenshotFile.delete()
        }
    }

    private fun handleAccessibilityScreenshotFailure(
        scanMode: String,
        errorCode: Int
    ) {
        requestedScanMode =
            if (
                scanMode ==
                SCAN_MODE_CLASSIFY_ONLY
            ) {
                SCAN_MODE_CLASSIFY_ONLY
            } else {
                SCAN_MODE_FULL_OFFER
            }

        activeScanMode =
            requestedScanMode

        scanRequested =
            false

        val status =
            "Screenshot unavailable (error $errorCode)"

        sendPreviousResult(
            status =
                status,
            screenState =
                SCREEN_STATE_UNKNOWN
        )

        updateNotification(
            status
        )
    }

    private fun handleAvailableImage(
        reader: ImageReader,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val image =
            reader.acquireLatestImage()
                ?: return

        if (
            !captureReady ||
            !scanRequested ||
            ocrInProgress
        ) {
            image.close()
            return
        }

        val scanMode =
            requestedScanMode

        scanRequested =
            false

        val scanId =
            beginScan(
                scanMode =
                    scanMode
            )

        try {
            val fullScreenBitmap =
                imageToBitmap(
                    image = image,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

            val cropStartY =
                (
                        fullScreenBitmap.height *
                                BOTTOM_CROP_START_FRACTION
                        )
                    .toInt()
                    .coerceIn(
                        0,
                        fullScreenBitmap.height - 1
                    )

            val croppedBitmap =
                Bitmap.createBitmap(
                    fullScreenBitmap,
                    0,
                    cropStartY,
                    fullScreenBitmap.width,
                    fullScreenBitmap.height -
                            cropStartY
                )

            runTextRecognition(
                scanId = scanId,
                fullScreenBitmap = fullScreenBitmap,
                croppedBitmap = croppedBitmap
            )
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "Could not process screen frame.",
                exception
            )

            if (
                isCurrentScan(
                    scanId
                )
            ) {
                sendScanResult(
                    restaurant =
                        getString(
                            R.string
                                .restaurant_not_found
                        ),

                    pay =
                        getString(
                            R.string.pay_not_found
                        ),

                    miles =
                        getString(
                            R.string.mileage_not_found
                        ),

                    status =
                        getString(
                            R.string.screen_reader_error,
                            exception.message
                                ?: getString(
                                    R.string.unknown_error
                                )
                        ),

                    screenState =
                        SCREEN_STATE_UNKNOWN,

                    routeSource =
                        RouteSource.NOT_AVAILABLE,

                    routeStatus =
                        RouteStatus.FAILED
                )

                finishScan(
                    scanId
                )
            }
        } finally {
            image.close()
        }
    }

    private fun beginScan(
        scanMode: String
    ): Long {
        nextScanId +=
            1

        val scanId =
            nextScanId

        activeScanId =
            scanId

        activeScanMode =
            scanMode

        ocrInProgress =
            true

        pendingParsedOffer =
            null

        pendingRouteCapturedAtWallTime =
            null

        pendingOfferMapMarkers =
            null

        pendingOfferMapCapturedAtWallTime =
            null

        scheduleScanTimeout(
            scanId
        )

        return scanId
    }

    private fun isCurrentScan(
        scanId: Long
    ): Boolean {
        return ocrInProgress &&
                activeScanId == scanId
    }

    private fun finishScan(
        scanId: Long
    ) {
        if (
            activeScanId != scanId
        ) {
            return
        }

        scanTimeoutRunnable?.let {
            backgroundHandler
                .removeCallbacks(
                    it
                )
        }

        scanTimeoutRunnable =
            null

        pendingParsedOffer =
            null

        pendingRouteCapturedAtWallTime =
            null

        pendingOfferMapMarkers =
            null

        pendingOfferMapCapturedAtWallTime =
            null

        activeScanId =
            0L

        ocrInProgress =
            false
    }

    private fun scheduleScanTimeout(
        scanId: Long
    ) {
        scanTimeoutRunnable?.let {
            backgroundHandler
                .removeCallbacks(
                    it
                )
        }

        val timeoutRunnable =
            Runnable {
                if (
                    !isCurrentScan(
                        scanId
                    )
                ) {
                    return@Runnable
                }

                Log.w(
                    LOG_TAG,
                    "Scan timed out."
                )

                /*
                 * A classification-only timeout must
                 * not modify the saved offer or trigger
                 * another Google route request.
                 */
                if (
                    activeScanMode ==
                    SCAN_MODE_CLASSIFY_ONLY
                ) {
                    sendPreviousResult(
                        status =
                            "Order-state check timed out",

                        screenState =
                            SCREEN_STATE_UNKNOWN
                    )

                    finishScan(
                        scanId
                    )

                    return@Runnable
                }

                val pendingOffer =
                    pendingParsedOffer

                if (
                    pendingOffer != null
                ) {
                    val capturedAt =
                        pendingRouteCapturedAtWallTime
                            ?: System.currentTimeMillis()

                    saveLatestScan(
                        parsedOffer =
                            pendingOffer,

                        restaurantPlaceId =
                            null,

                        timeToRestaurantMinutes =
                            null,

                        distanceToRestaurantMiles =
                            null,

                        routeCapturedAtWallTime =
                            capturedAt,

                        routeSource =
                            RouteSource.NOT_AVAILABLE,

                        routeStatus =
                            RouteStatus.TIMED_OUT
                    )

                    sendScanResult(
                        restaurant =
                            pendingOffer.restaurant,

                        restaurantPlaceId =
                            null,

                        pay =
                            pendingOffer.pay,

                        miles =
                            pendingOffer.miles,

                        status =
                            "Google route timed out; using mileage estimate",

                        screenState =
                            SCREEN_STATE_OFFER,

                        routeCapturedAtWallTime =
                            capturedAt,

                        routeSource =
                            RouteSource.NOT_AVAILABLE,

                        routeStatus =
                            RouteStatus.TIMED_OUT
                    )
                } else {
                    sendPreviousResult(
                        status =
                            "Scan timed out",

                        screenState =
                            SCREEN_STATE_UNKNOWN
                    )
                }

                finishScan(
                    scanId
                )
            }

        scanTimeoutRunnable =
            timeoutRunnable

        backgroundHandler.postDelayed(
            timeoutRunnable,
            SCAN_TIMEOUT_MS
        )
    }

    private fun imageToBitmap(
        image: Image,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap {
        val plane =
            image.planes[0]

        val buffer =
            plane.buffer

        buffer.rewind()

        val pixelStride =
            plane.pixelStride

        val rowStride =
            plane.rowStride

        val rowPadding =
            rowStride -
                    pixelStride * screenWidth

        val paddedWidth =
            screenWidth +
                    rowPadding / pixelStride

        val paddedBitmap =
            Bitmap.createBitmap(
                paddedWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

        paddedBitmap.copyPixelsFromBuffer(
            buffer
        )

        if (
            paddedWidth ==
            screenWidth
        ) {
            return paddedBitmap
        }

        val exactBitmap =
            Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                screenWidth,
                screenHeight
            )

        paddedBitmap.recycle()

        return exactBitmap
    }

    private fun runTextRecognition(
        scanId: Long,
        fullScreenBitmap: Bitmap,
        croppedBitmap: Bitmap
    ) {
        /*
         * Offer parsing, lifecycle recognition, and the
         * Google Maps navigation signature all use the
         * bottom half. The overlay is kept entirely above
         * this OCR region.
         */
        val recognitionBitmap =
            croppedBitmap

        val inputImage =
            InputImage.fromBitmap(
                recognitionBitmap,
                0
            )

        textRecognizer.process(
            inputImage
        )
            .addOnSuccessListener {
                    recognizedText ->

                if (
                    !isCurrentScan(
                        scanId
                    )
                ) {
                    return@addOnSuccessListener
                }

                Log.d(
                    LOG_TAG,
                    "Recognized text:\n" +
                            recognizedText.text
                )

                val offerSignals =
                    extractOfferSignals(
                        recognizedText.text
                    )

                val hasOfferCardMarkers =
                    hasDoorDashOfferCardMarkers(
                        recognizedText.text
                    )

                /*
                 * A classification-only scan stops here
                 * when the offer remains visible.
                 *
                 * It reuses the previously saved offer
                 * data and never performs another Places
                 * request.
                 */
                if (
                    offerSignals.isValidOffer &&
                    hasOfferCardMarkers &&
                    activeScanMode ==
                    SCAN_MODE_CLASSIFY_ONLY
                ) {
                    sendPreviousResult(
                        status =
                            "Offer still visible",

                        screenState =
                            SCREEN_STATE_OFFER
                    )

                    updateNotification(
                        "Offer still visible"
                    )

                    finishScan(
                        scanId
                    )

                    return@addOnSuccessListener
                }

                if (
                    !offerSignals.isValidOffer ||
                    !hasOfferCardMarkers
                ) {
                    handleNonOfferScreen(
                        scanId =
                            scanId,

                        recognizedText =
                            recognizedText.text
                    )

                    return@addOnSuccessListener
                }

                /*
                 * Customer-map experiment. This inspects pixels from the
                 * screenshot DashTool already captured. It does not change the
                 * score or lifecycle. When the restaurant match later returns,
                 * DashTool uses these markers for a measurement-only GPS estimate.
                 */
                if (
                    activeScanMode ==
                    SCAN_MODE_FULL_OFFER
                ) {
                    try {
                        val markerResult =
                            DoorDashOfferMapLocator.locate(
                                fullScreenBitmap
                            )

                        pendingOfferMapMarkers =
                            if (
                                markerResult.driver != null &&
                                markerResult.restaurant != null &&
                                markerResult.customer != null
                            ) {
                                markerResult
                            } else {
                                null
                            }

                        if (pendingOfferMapMarkers != null) {
                            val mapCapturedAt =
                                System.currentTimeMillis()

                            pendingOfferMapCapturedAtWallTime =
                                mapCapturedAt

                            customerMapCoordinateEstimator
                                .captureDriverAnchorNearScreenshot(
                                    screenshotCapturedAtWallTimeMs =
                                        mapCapturedAt
                                )
                        }

                        Log.d(
                            "DashToolCustomerMap",
                            markerResult.toLogString()
                        )
                    } catch (exception: Exception) {
                        Log.e(
                            "DashToolCustomerMap",
                            "Offer-map marker detection failed.",
                            exception
                        )
                    }
                }

                val parsedOffer =
                    parseOffer(
                        recognizedText =
                            recognizedText,

                        pay =
                            requireNotNull(
                                offerSignals.pay
                            ),

                        miles =
                            requireNotNull(
                                offerSignals.miles
                            )
                    )

                finishRecognizedOffer(
                    scanId =
                        scanId,

                    parsedOffer =
                        parsedOffer
                )
            }
            .addOnFailureListener {
                    exception ->

                if (
                    !isCurrentScan(
                        scanId
                    )
                ) {
                    return@addOnFailureListener
                }

                Log.e(
                    LOG_TAG,
                    "OCR failed.",
                    exception
                )

                sendScanResult(
                    restaurant =
                        getString(
                            R.string
                                .restaurant_not_found
                        ),

                    pay =
                        getString(
                            R.string.pay_not_found
                        ),

                    miles =
                        getString(
                            R.string.mileage_not_found
                        ),

                    status =
                        getString(
                            R.string.screen_reader_error,
                            exception.message
                                ?: getString(
                                    R.string.unknown_error
                                )
                        ),

                    screenState =
                        SCREEN_STATE_UNKNOWN,

                    routeSource =
                        RouteSource.NOT_AVAILABLE,

                    routeStatus =
                        RouteStatus.FAILED
                )

                finishScan(
                    scanId
                )
            }
            .addOnCompleteListener {
                if (
                    !croppedBitmap.isRecycled
                ) {
                    croppedBitmap.recycle()
                }

                if (
                    !fullScreenBitmap.isRecycled
                ) {
                    fullScreenBitmap.recycle()
                }
            }
    }

    private fun extractOfferSignals(
        completeText: String
    ): OfferSignals {
        val payPattern =
            Regex(
                """\$\s*\d+(?:\.\d{1,2})?"""
            )

        val mileagePattern =
            Regex(
                """\b\d+(?:\.\d+)?\s*(?:mi|mile|miles)\b""",
                RegexOption.IGNORE_CASE
            )

        return OfferSignals(
            pay =
                payPattern
                    .find(
                        completeText
                    )
                    ?.value,

            miles =
                mileagePattern
                    .find(
                        completeText
                    )
                    ?.value
        )
    }

    private fun hasDoorDashOfferCardMarkers(
        completeText: String
    ): Boolean {
        val normalizedText =
            completeText
                .lowercase(
                    Locale.US
                )
                .replace(
                    Regex(
                        """\s+"""
                    ),
                    " "
                )
                .trim()

        val hasCustomerDropoff =
            normalizedText.contains(
                "customer dropoff"
            ) ||
                    normalizedText.contains(
                        "customer drop off"
                    )

        val hasOfferDetails =
            normalizedText.contains(
                "deliver by"
            ) &&
                    normalizedText.contains(
                        "guaranteed"
                    )

        return normalizedText.contains(
            "accept"
        ) &&
                normalizedText.contains(
                    "pickup"
                ) &&
                (
                        hasCustomerDropoff ||
                                hasOfferDetails
                        )
    }

    private fun handleNonOfferScreen(
        scanId: Long,
        recognizedText: String
    ) {
        if (
            !isCurrentScan(
                scanId
            )
        ) {
            return
        }

        val classification =
            classifyNonOfferScreen(
                recognizedText
            )

        val status =
            when (
                classification.screenState
            ) {
                SCREEN_STATE_WAITING ->
                    "Waiting for offers"

                SCREEN_STATE_ACTIVE_DELIVERY ->
                    when (
                        classification.lifecycleStage
                    ) {
                        LIFECYCLE_STAGE_AT_RESTAURANT ->
                            "At restaurant"

                        LIFECYCLE_STAGE_TO_CUSTOMER ->
                            "Heading to customer"

                        LIFECYCLE_STAGE_AT_CUSTOMER ->
                            "At customer"

                        else ->
                            "Active delivery detected"
                    }

                SCREEN_STATE_NAVIGATION ->
                    "Active navigation detected"

                else ->
                    "No DoorDash offer detected"
            }

        Log.d(
            LOG_TAG,
            "No valid offer detected. State: " +
                    classification.screenState +
                    ", lifecycle: " +
                    classification.lifecycleStage
        )

        sendPreviousResult(
            status =
                status,

            screenState =
                classification.screenState,

            lifecycleStage =
                classification.lifecycleStage
        )

        updateNotification(
            status
        )

        finishScan(
            scanId
        )
    }

    private fun classifyNonOfferScreen(
        recognizedText: String
    ): ScreenClassification {
        val normalizedText =
            recognizedText
                .lowercase(
                    Locale.US
                )
                .replace(
                    Regex(
                        """\s+"""
                    ),
                    " "
                )
                .trim()

        fun containsAll(
            vararg markers: String
        ): Boolean {
            return markers.all { marker ->
                normalizedText.contains(
                    marker
                )
            }
        }

        /*
         * The completed-order summary is treated as the
         * waiting state. OverlayService can then close the
         * active lifecycle before the next offer appears.
         */
        if (
            containsAll(
                "this dash so far",
                "continue dashing"
            )
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_WAITING
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
            waitingMarkers.any { marker ->
                normalizedText.contains(
                    marker
                )
            }
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_WAITING
            )
        }

        /*
         * Bottom-only customer-arrival signals. These are
         * intentionally stage-gated by OverlayService, so
         * they can advance only from TO_CUSTOMER.
         *
         * DoorDash can show either:
         *   - Continue + Directions
         *   - Handed order to customer
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
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    LIFECYCLE_STAGE_AT_CUSTOMER
            )
        }

        /*
         * Start pickup is the fixed restaurant-arrival
         * action in the lower DoorDash panel.
         */
        if (
            normalizedText.contains(
                "start pickup"
            )
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    LIFECYCLE_STAGE_AT_RESTAURANT
            )
        }

        /*
         * Pickup from is visible in the bottom panel just
         * after acceptance. It replaces the old dependency
         * on the top-header phrase Pick up by.
         */
        if (
            normalizedText.contains(
                "pickup from"
            )
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    LIFECYCLE_STAGE_TO_RESTAURANT
            )
        }

        /*
         * Navigation may open immediately after an offer is
         * accepted, before DashTool captures the DoorDash
         * accepted-order screen. Route mileage by itself is
         * not sufficient because offer cards also show miles.
         *
         * The speed display is no longer visible inside the
         * bottom-half OCR crop, so navigation is identified by:
         *   1. route mileage,
         *   2. the Exit control, and
         *   3. either remaining travel time or arrival time.
         */
        val hasNavigationMileage =
            Regex(
                """\b\d+(?:\.\d+)?\s*(?:mi|mile|miles)\b""",
                RegexOption.IGNORE_CASE
            )
                .containsMatchIn(
                    normalizedText
                )

        val hasExitControl =
            normalizedText.contains(
                "exit"
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

        if (
            hasNavigationMileage &&
            hasExitControl &&
            (
                    hasRemainingTravelTime ||
                            hasArrivalTime
                    )
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_NAVIGATION
            )
        }


        /*
         * Once the restaurant-arrival stage has already
         * been recorded, a lower-panel Directions action
         * means pickup was confirmed and the customer leg
         * is available. OverlayService rejects this signal
         * if it would skip the restaurant-arrival stage.
         */
        if (
            normalizedText.contains(
                "directions"
            )
        ) {
            return ScreenClassification(
                screenState =
                    SCREEN_STATE_ACTIVE_DELIVERY,

                lifecycleStage =
                    LIFECYCLE_STAGE_TO_CUSTOMER
            )
        }

        return ScreenClassification(
            screenState =
                SCREEN_STATE_UNKNOWN
        )
    }

    private fun finishRecognizedOffer(
        scanId: Long,
        parsedOffer: ParsedOffer
    ) {
        if (
            !isCurrentScan(
                scanId
            )
        ) {
            return
        }

        pendingParsedOffer =
            parsedOffer

        val restaurantWasFound =
            parsedOffer.restaurant !=
                    getString(
                        R.string.restaurant_not_found
                    )

        if (
            !restaurantWasFound
        ) {
            val capturedAt =
                System.currentTimeMillis()

            saveLatestScan(
                parsedOffer =
                    parsedOffer,

                restaurantPlaceId =
                    null,

                timeToRestaurantMinutes =
                    null,

                distanceToRestaurantMiles =
                    null,

                routeCapturedAtWallTime =
                    capturedAt,

                routeSource =
                    RouteSource.NOT_AVAILABLE,

                routeStatus =
                    RouteStatus.NOT_AVAILABLE
            )

            sendScanResult(
                restaurant =
                    parsedOffer.restaurant,

                restaurantPlaceId =
                    null,

                pay =
                    parsedOffer.pay,

                miles =
                    parsedOffer.miles,

                status =
                    getString(
                        R.string.scan_status_ready
                    ),

                screenState =
                    SCREEN_STATE_OFFER,

                routeCapturedAtWallTime =
                    capturedAt,

                routeSource =
                    RouteSource.NOT_AVAILABLE,

                routeStatus =
                    RouteStatus.NOT_AVAILABLE
            )

            updateNotification(
                getString(
                    R.string.screen_reader_result,
                    parsedOffer.restaurant,
                    parsedOffer.pay,
                    parsedOffer.miles
                )
            )

            finishScan(
                scanId
            )

            return
        }

        updateNotification(
            getString(
                R.string
                    .scan_status_google_searching
            )
        )

        val routeCapturedAt =
            System.currentTimeMillis()

        pendingRouteCapturedAtWallTime =
            routeCapturedAt

        travelEstimator
            .estimateToNearestRestaurant(
                restaurantName =
                    parsedOffer.restaurant
            ) {
                    travelEstimate ->

                if (
                    !isCurrentScan(
                        scanId
                    )
                ) {
                    return@estimateToNearestRestaurant
                }

                val finalOffer =
                    if (
                        travelEstimate == null
                    ) {
                        parsedOffer
                    } else {
                        parsedOffer.copy(
                            restaurant =
                                travelEstimate
                                    .matchedRestaurantName
                        )
                    }

                val mapMarkers =
                    pendingOfferMapMarkers

                val matchedRestaurantPlaceId =
                    travelEstimate
                        ?.matchedRestaurantPlaceId

                if (
                    mapMarkers != null &&
                    !matchedRestaurantPlaceId.isNullOrBlank()
                ) {
                    customerMapCoordinateEstimator
                        .estimate(
                            markers =
                                mapMarkers,
                            restaurantPlaceId =
                                matchedRestaurantPlaceId,
                            screenshotCapturedAtWallTimeMs =
                                pendingOfferMapCapturedAtWallTime
                                    ?: routeCapturedAt
                        )
                }

                val routeSource =
                    if (
                        travelEstimate != null
                    ) {
                        RouteSource
                            .GOOGLE_TRAFFIC_AWARE
                    } else {
                        RouteSource
                            .NOT_AVAILABLE
                    }

                val routeStatus =
                    if (
                        travelEstimate != null
                    ) {
                        RouteStatus.SUCCESS
                    } else {
                        RouteStatus.FAILED
                    }

                saveLatestScan(
                    parsedOffer =
                        finalOffer,

                    restaurantPlaceId =
                        travelEstimate
                            ?.matchedRestaurantPlaceId,

                    timeToRestaurantMinutes =
                        travelEstimate
                            ?.minutesToRestaurant,

                    distanceToRestaurantMiles =
                        travelEstimate
                            ?.milesToRestaurant,

                    routeCapturedAtWallTime =
                        routeCapturedAt,

                    routeSource =
                        routeSource,

                    routeStatus =
                        routeStatus
                )

                val status =
                    if (
                        travelEstimate == null
                    ) {
                        getString(
                            R.string
                                .scan_status_google_unavailable
                        )
                    } else {
                        getString(
                            R.string.scan_status_google_route,
                            travelEstimate
                                .minutesToRestaurant,
                            travelEstimate
                                .milesToRestaurant
                        )
                    }

                sendScanResult(
                    restaurant =
                        finalOffer.restaurant,

                    restaurantPlaceId =
                        travelEstimate
                            ?.matchedRestaurantPlaceId,

                    pay =
                        finalOffer.pay,

                    miles =
                        finalOffer.miles,

                    status =
                        status,

                    timeToRestaurantMinutes =
                        travelEstimate
                            ?.minutesToRestaurant,

                    distanceToRestaurantMiles =
                        travelEstimate
                            ?.milesToRestaurant,

                    screenState =
                        SCREEN_STATE_OFFER,

                    routeCapturedAtWallTime =
                        routeCapturedAt,

                    routeSource =
                        routeSource,

                    routeStatus =
                        routeStatus
                )

                updateNotification(
                    getString(
                        R.string.screen_reader_result,
                        finalOffer.restaurant,
                        finalOffer.pay,
                        finalOffer.miles
                    ) +
                            "\n" +
                            status
                )

                finishScan(
                    scanId
                )
            }
    }

    private fun parseOffer(
        recognizedText: Text,
        pay: String,
        miles: String
    ): ParsedOffer {
        val recognizedLines =
            recognizedText
                .textBlocks
                .flatMap { block ->
                    block.lines.map { line ->
                        line.text.trim()
                    }
                }
                .filter { line ->
                    line.isNotBlank()
                }

        val restaurant =
            findRestaurantAfterPickup(
                recognizedLines
            )
                ?: findRestaurantFallback(
                    recognizedLines
                )
                ?: getString(
                    R.string.restaurant_not_found
                )

        return ParsedOffer(
            restaurant =
                restaurant,

            pay =
                pay,

            miles =
                miles
        )
    }

    private fun findRestaurantAfterPickup(
        recognizedLines: List<String>
    ): String? {
        val pickupIndex =
            recognizedLines
                .indexOfFirst { line ->
                    line.contains(
                        "pickup",
                        ignoreCase = true
                    )
                }

        if (
            pickupIndex < 0
        ) {
            return null
        }

        val pickupLine =
            recognizedLines[
                pickupIndex
            ]

        val inlineRestaurant =
            pickupLine
                .replaceFirst(
                    Regex(
                        """(?i)^.*?\bpickup\b\s*[:\-]?\s*"""
                    ),
                    ""
                )
                .trim()

        if (
            inlineRestaurant.isNotBlank() &&
            isRestaurantCandidate(
                inlineRestaurant
            )
        ) {
            return inlineRestaurant
        }

        val searchEnd =
            (
                    pickupIndex + 6
                    )
                .coerceAtMost(
                    recognizedLines.size
                )

        for (
        index in
        pickupIndex + 1 until searchEnd
        ) {
            val line =
                recognizedLines[
                    index
                ]

            if (
                line.contains(
                    "dropoff",
                    ignoreCase = true
                ) ||
                line.contains(
                    "customer",
                    ignoreCase = true
                )
            ) {
                break
            }

            if (
                isRestaurantCandidate(
                    line
                )
            ) {
                return line
            }
        }

        return null
    }

    private fun findRestaurantFallback(
        recognizedLines: List<String>
    ): String? {
        return recognizedLines
            .firstOrNull { line ->
                isRestaurantCandidate(
                    line
                )
            }
    }

    private fun isRestaurantCandidate(
        line: String
    ): Boolean {
        val payPattern =
            Regex(
                """\$\s*\d+(?:\.\d{1,2})?"""
            )

        val mileagePattern =
            Regex(
                """\b\d+(?:\.\d+)?\s*(?:mi|mile|miles)\b""",
                RegexOption.IGNORE_CASE
            )

        val ignoredPhrases =
            listOf(
                "mapbox",
                "openstreetmap",
                "google maps",
                "accept",
                "decline",
                "deliver",
                "delivery",
                "pickup",
                "dropoff",
                "drop off",
                "customer",
                "total",
                "guaranteed",
                "including",
                "tips",
                "items",
                "minutes",
                "miles",
                "doordash",
                "finding offers",
                "looking for orders",
                "searching for offers",
                "waiting for offers",
                "directions",
                "navigate",
                "route"
            )

        val containsIgnoredPhrase =
            ignoredPhrases.any {
                    ignored ->

                line.contains(
                    ignored,
                    ignoreCase = true
                )
            }

        return line.length in 2..60 &&
                line.any { character ->
                    character.isLetter()
                } &&
                !containsIgnoredPhrase &&
                !payPattern.containsMatchIn(
                    line
                ) &&
                !mileagePattern.containsMatchIn(
                    line
                )
    }

    private fun saveLatestScan(
        parsedOffer: ParsedOffer,
        restaurantPlaceId: String?,
        timeToRestaurantMinutes:
        Double?,
        distanceToRestaurantMiles:
        Double?,
        routeCapturedAtWallTime:
        Long?,
        routeSource: String,
        routeStatus: String
    ) {
        val editor =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )
                .edit()
                .putString(
                    KEY_RESTAURANT,
                    parsedOffer.restaurant
                )
                .putString(
                    KEY_PAY,
                    parsedOffer.pay
                )
                .putString(
                    KEY_MILES,
                    parsedOffer.miles
                )
                .putString(
                    KEY_ROUTE_SOURCE,
                    routeSource
                )
                .putString(
                    KEY_ROUTE_STATUS,
                    routeStatus
                )

        if (
            restaurantPlaceId != null
        ) {
            editor.putString(
                KEY_RESTAURANT_PLACE_ID,
                restaurantPlaceId
            )
        } else {
            editor.remove(
                KEY_RESTAURANT_PLACE_ID
            )
        }

        if (
            routeCapturedAtWallTime != null
        ) {
            editor.putLong(
                KEY_ROUTE_CAPTURED_AT_WALL_TIME,
                routeCapturedAtWallTime
            )
        } else {
            editor.remove(
                KEY_ROUTE_CAPTURED_AT_WALL_TIME
            )
        }

        if (
            timeToRestaurantMinutes != null &&
            distanceToRestaurantMiles != null
        ) {
            editor
                .putBoolean(
                    KEY_HAS_GOOGLE_ROUTE,
                    true
                )
                .putFloat(
                    KEY_TIME_TO_RESTAURANT,
                    timeToRestaurantMinutes
                        .toFloat()
                )
                .putFloat(
                    KEY_DISTANCE_TO_RESTAURANT,
                    distanceToRestaurantMiles
                        .toFloat()
                )
        } else {
            editor
                .putBoolean(
                    KEY_HAS_GOOGLE_ROUTE,
                    false
                )
                .remove(
                    KEY_TIME_TO_RESTAURANT
                )
                .remove(
                    KEY_DISTANCE_TO_RESTAURANT
                )
        }

        editor.apply()
    }

    private fun sendPreviousResult(
        status: String,
        screenState: String,
        lifecycleStage: String =
            LIFECYCLE_STAGE_NONE
    ) {
        val preferences =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

        val previousRestaurant =
            preferences.getString(
                KEY_RESTAURANT,
                null
            )

        val previousRestaurantPlaceId =
            preferences.getString(
                KEY_RESTAURANT_PLACE_ID,
                null
            )

        val previousPay =
            preferences.getString(
                KEY_PAY,
                null
            )

        val previousMiles =
            preferences.getString(
                KEY_MILES,
                null
            )

        val hasGoogleRoute =
            preferences.getBoolean(
                KEY_HAS_GOOGLE_ROUTE,
                false
            )

        val previousTime =
            if (
                hasGoogleRoute
            ) {
                preferences.getFloat(
                    KEY_TIME_TO_RESTAURANT,
                    0.0f
                ).toDouble()
            } else {
                null
            }

        val previousDistance =
            if (
                hasGoogleRoute
            ) {
                preferences.getFloat(
                    KEY_DISTANCE_TO_RESTAURANT,
                    0.0f
                ).toDouble()
            } else {
                null
            }

        val routeCapturedAt =
            if (
                preferences.contains(
                    KEY_ROUTE_CAPTURED_AT_WALL_TIME
                )
            ) {
                preferences.getLong(
                    KEY_ROUTE_CAPTURED_AT_WALL_TIME,
                    0L
                )
            } else {
                null
            }

        val routeSource =
            preferences.getString(
                KEY_ROUTE_SOURCE,
                RouteSource.NOT_AVAILABLE
            )
                ?: RouteSource.NOT_AVAILABLE

        val routeStatus =
            preferences.getString(
                KEY_ROUTE_STATUS,
                RouteStatus.NOT_AVAILABLE
            )
                ?: RouteStatus.NOT_AVAILABLE

        sendScanResult(
            restaurant =
                previousRestaurant
                    ?: getString(
                        R.string.restaurant_not_found
                    ),

            restaurantPlaceId =
                previousRestaurantPlaceId,

            pay =
                previousPay
                    ?: getString(
                        R.string.pay_not_found
                    ),

            miles =
                previousMiles
                    ?: getString(
                        R.string.mileage_not_found
                    ),

            status =
                status,

            timeToRestaurantMinutes =
                previousTime,

            distanceToRestaurantMiles =
                previousDistance,

            screenState =
                screenState,

            lifecycleStage =
                lifecycleStage,

            routeCapturedAtWallTime =
                routeCapturedAt,

            routeSource =
                routeSource,

            routeStatus =
                routeStatus
        )
    }

    private fun sendScanResult(
        restaurant: String,
        pay: String,
        miles: String,
        status: String,
        screenState: String,
        lifecycleStage: String =
            LIFECYCLE_STAGE_NONE,
        restaurantPlaceId:
        String? = null,
        timeToRestaurantMinutes:
        Double? = null,
        distanceToRestaurantMiles:
        Double? = null,
        routeCapturedAtWallTime:
        Long? = null,
        routeSource: String,
        routeStatus: String
    ) {
        val resultIntent =
            Intent(
                ACTION_SCAN_RESULT
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    EXTRA_RESTAURANT,
                    restaurant
                )

                putExtra(
                    EXTRA_PAY,
                    pay
                )

                putExtra(
                    EXTRA_MILES,
                    miles
                )

                putExtra(
                    EXTRA_SCAN_STATUS,
                    status
                )

                putExtra(
                    EXTRA_SCREEN_STATE,
                    screenState
                )

                putExtra(
                    EXTRA_LIFECYCLE_STAGE,
                    lifecycleStage
                )

                putExtra(
                    EXTRA_SCAN_MODE,
                    activeScanMode
                )

                putExtra(
                    EXTRA_ROUTE_SOURCE,
                    routeSource
                )

                putExtra(
                    EXTRA_ROUTE_STATUS,
                    routeStatus
                )

                if (
                    restaurantPlaceId != null
                ) {
                    putExtra(
                        EXTRA_RESTAURANT_PLACE_ID,
                        restaurantPlaceId
                    )
                }

                if (
                    timeToRestaurantMinutes != null
                ) {
                    putExtra(
                        EXTRA_TIME_TO_RESTAURANT,
                        timeToRestaurantMinutes
                    )
                }

                if (
                    distanceToRestaurantMiles != null
                ) {
                    putExtra(
                        EXTRA_DISTANCE_TO_RESTAURANT,
                        distanceToRestaurantMiles
                    )
                }

                if (
                    routeCapturedAtWallTime != null
                ) {
                    putExtra(
                        EXTRA_ROUTE_CAPTURED_AT_WALL_TIME,
                        routeCapturedAtWallTime
                    )
                }
            }

        sendBroadcast(
            resultIntent
        )
    }

    private fun setReaderActive(
        active: Boolean
    ) {
        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_READER_ACTIVE,
                active
            )
            .apply()
    }

    private fun updateNotification(
        message: String
    ) {
        val notificationPermissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission
                    .POST_NOTIFICATIONS
            ) ==
                    PackageManager
                        .PERMISSION_GRANTED

        if (
            !notificationPermissionGranted
        ) {
            return
        }

        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                message
            )
        )
    }

    private fun buildNotification(
        message: String
    ): Notification {
        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable
                    .ic_menu_camera
            )
            .setContentTitle(
                getString(
                    R.string
                        .screen_reader_notification_title
                )
            )
            .setContentText(
                message
            )
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(
                        message
                    )
            )
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .setOngoing(
                true
            )
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(
                    R.string
                        .screen_reader_channel_name
                ),
                NotificationManager
                    .IMPORTANCE_LOW
            )

        notificationManager
            .createNotificationChannel(
                channel
            )
    }

    private fun clearCaptureResources(
        stopProjection: Boolean
    ) {
        captureReady =
            false

        scanRequested =
            false

        ocrInProgress =
            false

        activeScanId =
            0L

        requestedScanMode =
            SCAN_MODE_FULL_OFFER

        activeScanMode =
            SCAN_MODE_FULL_OFFER

        if (backgroundInfrastructureReady) {
            scanTimeoutRunnable?.let { runnable ->
                backgroundHandler
                    .removeCallbacks(
                        runnable
                    )
            }
        }

        scanTimeoutRunnable =
            null

        pendingParsedOffer =
            null

        pendingRouteCapturedAtWallTime =
            null

        setReaderActive(
            false
        )

        virtualDisplay?.release()

        virtualDisplay =
            null

        imageReader
            ?.setOnImageAvailableListener(
                null,
                null
            )

        imageReader?.close()

        imageReader =
            null

        val projection =
            mediaProjection

        mediaProjection =
            null

        if (
            stopProjection &&
            projection != null
        ) {
            runCatching {
                projection.unregisterCallback(
                    projectionCallback
                )
            }

            runCatching {
                projection.stop()
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(
                scanRequestReceiver
            )
        }

        clearCaptureResources(
            stopProjection = true
        )

        textRecognizer.close()

        if (backgroundInfrastructureReady) {
            backgroundThread.quitSafely()
            backgroundInfrastructureReady =
                false
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}