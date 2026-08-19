package com.example.dashtool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.example.dashtool.data.DashToolRepository
import com.example.dashtool.data.DashToolVersions
import com.example.dashtool.data.DataConfidence
import com.example.dashtool.data.OfferDetectionSource
import com.example.dashtool.data.OfferEntity
import com.example.dashtool.data.OrderEventEntity
import com.example.dashtool.data.OrderEventSource
import com.example.dashtool.data.OrderEventType
import com.example.dashtool.data.RestaurantMatchConfidence
import com.example.dashtool.data.RouteLeg
import com.example.dashtool.data.RouteSnapshotEntity
import com.example.dashtool.data.RouteSource
import com.example.dashtool.data.RouteStatus
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class OverlayTextView(
    context: Context
) : AppCompatTextView(
    context
) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}


/*
 * DashTool-specific overlay presentation.
 *
 * This intentionally avoids a stock Material-card look. The expanded state
 * reads like a compact driver instrument panel: a branded mode line, a
 * score rail / segmented order index, pace + clear-time metrics, and a
 * market-pulse section. The collapsed state remains glanceable while driving.
 */
private class DashToolOverlayView(
    context: Context
) : FrameLayout(
    context
) {

    companion object {
        private const val CARD_WIDTH_DP =
            312
    }

    private fun dp(
        value: Int
    ): Int {
        return (
                value *
                        resources
                            .displayMetrics
                            .density
                )
            .toInt()
    }

    private fun roundedBackground(
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(
                fillColor
            )

            cornerRadius =
                dp(
                    radiusDp
                ).toFloat()

            if (
                strokeColor != null
            ) {
                setStroke(
                    dp(
                        strokeWidthDp
                    ),
                    strokeColor
                )
            }
        }
    }

    private fun makeLabel(
        sizeSp: Float = 10f
    ): TextView {
        return TextView(
            context
        ).apply {
            setTextColor(
                Color.rgb(
                    142,
                    154,
                    169
                )
            )

            textSize =
                sizeSp

            typeface =
                Typeface.create(
                    Typeface.MONOSPACE,
                    Typeface.BOLD
                )

            letterSpacing =
                0.10f

            includeFontPadding =
                false
        }
    }

    private fun makeValue(
        sizeSp: Float
    ): TextView {
        return TextView(
            context
        ).apply {
            setTextColor(
                Color.rgb(
                    245,
                    248,
                    251
                )
            )

            textSize =
                sizeSp

            typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

            includeFontPadding =
                false
        }
    }

    private val expandedCard =
        FrameLayout(
            context
        ).apply {
            visibility =
                View.GONE

            /*
             * One solid panel. No elevation/shadow and no translucent-looking
             * outline around the rounded corners.
             */
            elevation =
                0f

            background =
                roundedBackground(
                    fillColor =
                        Color.rgb(
                            17,
                            22,
                            29
                        ),
                    radiusDp =
                        14
                )

            layoutParams =
                LayoutParams(
                    dp(
                        CARD_WIDTH_DP
                    ),
                    LayoutParams.WRAP_CONTENT
                )
        }

    private val content =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(
                    18
                ),
                dp(
                    16
                ),
                dp(
                    18
                ),
                dp(
                    13
                )
            )

            layoutParams =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
        }

    private val modeLabel =
        makeLabel(
            9.5f
        )

    private val restaurantText =
        makeValue(
            20f
        ).apply {
            maxLines =
                2
        }

    private val headerLeft =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            addView(
                modeLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                restaurantText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(
                            4
                        )
                }
            )
        }

    private val scoreValue =
        makeValue(
            25f
        ).apply {
            gravity =
                Gravity.CENTER
        }

    private val scoreCaption =
        makeLabel(
            8f
        ).apply {
            text =
                "ORDER INDEX"

            gravity =
                Gravity.CENTER
        }

    private val scoreBox =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            gravity =
                Gravity.CENTER

            setPadding(
                dp(
                    10
                ),
                dp(
                    7
                ),
                dp(
                    10
                ),
                dp(
                    7
                )
            )

            addView(
                scoreValue,
                LinearLayout.LayoutParams(
                    dp(
                        58
                    ),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                scoreCaption,
                LinearLayout.LayoutParams(
                    dp(
                        66
                    ),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(
                            1
                        )
                }
            )
        }

    private val headerRow =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            addView(
                headerLeft,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(
                scoreBox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin =
                        dp(
                            10
                        )
                }
            )
        }

    private val scoreSegments =
        mutableListOf<View>()

    private val segmentRow =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.HORIZONTAL

            repeat(
                8
            ) {
                val segment =
                    View(
                        context
                    )

                scoreSegments.add(
                    segment
                )

                addView(
                    segment,
                    LinearLayout.LayoutParams(
                        0,
                        dp(
                            4
                        ),
                        1f
                    ).apply {
                        marginEnd =
                            if (
                                it < 7
                            ) {
                                dp(
                                    3
                                )
                            } else {
                                0
                            }
                    }
                )
            }
        }

    private fun createMetricColumn(
        valueView: TextView,
        labelText: String
    ): LinearLayout {
        return LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            addView(
                valueView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                makeLabel(
                    8.5f
                ).apply {
                    text =
                        labelText
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(
                            3
                        )
                }
            )
        }
    }

    private val hourlyValue =
        makeValue(
            19f
        )

    private val completionValue =
        makeValue(
            19f
        )

    private val metricDivider =
        View(
            context
        ).apply {
            setBackgroundColor(
                Color.rgb(
                    48,
                    58,
                    70
                )
            )
        }

    private val metricsRow =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            addView(
                createMetricColumn(
                    valueView =
                        hourlyValue,
                    labelText =
                        "ORDER $/HR"
                ),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(
                metricDivider,
                LinearLayout.LayoutParams(
                    dp(
                        1
                    ),
                    dp(
                        38
                    )
                ).apply {
                    marginStart =
                        dp(
                            12
                        )

                    marginEnd =
                        dp(
                            12
                        )
                }
            )

            addView(
                createMetricColumn(
                    valueView =
                        completionValue,
                    labelText =
                        "EST. TIME"
                ),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

    private val marketLabel =
        makeLabel(
            8.5f
        ).apply {
            text =
                "WAITING AREA"
        }

    private val marketText =
        TextView(
            context
        ).apply {
            setTextColor(
                Color.rgb(
                    202,
                    211,
                    221
                )
            )

            textSize =
                12.5f

            includeFontPadding =
                false

            maxLines =
                4
        }

    private val marketBlock =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            background =
                roundedBackground(
                    fillColor =
                        Color.rgb(
                            23,
                            30,
                            39
                        ),
                    radiusDp =
                        11
                )

            setPadding(
                dp(
                    12
                ),
                dp(
                    9
                ),
                dp(
                    12
                ),
                dp(
                    10
                )
            )

            addView(
                marketLabel
            )

            addView(
                marketText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(
                            5
                        )
                }
            )

        }

    private val destinationLabel =
        makeLabel(
            8.5f
        ).apply {
            text =
                "DESTINATION LOCK"
        }

    private val destinationText =
        TextView(
            context
        ).apply {
            setTextColor(
                Color.rgb(
                    220,
                    226,
                    234
                )
            )

            textSize =
                12.2f

            includeFontPadding =
                false

            maxLines =
                3
        }

    private val destinationBlock =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            visibility =
                View.GONE

            background =
                roundedBackground(
                    fillColor =
                        Color.rgb(
                            26,
                            23,
                            28
                        ),
                    radiusDp =
                        13,
                    strokeColor =
                        Color.rgb(
                            92,
                            43,
                            51
                        )
                )

            setPadding(
                dp(
                    12
                ),
                dp(
                    9
                ),
                dp(
                    12
                ),
                dp(
                    10
                )
            )

            addView(
                destinationLabel
            )

            addView(
                destinationText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(
                            5
                        )
                }
            )
        }

    private val footer =
        makeLabel(
            8.3f
        ).apply {
            gravity =
                Gravity.CENTER

            text =
                "TAP  COLLAPSE      HOLD  MAP"
        }

    private val collapsedDot =
        View(
            context
        ).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    dp(
                        6
                    ),
                    dp(
                        6
                    )
                )
        }

    private val collapsedBrand =
        makeLabel(
            9f
        ).apply {
            text =
                "DT"

            setTextColor(
                Color.rgb(
                    221,
                    228,
                    236
                )
            )
        }

    private val collapsedValue =
        makeValue(
            19f
        )

    private val collapsedPill =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            elevation =
                dp(
                    9
                ).toFloat()

            setPadding(
                dp(
                    12
                ),
                dp(
                    9
                ),
                dp(
                    13
                ),
                dp(
                    9
                )
            )

            addView(
                collapsedDot
            )

            addView(
                collapsedBrand,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin =
                        dp(
                            7
                        )
                }
            )

            addView(
                collapsedValue,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin =
                        dp(
                            9
                        )
                }
            )
        }

    init {
        clipChildren =
            false

        clipToPadding =
            false

        content.addView(
            headerRow
        )

        content.addView(
            segmentRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(
                    4
                )
            ).apply {
                topMargin =
                    dp(
                        12
                    )
            }
        )

        content.addView(
            metricsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(
                        14
                    )
            }
        )

        content.addView(
            marketBlock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(
                        13
                    )
            }
        )

        content.addView(
            destinationBlock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(
                        9
                    )
            }
        )

        content.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    dp(
                        11
                    )
            }
        )

        expandedCard.addView(
            content
        )

        addView(
            expandedCard
        )

        addView(
            collapsedPill
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyAccent(
        accentColor: Int
    ) {
        scoreValue.setTextColor(
            accentColor
        )

        scoreBox.background =
            roundedBackground(
                fillColor =
                    Color.rgb(
                        21,
                        27,
                        35
                    ),
                radiusDp =
                    14,
                strokeColor =
                    accentColor
            )

        collapsedDot.background =
            roundedBackground(
                fillColor =
                    accentColor,
                radiusDp =
                    6
            )

        collapsedValue.setTextColor(
            accentColor
        )

        collapsedPill.background =
            roundedBackground(
                fillColor =
                    Color.rgb(
                        17,
                        22,
                        29
                    ),
                radiusDp =
                    28,
                strokeColor =
                    accentColor
            )
    }

    private fun updateSegments(
        score: Double?,
        accentColor: Int
    ) {
        val filled =
            if (
                score == null
            ) {
                0
            } else {
                (
                        score
                            .coerceIn(
                                0.0,
                                10.0
                            ) /
                                10.0 *
                                scoreSegments.size
                        )
                    .roundToInt()
            }

        scoreSegments.forEachIndexed {
                index,
                segment ->

            val color =
                if (
                    index < filled
                ) {
                    accentColor
                } else {
                    Color.rgb(
                        48,
                        58,
                        70
                    )
                }

            segment.background =
                roundedBackground(
                    fillColor =
                        color,
                    radiusDp =
                        2
                )
        }
    }

    fun showOrder(
        restaurantName: String,
        score: Double?,
        hourlyRate: String,
        completionTime: String,
        demandText: String?,
        destinationSummary: String?,
        accentColor: Int
    ) {
        applyAccent(
            accentColor
        )

        collapsedPill.visibility =
            View.GONE

        expandedCard.visibility =
            View.VISIBLE

        modeLabel.text =
            "DASHTOOL // OFFER"

        restaurantText.text =
            restaurantName

        scoreBox.visibility =
            View.VISIBLE

        segmentRow.visibility =
            View.VISIBLE

        metricsRow.visibility =
            View.VISIBLE

        scoreValue.text =
            score?.let {
                    value ->

                String.format(
                    Locale.US,
                    "%.1f",
                    value
                )
            } ?: "•••"

        hourlyValue.text =
            hourlyRate

        completionValue.text =
            completionTime

        updateSegments(
            score =
                score,
            accentColor =
                accentColor
        )

        /*
         * Demand telemetry is intentionally omitted from the popup and no
         * longer changes the grade. This shared block is reserved for
         * waiting-area information while DashTool is idle.
         */
        marketBlock.visibility =
            View.GONE

        val destination =
            destinationSummary
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            destination != null
        ) {
            destinationText.text =
                destination

            destinationBlock.visibility =
                View.VISIBLE

            footer.text =
                "TAP  COLLAPSE      HOLD  CUSTOMER"
        } else {
            destinationBlock.visibility =
                View.GONE

            footer.text =
                "TAP  COLLAPSE      HOLD  MAP"
        }
    }

    fun showWaiting(
        recommendationText: String?,
        centerName: String?,
        accentColor: Int
    ) {
        applyAccent(
            accentColor
        )

        collapsedPill.visibility =
            View.GONE

        expandedCard.visibility =
            View.VISIBLE

        modeLabel.text =
            "DASHTOOL // POSITION"

        restaurantText.text =
            centerName
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Next move"

        scoreBox.visibility =
            View.GONE

        segmentRow.visibility =
            View.GONE

        metricsRow.visibility =
            View.GONE

        marketLabel.text =
            "WAITING AREA"

        marketText.text =
            recommendationText
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Scanning nearby centers, restaurant density, and historical wait."

        marketBlock.visibility =
            View.VISIBLE

        destinationBlock.visibility =
            View.GONE

        footer.text =
            "TAP  COLLAPSE      HOLD  MAP"
    }

    fun showCollapsed(
        valueText: String,
        accentColor: Int,
        compact: Boolean
    ) {
        applyAccent(
            accentColor
        )

        expandedCard.visibility =
            View.GONE

        collapsedPill.visibility =
            View.VISIBLE

        collapsedValue.text =
            valueText

        collapsedBrand.visibility =
            if (
                compact
            ) {
                View.VISIBLE
            } else {
                View.VISIBLE
            }
    }
}

class OverlayService : Service() {

    companion object {

        private const val NOTIFICATION_CHANNEL_ID =
            "dashtool_overlay_channel"

        private const val NOTIFICATION_ID =
            1

        /*
         * ScreenCaptureService waits up to 12 seconds.
         * The overlay waits slightly longer.
         */
        private const val OVERLAY_SCAN_TIMEOUT_MS =
            13_000L

        /*
         * The decision period is measured from the
         * original offer detection time.
         *
         * Duplicate scans do not reset this deadline.
         */
        private const val OFFER_DECISION_WINDOW_MS =
            45_000L

        private const val FIRST_DECISION_SCAN_DELAY_MS =
            250L

        private const val DECISION_SCAN_INTERVAL_MS =
            1_000L

        /*
         * DoorDash can briefly expose its normal waiting UI while an
         * accepted offer is handing off to Google Maps. A single WAITING
         * classification during the decision window must therefore never
         * reject the offer immediately. Require WAITING to persist before
         * resolving the offer as not accepted.
         */
        private const val WAITING_DECISION_CONFIRM_MS =
            5_000L

        private const val WAITING_DECISION_RECHECK_MS =
            500L

        /*
         * The same DoorDash handoff can briefly look like WAITING just after
         * acceptance. Do not let that transient state terminate a lifecycle
         * that has only just started. Explicit "Continue dashing" text still
         * ends the order immediately.
         */
        private const val WAITING_LIFECYCLE_CONFIRM_MS =
            5_000L

        private const val WAITING_LIFECYCLE_RECHECK_MS =
            500L

        private const val LIFECYCLE_FIRST_SCAN_DELAY_MS =
            750L

        private const val LIFECYCLE_SCAN_INTERVAL_MS =
            3_000L

        private const val NAVIGATION_SCAN_INTERVAL_MS =
            3_000L

        /*
         * Protect a real customer-arrival screen from one-frame navigation
         * flicker. Two seconds is long enough to reject transition screens but
         * short enough to recognize genuine navigation to another customer on
         * a stacked delivery.
         */
        private const val CUSTOMER_NAVIGATION_CONFIRM_MS =
            2_000L

        private const val CUSTOMER_NAVIGATION_RECHECK_MS =
            500L

        private const val BUSY_RETRY_DELAY_MS =
            500L

        /*
         * Waiting-area information is always shown while the overlay is
         * expanded and no offer/delivery is active, but the expensive
         * recommendation lookup is cached between expansions.
         */
        private const val WAITING_RECOMMENDATION_REFRESH_MS =
            10L * 60L * 1_000L

        /*
         * A failed lookup can retry sooner than a successful one so a brief
         * network/Places/server problem does not hide waiting info for ten
         * minutes.
         */
        private const val WAITING_RECOMMENDATION_FAILURE_RETRY_MS =
            60_000L

        /*
         * The map-coordinate predictor normally associates its result with the
         * newly persisted offer immediately. These retries cover the opposite
         * completion order without blocking the offer UI.
         */
        private val POST_DELIVERY_PREDICTION_RETRY_DELAYS_MS =
            longArrayOf(
                250L,
                750L,
                1_500L,
                3_000L
            )

        /*
         * A classification scan leaves the overlay
         * visible. This tiny alpha pulse forces Android
         * to produce a fresh screen frame without
         * visibly blinking the overlay.
         */
        private const val CLASSIFICATION_FRAME_DELAY_MS =
            100L

        private const val CLASSIFICATION_PULSE_DURATION_MS =
            60L

        private const val CLASSIFICATION_PULSE_ALPHA =
            0.985f

        /*
         * DashTool may use the entire upper half of the
         * display. OCR begins at the exact halfway point.
         */
        private const val OVERLAY_REGION_FRACTION =
            0.50f

        private const val OVERLAY_EDGE_MARGIN_DP =
            8

        private const val SCAN_PURPOSE_FULL_OFFER =
            "full_offer"

        private const val SCAN_PURPOSE_DECISION =
            "decision"

        private const val SCAN_PURPOSE_LIFECYCLE =
            "lifecycle"

        private const val DATA_LOG_TAG =
            "DashToolData"

        private const val DECISION_LOG_TAG =
            "DashToolDecision"

        private const val LIFECYCLE_LOG_TAG =
            "DashToolLifecycle"
    }

    private data class ScanDetectionContext(
        val detectionSource: String?,
        val scanMode: String,
        val wallTime: Long,
        val elapsedTime: Long,
        val scanPurpose: String =
            SCAN_PURPOSE_FULL_OFFER
    )

    private lateinit var windowManager:
            WindowManager

    private lateinit var layoutParameters:
            WindowManager.LayoutParams

    private lateinit var repository:
            DashToolRepository

    private lateinit var waitingAreaTracker:
            WaitingAreaTracker

    private lateinit var waitingAreaRecommender:
            WaitingAreaRecommender

    private var waitingAreaRecommendationSummary:
            String? = null

    private var recommendedWaitingCenter:
            WaitingAreaTracker.WaitingCenter? = null

    /*
     * Keep the complete recommendation that was actually available when an
     * offer appeared. The telemetry snapshot uses this immutable context later
     * instead of trying to reconstruct why a center was recommended.
     */
    private var lastWaitingAreaRecommendation:
            WaitingAreaRecommender.Recommendation? = null

    private var lastWaitingAreaRecommendationWallTime:
            Long? = null

    /*
     * The recommendation can be displayed repeatedly from memory, while
     * Google/server ranking is refreshed only when this cooldown expires.
     */
    private var waitingAreaRecommendationInFlight =
        false

    /*
     * A waiting-area lookup can finish after a DoorDash offer has appeared.
     * Incrementing this generation invalidates any callback that belongs to
     * an older idle state, preventing stale Places results from being applied
     * to the new order.
     */
    private var waitingAreaRecommendationGeneration =
        0L

    private var waitingAreaDisplayName:
            String? = null

    private var nextWaitingAreaRecommendationRefreshElapsedTime =
        0L

    /*
     * Restaurant GPS coordinates are recorded once per offer when the
     * lifecycle first reaches AT_RESTAURANT.
     */
    private val restaurantLocationRecordedOffers =
        mutableSetOf<String>()

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    /*
     * Order events and the final export must be committed
     * in lifecycle order. Without this lock, the ending
     * event could export before an earlier stage finishes
     * writing to Room.
     */
    private val eventSaveMutex =
        Mutex()

    private var overlayView:
            DashToolOverlayView? = null

    private var isExpanded =
        false

    private var isScanning =
        false

    /*
     * Set when the user taps the collapsed overlay. The overlay stays
     * collapsed until a fresh manual full scan returns. Even an idle /
     * waiting result should then expand so the waiting-area recommendation
     * is visible.
     */
    private var expandAfterManualScan =
        false

    /*
     * True only while the full scan requested by the overlay tap is the
     * scan currently in flight. If the tap happens during a lifecycle scan,
     * expandAfterManualScan remains true and the full scan is started as
     * soon as the current scan finishes.
     */
    private var manualExpandScanStarted =
        false

    private var receiverRegistered =
        false

    private var autoScanReceiverRegistered =
        false

    private var accessibilityStageReceiverRegistered =
        false

    private var currentScreenState =
        ScreenCaptureService
            .SCREEN_STATE_UNKNOWN

    private var restaurant =
        ""

    private var restaurantPlaceId:
            String? = null

    private var pay =
        ""

    private var miles =
        ""

    private var scanStatus =
        ""

    private var timeToRestaurantMinutes:
            Double? = null

    private var distanceToRestaurantMiles:
            Double? = null

    private var routeCapturedAtWallTime:
            Long? = null

    private var routeSource =
        RouteSource.NOT_AVAILABLE

    private var routeStatus =
        RouteStatus.NOT_AVAILABLE

    private var currentScore:
            Double? = null

    private var currentRatingResult:
            OrderRatingResult? = null

    private var currentRestaurantWaitMinutes:
            Double? = null

    /*
     * Offer-specific estimate of the unpaid work after delivery. The score
     * uses this; the displayed order $/hr and order ETA deliberately do not.
     */
    private var predictedPostDeliveryEstimate:
            WaitingAreaRecommender.PostDeliveryEstimate? = null

    private var postDeliveryEstimateOfferId:
            String? = null

    private var postDeliveryEstimateGeneration =
        0L

    private var effectiveHourlyRateForScore:
            Double? = null

    /*
     * currentScore may be calculated once before the offer has an offerId so
     * the offer can be saved and linked to its customer-map prediction. Do
     * not expose that provisional value. The popup shows a score only after
     * the post-delivery route has resolved, or after the lookup has definitively
     * fallen back.
     */
    private var finalScoreReadyForDisplay =
        false

    private var currentDemandEstimate:
            DoorDashDemandTracker.DemandEstimate? = null

    /*
     * Demand is retained only as telemetry for later analysis. It no longer
     * contributes to the displayed grade.
     */
    private var demandScoreAdjustment =
        0.0

    private var demandSummary:
            String? = null

    /*
     * Customer address is read from DoorDash accessibility text only after
     * the lifecycle reaches the customer leg. The raw street address remains
     * in memory and is cleared when the order ends.
     */
    private var customerAddressResult:
            CustomerAddressTracker.Result? = null

    private var gasPriceUsed:
            Double? = null

    private var vehicleMpgUsed:
            Double? = null

    private var estimatedHourlyRate =
        "—"

    private var estimatedCompletionTime =
        "—"

    private var pendingScanContext:
            ScanDetectionContext? = null

    /*
     * Retained after acceptance so later lifecycle
     * events can use the correct offer ID.
     */
    private var currentOfferId:
            String? = null

    /*
     * Acceptance-decision monitor state.
     */
    private var monitoredOfferId:
            String? = null

    private var decisionDeadlineElapsedTime =
        0L

    /*
     * First time a waiting screen was observed while deciding an offer.
     * This is deliberately provisional because DoorDash can flash the
     * waiting screen during the accepted-order -> Google Maps handoff.
     */
    private var decisionWaitingSinceElapsedTime =
        0L

    /*
     * Post-acceptance delivery lifecycle monitor.
     */
    private var lifecycleOfferId:
            String? = null

    private var lifecycleStage =
        ScreenCaptureService
            .LIFECYCLE_STAGE_NONE

    private var lifecycleWaitingSinceElapsedTime =
        0L

    /*
     * Customer arrival remains provisional until the
     * order ends. If Maps navigation is detected again,
     * the stage can return to TO_CUSTOMER without saving
     * a false ARRIVED_AT_CUSTOMER event.
     */
    private var provisionalCustomerArrivalWallTime:
            Long? = null

    private var provisionalCustomerArrivalElapsedTime:
            Long? = null

    /*
     * A single navigation classification immediately after AT_CUSTOMER can be
     * a DoorDash transition frame. Require navigation to remain visible across
     * a confirmation window before rolling the customer arrival back.
     */
    private var customerNavigationSinceElapsedTime =
        0L

    private val savedLifecycleEvents =
        mutableSetOf<String>()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val decisionScanRunnable =
        Runnable {
            runDecisionMonitorStep()
        }

    private val lifecycleScanRunnable =
        Runnable {
            runLifecycleMonitorStep()
        }

    private val restoreOverlayRunnable =
        Runnable {
            val timedOutContext =
                pendingScanContext

            overlayView?.apply {
                visibility =
                    View.VISIBLE

                alpha =
                    1.0f
            }

            if (
                isScanning
            ) {
                isScanning =
                    false

                pendingScanContext =
                    null

                val timedOutManualExpandScan =
                    manualExpandScanStarted &&
                            timedOutContext?.scanPurpose ==
                            SCAN_PURPOSE_FULL_OFFER

                if (
                    timedOutManualExpandScan
                ) {
                    manualExpandScanStarted =
                        false

                    if (
                        expandAfterManualScan
                    ) {
                        expandAfterManualScan =
                            false

                        /*
                         * The user explicitly asked to open the popup. If
                         * the manual scan times out, still honor that tap
                         * and show the current idle / recommendation state.
                         */
                        isExpanded =
                            true
                    }
                }

                updateOverlayAppearance()

                if (
                    !timedOutManualExpandScan &&
                    expandAfterManualScan &&
                    !isScanning
                ) {
                    manualExpandScanStarted =
                        requestScreenScan(
                            detectionSource =
                                OfferDetectionSource
                                    .MANUAL_SCAN,

                            scanMode =
                                ScreenCaptureService
                                    .SCAN_MODE_FULL_OFFER
                        )

                    if (
                        !manualExpandScanStarted
                    ) {
                        expandAfterManualScan =
                            false

                        isExpanded =
                            true

                        updateOverlayAppearance()
                    }
                }
            }

            /*
             * A failed classification scan should not
             * permanently stop either monitor.
             */
            when (
                timedOutContext?.scanPurpose
            ) {
                SCAN_PURPOSE_DECISION -> {
                    if (
                        monitoredOfferId != null
                    ) {
                        scheduleNextDecisionScan(
                            BUSY_RETRY_DELAY_MS
                        )
                    }
                }

                SCAN_PURPOSE_LIFECYCLE -> {
                    if (
                        lifecycleOfferId != null
                    ) {
                        scheduleNextLifecycleScan(
                            BUSY_RETRY_DELAY_MS
                        )
                    }
                }
            }
        }

    private val scanResultReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    ScreenCaptureService
                        .ACTION_SCAN_RESULT
                ) {
                    return
                }

                val scanContext =
                    pendingScanContext

                val incomingScanMode =
                    intent.getStringExtra(
                        ScreenCaptureService
                            .EXTRA_SCAN_MODE
                    )
                        ?: ScreenCaptureService
                            .SCAN_MODE_FULL_OFFER

                val incomingScreenState =
                    intent.getStringExtra(
                        ScreenCaptureService
                            .EXTRA_SCREEN_STATE
                    )
                        ?: ScreenCaptureService
                            .SCREEN_STATE_UNKNOWN

                val incomingLifecycleStage =
                    intent.getStringExtra(
                        ScreenCaptureService
                            .EXTRA_LIFECYCLE_STAGE
                    )
                        ?: ScreenCaptureService
                            .LIFECYCLE_STAGE_NONE

                scanStatus =
                    intent.getStringExtra(
                        ScreenCaptureService
                            .EXTRA_SCAN_STATUS
                    )
                        ?: scanStatus

                isScanning =
                    false

                mainHandler.removeCallbacks(
                    restoreOverlayRunnable
                )

                overlayView?.apply {
                    visibility =
                        View.VISIBLE

                    alpha =
                        1.0f
                }

                if (
                    incomingScanMode ==
                    ScreenCaptureService
                        .SCAN_MODE_CLASSIFY_ONLY
                ) {
                    when (
                        scanContext?.scanPurpose
                    ) {
                        SCAN_PURPOSE_LIFECYCLE ->
                            handleLifecycleScanResult(
                                intent =
                                    intent,

                                incomingScreenState =
                                    incomingScreenState,

                                incomingLifecycleStage =
                                    incomingLifecycleStage,

                                scanContext =
                                    scanContext
                            )

                        else ->
                            handleDecisionScanResult(
                                intent =
                                    intent,

                                incomingScreenState =
                                    incomingScreenState,

                                incomingLifecycleStage =
                                    incomingLifecycleStage,

                                scanContext =
                                    scanContext
                            )
                    }
                } else {
                    handleFullOfferScanResult(
                        intent =
                            intent,

                        incomingScreenState =
                            incomingScreenState,

                        incomingLifecycleStage =
                            incomingLifecycleStage,

                        scanContext =
                            scanContext
                    )
                }

                pendingScanContext =
                    null

                val completedManualExpandScan =
                    manualExpandScanStarted &&
                            scanContext?.scanPurpose ==
                            SCAN_PURPOSE_FULL_OFFER

                if (
                    completedManualExpandScan
                ) {
                    manualExpandScanStarted =
                        false

                    if (
                        expandAfterManualScan
                    ) {
                        expandAfterManualScan =
                            false

                        /*
                         * This is the key behavior: OFFER and active-order
                         * results already expand in handleFullOfferScanResult(),
                         * but WAITING / UNKNOWN / no-readable-offer results must
                         * also expand because the user tapped the popup.
                         */
                        isExpanded =
                            true
                    }
                }

                updateOverlayAppearance()

                if (
                    !completedManualExpandScan &&
                    expandAfterManualScan &&
                    !isScanning
                ) {
                    /*
                     * The user tapped while a lifecycle / decision scan was
                     * already running. Start the requested fresh full scan
                     * immediately after that scan finishes.
                     */
                    manualExpandScanStarted =
                        requestScreenScan(
                            detectionSource =
                                OfferDetectionSource
                                    .MANUAL_SCAN,

                            scanMode =
                                ScreenCaptureService
                                    .SCAN_MODE_FULL_OFFER
                        )

                    if (
                        !manualExpandScanStarted
                    ) {
                        expandAfterManualScan =
                            false

                        isExpanded =
                            true

                        updateOverlayAppearance()
                    }
                }
            }
        }

    private val automaticScanRequestReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    DoorDashNotificationListenerService
                        .ACTION_AUTO_SCAN_REQUEST
                ) {
                    return
                }

                /*
                 * A DoorDash notification means an offer is arriving even if
                 * the screen scan has not parsed it yet. Invalidate any
                 * waiting-area lookup immediately so a late Places callback
                 * cannot bleed into this offer.
                 */
                invalidateWaitingAreaRecommendationRequest()
                invalidatePredictedPostDeliveryEstimate()

                /*
                 * Keep old or idle content collapsed while
                 * the new offer is being captured. A valid
                 * result expands only after applyOrderResult()
                 * has committed the new values.
                 */
                isExpanded =
                    false

                updateOverlayAppearance()

                requestScreenScan(
                    detectionSource =
                        OfferDetectionSource
                            .NOTIFICATION,

                    scanMode =
                        ScreenCaptureService
                            .SCAN_MODE_FULL_OFFER
                )
            }
        }

    private val accessibilityStageReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    DashToolAccessibilityService
                        .ACTION_ACCESSIBILITY_STAGE_DETECTED
                ) {
                    return
                }

                val incomingScreenState =
                    intent.getStringExtra(
                        DashToolAccessibilityService
                            .EXTRA_SCREEN_STATE
                    )
                        ?: ScreenCaptureService
                            .SCREEN_STATE_UNKNOWN

                val incomingLifecycleStage =
                    intent.getStringExtra(
                        DashToolAccessibilityService
                            .EXTRA_LIFECYCLE_STAGE
                    )
                        ?: ScreenCaptureService
                            .LIFECYCLE_STAGE_NONE

                val eventWallTime =
                    intent.getLongExtra(
                        DashToolAccessibilityService
                            .EXTRA_EVENT_WALL_TIME,
                        System.currentTimeMillis()
                    )

                val eventElapsedTime =
                    intent.getLongExtra(
                        DashToolAccessibilityService
                            .EXTRA_EVENT_ELAPSED_TIME,
                        SystemClock.elapsedRealtime()
                    )

                val nodeText =
                    intent.getStringExtra(
                        DashToolAccessibilityService
                            .EXTRA_NODE_TEXT
                    )
                        .orEmpty()

                val accessibilityContext =
                    ScanDetectionContext(
                        detectionSource =
                            null,

                        scanMode =
                            ScreenCaptureService
                                .SCAN_MODE_CLASSIFY_ONLY,

                        wallTime =
                            eventWallTime,

                        elapsedTime =
                            eventElapsedTime,

                        scanPurpose =
                            SCAN_PURPOSE_LIFECYCLE
                    )

                Log.d(
                    LIFECYCLE_LOG_TAG,
                    "Accessibility result: " +
                            "$incomingScreenState / " +
                            "$incomingLifecycleStage; text=" +
                            nodeText
                                .replace(
                                    "\n",
                                    " | "
                                )
                                .take(
                                    220
                                )
                )

                when (
                    incomingScreenState
                ) {
                    ScreenCaptureService
                        .SCREEN_STATE_WAITING -> {

                        if (
                            monitoredOfferId != null
                        ) {
                            handleWaitingDuringDecision(
                                scanContext =
                                    accessibilityContext
                            )
                        } else if (
                            lifecycleOfferId != null
                        ) {
                            val normalizedNodeText =
                                nodeText
                                    .lowercase(
                                        Locale.US
                                    )

                            val explicitCompletion =
                                normalizedNodeText.contains(
                                    "this dash so far"
                                ) &&
                                        normalizedNodeText.contains(
                                            "continue dashing"
                                        )

                            if (
                                explicitCompletion
                            ) {
                                finalizeLifecycleFromWaiting(
                                    scanContext =
                                        accessibilityContext
                                )
                            } else {
                                handleWaitingDuringLifecycle(
                                    scanContext =
                                        accessibilityContext
                                )
                            }
                        }
                    }

                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY -> {

                        lifecycleWaitingSinceElapsedTime =
                            0L

                        val addressOfferId =
                            monitoredOfferId
                                ?: lifecycleOfferId

                        if (
                            addressOfferId != null
                        ) {
                            CustomerAddressTracker
                                .captureFromAccessibilityText(
                                    context =
                                        applicationContext,
                                    offerId =
                                        addressOfferId,
                                    lifecycleStage =
                                        incomingLifecycleStage,
                                    nodeText =
                                        nodeText
                                ) {
                                        result ->

                                    mainHandler.post {
                                        val stillCurrent =
                                            lifecycleOfferId ==
                                                    result.offerId ||
                                                    monitoredOfferId ==
                                                    result.offerId

                                        if (
                                            stillCurrent
                                        ) {
                                            customerAddressResult =
                                                result

                                            updateOverlayAppearance()
                                        }
                                    }
                                }
                        }

                        val offerBeingDecided =
                            monitoredOfferId

                        if (
                            offerBeingDecided != null
                        ) {
                            currentScreenState =
                                ScreenCaptureService
                                    .SCREEN_STATE_ACTIVE_DELIVERY

                            resolveCurrentDecision(
                                eventType =
                                    OrderEventType
                                        .OFFER_ACCEPTED,

                                confidence =
                                    DataConfidence.HIGH,

                                scanContext =
                                    accessibilityContext
                            )

                            processLifecycleStage(
                                offerId =
                                    offerBeingDecided,

                                detectedStage =
                                    incomingLifecycleStage,

                                scanContext =
                                    accessibilityContext
                            )
                        } else {
                            val activeOfferId =
                                lifecycleOfferId

                            if (
                                activeOfferId != null
                            ) {
                                currentScreenState =
                                    ScreenCaptureService
                                        .SCREEN_STATE_ACTIVE_DELIVERY

                                val returningToCustomerDirections =
                                    lifecycleStage ==
                                            ScreenCaptureService
                                                .LIFECYCLE_STAGE_AT_CUSTOMER &&
                                            incomingLifecycleStage ==
                                            ScreenCaptureService
                                                .LIFECYCLE_STAGE_TO_CUSTOMER

                                if (
                                    returningToCustomerDirections
                                ) {
                                    confirmNavigationAfterCustomerArrival(
                                        offerId =
                                            activeOfferId,
                                        observedElapsedTime =
                                            eventElapsedTime
                                    )
                                } else {
                                    customerNavigationSinceElapsedTime =
                                        0L

                                    processLifecycleStage(
                                        offerId =
                                            activeOfferId,

                                        detectedStage =
                                            incomingLifecycleStage,

                                        scanContext =
                                            accessibilityContext
                                    )
                                }
                            }
                        }

                        /*
                         * Node text already produced an immediate result.
                         * Delay the next OCR check; it is now only a backup.
                         */
                        if (
                            lifecycleOfferId != null
                        ) {
                            scheduleNextLifecycleScan(
                                if (
                                    lifecycleStage ==
                                    ScreenCaptureService
                                        .LIFECYCLE_STAGE_AT_CUSTOMER &&
                                    customerNavigationSinceElapsedTime !=
                                    0L
                                ) {
                                    CUSTOMER_NAVIGATION_RECHECK_MS
                                } else {
                                    LIFECYCLE_SCAN_INTERVAL_MS
                                }
                            )
                        }
                    }
                }

                updateOverlayAppearance()
            }
        }
    override fun onCreate() {
        super.onCreate()

        if (
            !Settings.canDrawOverlays(
                this
            )
        ) {
            stopSelf()
            return
        }

        repository =
            DashToolRepository.getInstance(
                this
            )

        waitingAreaTracker =
            WaitingAreaTracker(
                applicationContext
            )

        waitingAreaRecommender =
            WaitingAreaRecommender(
                applicationContext
            )

        serviceScope.launch {
            val retrySummary =
                OrderUploadManager
                    .retryPending(
                        applicationContext
                    )

            Log.d(
                DATA_LOG_TAG,
                "Upload retry finished: " +
                        "${retrySummary.uploadedCount} uploaded, " +
                        "${retrySummary.remainingCount} pending."
            )

            val waitingRetrySummary =
                WaitingDataUploadManager
                    .retryPending(
                        applicationContext
                    )

            Log.d(
                DATA_LOG_TAG,
                "Waiting-data retry finished: " +
                        "${waitingRetrySummary.uploadedCount} uploaded, " +
                        "${waitingRetrySummary.remainingCount} pending."
            )

            CustomerMapLearningManager.retryPending(
                applicationContext
            )

            CustomerMapLearningManager
                .resumePendingConfirmation(
                    applicationContext
                )
        }

        restaurant =
            getString(
                R.string.restaurant_not_found
            )

        pay =
            getString(
                R.string.pay_not_found
            )

        miles =
            getString(
                R.string.mileage_not_found
            )

        scanStatus =
            getString(
                R.string.scan_status_reader_inactive
            )

        loadLatestScan()
        registerScanReceiver()
        registerAutomaticScanReceiver()
        registerAccessibilityStageReceiver()
        createNotificationChannel()
        startOverlayForegroundService()
        createOverlay()
    }

    private fun registerScanReceiver() {
        ContextCompat.registerReceiver(
            this,
            scanResultReceiver,
            IntentFilter(
                ScreenCaptureService
                    .ACTION_SCAN_RESULT
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered =
            true
    }

    private fun registerAutomaticScanReceiver() {
        ContextCompat.registerReceiver(
            this,
            automaticScanRequestReceiver,
            IntentFilter(
                DoorDashNotificationListenerService
                    .ACTION_AUTO_SCAN_REQUEST
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        autoScanReceiverRegistered =
            true
    }

    private fun registerAccessibilityStageReceiver() {
        ContextCompat.registerReceiver(
            this,
            accessibilityStageReceiver,
            IntentFilter(
                DashToolAccessibilityService
                    .ACTION_ACCESSIBILITY_STAGE_DETECTED
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        accessibilityStageReceiverRegistered =
            true
    }

    private fun shouldShowWaitingInformation(): Boolean {
        return (
                lifecycleOfferId == null &&
                        monitoredOfferId == null &&
                        currentScreenState !=
                        ScreenCaptureService
                            .SCREEN_STATE_OFFER &&
                        currentScreenState !=
                        ScreenCaptureService
                            .SCREEN_STATE_ACTIVE_DELIVERY
                )
    }

    private fun maybeRefreshWaitingAreaRecommendation() {
        if (
            !isExpanded ||
            !shouldShowWaitingInformation() ||
            waitingAreaRecommendationInFlight
        ) {
            return
        }

        if (
            SystemClock.elapsedRealtime() <
            nextWaitingAreaRecommendationRefreshElapsedTime
        ) {
            /*
             * Reuse the cached recommendation immediately. Expanding the
             * overlay does not make another Places request during cooldown.
             */
            return
        }

        requestWaitingAreaRecommendation(
            forceRefresh = false
        )
    }

    private fun requestWaitingAreaRecommendation(
        forceRefresh: Boolean = true
    ) {
        if (
            !::waitingAreaRecommender.isInitialized ||
            waitingAreaRecommendationInFlight
        ) {
            return
        }

        val nowElapsed =
            SystemClock.elapsedRealtime()

        if (
            !forceRefresh &&
            nowElapsed <
            nextWaitingAreaRecommendationRefreshElapsedTime
        ) {
            return
        }

        waitingAreaRecommendationInFlight =
            true

        val requestGeneration =
            ++waitingAreaRecommendationGeneration

        /*
         * Reserve the normal cooldown immediately. The callback shortens it
         * to the failure retry interval when the lookup fails.
         */
        nextWaitingAreaRecommendationRefreshElapsedTime =
            nowElapsed +
                    WAITING_RECOMMENDATION_REFRESH_MS

        waitingAreaRecommendationSummary =
            "Finding best waiting area..."

        waitingAreaDisplayName =
            null

        updateOverlayAppearance()

        waitingAreaRecommender.recommend { recommendation ->
            /*
             * An offer may have appeared while Places/server work was still
             * running. Ignore that old callback completely rather than letting
             * idle-state data leak into the active order.
             */
            if (
                requestGeneration !=
                waitingAreaRecommendationGeneration ||
                !shouldShowWaitingInformation()
            ) {
                Log.d(
                    DATA_LOG_TAG,
                    "Ignored stale waiting-area recommendation callback."
                )

                return@recommend
            }

            waitingAreaRecommendationInFlight =
                false

            if (recommendation == null) {
                recommendedWaitingCenter =
                    null

                lastWaitingAreaRecommendation =
                    null

                lastWaitingAreaRecommendationWallTime =
                    null

                waitingAreaDisplayName =
                    null

                waitingAreaRecommendationSummary =
                    "No nearby waiting area found"

                nextWaitingAreaRecommendationRefreshElapsedTime =
                    SystemClock.elapsedRealtime() +
                            WAITING_RECOMMENDATION_FAILURE_RETRY_MS

                updateOverlayAppearance()
                return@recommend
            }

            waitingAreaTracker.setRecommendation(
                recommendedCenter = recommendation.recommendedCenter,
                candidates = recommendation.candidateCenters
            )

            recommendedWaitingCenter =
                recommendation.recommendedCenter

            lastWaitingAreaRecommendation =
                recommendation

            lastWaitingAreaRecommendationWallTime =
                System.currentTimeMillis()

            waitingAreaDisplayName =
                recommendation.displayName

            waitingAreaRecommendationSummary =
                recommendation.overlaySummary()

            nextWaitingAreaRecommendationRefreshElapsedTime =
                SystemClock.elapsedRealtime() +
                        WAITING_RECOMMENDATION_REFRESH_MS

            updateOverlayAppearance()
        }
    }

    private fun invalidateWaitingAreaRecommendationRequest() {
        waitingAreaRecommendationGeneration +=
            1L

        waitingAreaRecommendationInFlight =
            false
    }

    private fun handleFullOfferScanResult(
        intent: Intent,
        incomingScreenState: String,
        incomingLifecycleStage: String,
        scanContext: ScanDetectionContext?
    ) {
        when (
            incomingScreenState
        ) {
            ScreenCaptureService
                .SCREEN_STATE_OFFER -> {

                invalidateWaitingAreaRecommendationRequest()
                invalidatePredictedPostDeliveryEstimate()

                /*
                 * A real new offer is also strong evidence
                 * that a previously monitored lifecycle has
                 * ended. Finalize it before applying the new
                 * route values so the old order exports its
                 * own snapshot rather than the new offer's.
                 */
                if (
                    lifecycleOfferId != null
                ) {
                    finalizeLifecycleForNewOffer(
                        scanContext =
                            scanContext
                    )
                }

                applyOrderResult(
                    intent =
                        intent,

                    screenState =
                        incomingScreenState
                )

                saveCurrentOffer(
                    scanDetectionContext =
                        scanContext
                )

                /*
                 * scanResultReceiver redraws after this
                 * method returns, so the popup's first
                 * expanded frame contains fresh values.
                 */
                isExpanded =
                    true
            }

            ScreenCaptureService
                .SCREEN_STATE_ACTIVE_DELIVERY -> {

                applyOrderResult(
                    intent =
                        intent,

                    screenState =
                        incomingScreenState
                )

                isExpanded =
                    true

                val offerBeingDecided =
                    monitoredOfferId

                if (
                    offerBeingDecided != null
                ) {
                    resolveCurrentDecision(
                        eventType =
                            OrderEventType
                                .OFFER_ACCEPTED,

                        confidence =
                            DataConfidence.HIGH,

                        scanContext =
                            scanContext
                    )

                    processLifecycleStage(
                        offerId =
                            offerBeingDecided,

                        detectedStage =
                            incomingLifecycleStage,

                        scanContext =
                            scanContext
                    )
                } else {
                    val activeLifecycleOfferId =
                        lifecycleOfferId

                    if (
                        activeLifecycleOfferId != null
                    ) {
                        val returningToCustomerDirections =
                            lifecycleStage ==
                                    ScreenCaptureService
                                        .LIFECYCLE_STAGE_AT_CUSTOMER &&
                                    incomingLifecycleStage ==
                                    ScreenCaptureService
                                        .LIFECYCLE_STAGE_TO_CUSTOMER

                        if (
                            returningToCustomerDirections
                        ) {
                            val rolledBack =
                                confirmNavigationAfterCustomerArrival(
                                    offerId =
                                        activeLifecycleOfferId,
                                    observedElapsedTime =
                                        scanContext?.elapsedTime
                                            ?: SystemClock.elapsedRealtime()
                                )

                            scheduleNextLifecycleScan(
                                if (
                                    rolledBack
                                ) {
                                    LIFECYCLE_SCAN_INTERVAL_MS
                                } else {
                                    CUSTOMER_NAVIGATION_RECHECK_MS
                                }
                            )
                        } else {
                            customerNavigationSinceElapsedTime =
                                0L

                            processLifecycleStage(
                                offerId =
                                    activeLifecycleOfferId,

                                detectedStage =
                                    incomingLifecycleStage,

                                scanContext =
                                    scanContext
                            )
                        }
                    }
                }
            }

            ScreenCaptureService
                .SCREEN_STATE_NAVIGATION -> {

                val offerBeingDecided =
                    monitoredOfferId

                if (
                    offerBeingDecided != null
                ) {
                    currentScreenState =
                        ScreenCaptureService
                            .SCREEN_STATE_ACTIVE_DELIVERY

                    Log.d(
                        DECISION_LOG_TAG,
                        "Navigation detected during decision " +
                                "window for $offerBeingDecided; " +
                                "treating offer as accepted."
                    )

                    resolveCurrentDecision(
                        eventType =
                            OrderEventType
                                .OFFER_ACCEPTED,

                        confidence =
                            DataConfidence.MEDIUM,

                        scanContext =
                            scanContext
                    )
                } else {
                    val activeLifecycleOfferId =
                        lifecycleOfferId

                    if (
                        activeLifecycleOfferId != null
                    ) {
                        currentScreenState =
                            ScreenCaptureService
                                .SCREEN_STATE_ACTIVE_DELIVERY

                        val rolledBack =
                            confirmNavigationAfterCustomerArrival(
                                offerId =
                                    activeLifecycleOfferId,
                                observedElapsedTime =
                                    scanContext?.elapsedTime
                                        ?: SystemClock.elapsedRealtime()
                            )

                        scheduleNextLifecycleScan(
                            if (
                                lifecycleStage ==
                                ScreenCaptureService
                                    .LIFECYCLE_STAGE_AT_CUSTOMER &&
                                !rolledBack
                            ) {
                                CUSTOMER_NAVIGATION_RECHECK_MS
                            } else {
                                NAVIGATION_SCAN_INTERVAL_MS
                            }
                        )
                    }
                }
            }

            ScreenCaptureService
                .SCREEN_STATE_WAITING -> {

                customerNavigationSinceElapsedTime =
                    0L

                if (
                    monitoredOfferId != null
                ) {
                    /*
                     * Do not clear the offer display yet. DoorDash may flash
                     * the waiting screen for a fraction of a second after
                     * Accept while it launches Google Maps.
                     */
                    handleWaitingDuringDecision(
                        scanContext =
                            scanContext
                    )
                } else {
                    showWaitingState()

                    if (
                        lifecycleOfferId != null
                    ) {
                        handleWaitingDuringLifecycle(
                            scanContext =
                                scanContext
                        )
                    } else {
                        resetToIdleAfterOrder(
                            reason =
                                "Confirmed waiting screen"
                        )
                    }
                }
            }

            else -> {
                /*
                 * Unknown breaks the continuity of a suspected navigation
                 * rollback. Preserve the display state, but require a fresh
                 * full confirmation window if navigation appears again.
                 */
                customerNavigationSinceElapsedTime =
                    0L
            }
        }
    }

    private fun handleDecisionScanResult(
        intent: Intent,
        incomingScreenState: String,
        incomingLifecycleStage: String,
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            monitoredOfferId
                ?: return

        Log.d(
            DECISION_LOG_TAG,
            "Decision scan for $offerId returned " +
                    incomingScreenState +
                    " / " +
                    incomingLifecycleStage
        )

        when (
            incomingScreenState
        ) {
            ScreenCaptureService
                .SCREEN_STATE_ACTIVE_DELIVERY -> {

                applyOrderResult(
                    intent =
                        intent,

                    screenState =
                        incomingScreenState
                )

                resolveCurrentDecision(
                    eventType =
                        OrderEventType
                            .OFFER_ACCEPTED,

                    confidence =
                        DataConfidence.HIGH,

                    scanContext =
                        scanContext
                )

                processLifecycleStage(
                    offerId =
                        offerId,

                    detectedStage =
                        incomingLifecycleStage,

                    scanContext =
                        scanContext
                )
            }

            ScreenCaptureService
                .SCREEN_STATE_NAVIGATION -> {

                currentScreenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY

                Log.d(
                    DECISION_LOG_TAG,
                    "Navigation detected during decision " +
                            "window for $offerId; treating " +
                            "offer as accepted."
                )

                resolveCurrentDecision(
                    eventType =
                        OrderEventType
                            .OFFER_ACCEPTED,

                    confidence =
                        DataConfidence.MEDIUM,

                    scanContext =
                        scanContext
                )
            }

            ScreenCaptureService
                .SCREEN_STATE_WAITING -> {

                handleWaitingDuringDecision(
                    scanContext =
                        scanContext
                )
            }

            ScreenCaptureService
                .SCREEN_STATE_OFFER -> {

                currentScreenState =
                    ScreenCaptureService
                        .SCREEN_STATE_OFFER

                decisionWaitingSinceElapsedTime =
                    0L

                scheduleNextDecisionScan(
                    DECISION_SCAN_INTERVAL_MS
                )
            }

            else -> {
                scheduleNextDecisionScan(
                    DECISION_SCAN_INTERVAL_MS
                )
            }
        }
    }

    private fun handleLifecycleScanResult(
        intent: Intent,
        incomingScreenState: String,
        incomingLifecycleStage: String,
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            lifecycleOfferId
                ?: return

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Lifecycle scan for $offerId returned " +
                    incomingScreenState +
                    " / " +
                    incomingLifecycleStage
        )

        when (
            incomingScreenState
        ) {
            ScreenCaptureService
                .SCREEN_STATE_ACTIVE_DELIVERY -> {

                lifecycleWaitingSinceElapsedTime =
                    0L

                applyOrderResult(
                    intent =
                        intent,

                    screenState =
                        incomingScreenState
                )

                val returningToCustomerDirections =
                    lifecycleStage ==
                            ScreenCaptureService
                                .LIFECYCLE_STAGE_AT_CUSTOMER &&
                            incomingLifecycleStage ==
                            ScreenCaptureService
                                .LIFECYCLE_STAGE_TO_CUSTOMER

                if (
                    returningToCustomerDirections
                ) {
                    val rolledBack =
                        confirmNavigationAfterCustomerArrival(
                            offerId =
                                offerId,
                            observedElapsedTime =
                                scanContext?.elapsedTime
                                    ?: SystemClock.elapsedRealtime()
                        )

                    scheduleNextLifecycleScan(
                        if (
                            rolledBack
                        ) {
                            LIFECYCLE_SCAN_INTERVAL_MS
                        } else {
                            CUSTOMER_NAVIGATION_RECHECK_MS
                        }
                    )
                } else {
                    customerNavigationSinceElapsedTime =
                        0L

                    processLifecycleStage(
                        offerId =
                            offerId,

                        detectedStage =
                            incomingLifecycleStage,

                        scanContext =
                            scanContext
                    )

                    scheduleNextLifecycleScan(
                        LIFECYCLE_SCAN_INTERVAL_MS
                    )
                }
            }

            ScreenCaptureService
                .SCREEN_STATE_NAVIGATION -> {

                lifecycleWaitingSinceElapsedTime =
                    0L

                currentScreenState =
                    ScreenCaptureService
                        .SCREEN_STATE_ACTIVE_DELIVERY

                val rolledBack =
                    confirmNavigationAfterCustomerArrival(
                        offerId =
                            offerId,
                        observedElapsedTime =
                            scanContext?.elapsedTime
                                ?: SystemClock.elapsedRealtime()
                    )

                Log.d(
                    LIFECYCLE_LOG_TAG,
                    "Navigation detected for $offerId; " +
                            "current lifecycle stage is " +
                            "$lifecycleStage."
                )

                scheduleNextLifecycleScan(
                    if (
                        lifecycleStage ==
                        ScreenCaptureService
                            .LIFECYCLE_STAGE_AT_CUSTOMER &&
                        !rolledBack
                    ) {
                        CUSTOMER_NAVIGATION_RECHECK_MS
                    } else {
                        NAVIGATION_SCAN_INTERVAL_MS
                    }
                )
            }

            ScreenCaptureService
                .SCREEN_STATE_WAITING -> {

                customerNavigationSinceElapsedTime =
                    0L

                handleWaitingDuringLifecycle(
                    scanContext =
                        scanContext
                )
            }

            ScreenCaptureService
                .SCREEN_STATE_OFFER -> {

                customerNavigationSinceElapsedTime =
                    0L

                Log.w(
                    LIFECYCLE_LOG_TAG,
                    "Ignoring offer-like classification " +
                            "during active lifecycle for " +
                            "$offerId."
                )

                scheduleNextLifecycleScan(
                    LIFECYCLE_SCAN_INTERVAL_MS
                )
            }

            else -> {
                customerNavigationSinceElapsedTime =
                    0L

                scheduleNextLifecycleScan(
                    LIFECYCLE_SCAN_INTERVAL_MS
                )
            }
        }
    }

    private fun handleWaitingDuringDecision(
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            monitoredOfferId
                ?: return

        val observedElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        if (
            decisionWaitingSinceElapsedTime ==
            0L
        ) {
            decisionWaitingSinceElapsedTime =
                observedElapsedTime

            Log.d(
                DECISION_LOG_TAG,
                "Transient waiting screen detected for $offerId; " +
                        "holding decision for Google Maps handoff."
            )

            scheduleNextDecisionScan(
                WAITING_DECISION_RECHECK_MS
            )

            return
        }

        val waitingDuration =
            observedElapsedTime -
                    decisionWaitingSinceElapsedTime

        if (
            waitingDuration <
            WAITING_DECISION_CONFIRM_MS
        ) {
            Log.d(
                DECISION_LOG_TAG,
                "Waiting screen for $offerId has lasted " +
                        "$waitingDuration ms; still allowing " +
                        "accepted-order navigation handoff."
            )

            scheduleNextDecisionScan(
                WAITING_DECISION_RECHECK_MS
            )

            return
        }

        Log.d(
            DECISION_LOG_TAG,
            "Waiting screen persisted for $offerId for " +
                    "$waitingDuration ms; resolving as not accepted."
        )

        resolveCurrentDecision(
            eventType =
                OrderEventType
                    .OFFER_NOT_ACCEPTED,

            confidence =
                DataConfidence.HIGH,

            scanContext =
                scanContext
        )
    }

    private fun handleWaitingDuringLifecycle(
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            lifecycleOfferId
                ?: return

        val observedElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        if (
            lifecycleWaitingSinceElapsedTime ==
            0L
        ) {
            lifecycleWaitingSinceElapsedTime =
                observedElapsedTime

            Log.d(
                LIFECYCLE_LOG_TAG,
                "Transient waiting screen detected during lifecycle for " +
                        "$offerId; waiting for confirmation."
            )

            scheduleNextLifecycleScan(
                WAITING_LIFECYCLE_RECHECK_MS
            )

            return
        }

        val waitingDuration =
            observedElapsedTime -
                    lifecycleWaitingSinceElapsedTime

        if (
            waitingDuration <
            WAITING_LIFECYCLE_CONFIRM_MS
        ) {
            scheduleNextLifecycleScan(
                WAITING_LIFECYCLE_RECHECK_MS
            )

            return
        }

        showWaitingState()

        finalizeLifecycleFromWaiting(
            scanContext =
                scanContext
        )
    }

    private fun startDecisionMonitor(
        offerId: String,
        originalDetectedAtElapsedTime: Long
    ) {
        val existingMonitoredOfferId =
            monitoredOfferId

        /*
         * A duplicate scan that reused the same ID must
         * not reset the original decision period.
         */
        if (
            existingMonitoredOfferId ==
            offerId
        ) {
            return
        }

        /*
         * Seeing a genuinely different offer means the
         * previous one was not accepted.
         */
        if (
            existingMonitoredOfferId != null &&
            existingMonitoredOfferId != offerId
        ) {
            saveDecisionEvent(
                offerId =
                    existingMonitoredOfferId,

                eventType =
                    OrderEventType
                        .OFFER_NOT_ACCEPTED,

                wallTime =
                    System.currentTimeMillis(),

                elapsedTime =
                    SystemClock.elapsedRealtime(),

                source =
                    OrderEventSource
                        .SCREEN_STATE,

                confidence =
                    DataConfidence.HIGH
            )
        }

        stopDecisionMonitor()

        monitoredOfferId =
            offerId

        decisionWaitingSinceElapsedTime =
            0L

        decisionDeadlineElapsedTime =
            originalDetectedAtElapsedTime +
                    OFFER_DECISION_WINDOW_MS

        val remainingTime =
            decisionDeadlineElapsedTime -
                    SystemClock.elapsedRealtime()

        Log.d(
            DECISION_LOG_TAG,
            "Started decision monitor for $offerId with " +
                    "$remainingTime ms remaining."
        )

        if (
            remainingTime <= 0L
        ) {
            resolveCurrentDecision(
                eventType =
                    OrderEventType
                        .OFFER_NOT_ACCEPTED,

                confidence =
                    DataConfidence.LOW,

                scanContext =
                    null
            )

            return
        }

        scheduleNextDecisionScan(
            minOf(
                FIRST_DECISION_SCAN_DELAY_MS,
                remainingTime
            )
        )
    }

    private fun scheduleNextDecisionScan(
        requestedDelayMs: Long
    ) {
        val offerId =
            monitoredOfferId
                ?: return

        val remainingTime =
            decisionDeadlineElapsedTime -
                    SystemClock.elapsedRealtime()

        if (
            remainingTime <= 0L
        ) {
            Log.d(
                DECISION_LOG_TAG,
                "Decision window expired for $offerId."
            )

            resolveCurrentDecision(
                eventType =
                    OrderEventType
                        .OFFER_NOT_ACCEPTED,

                confidence =
                    DataConfidence.LOW,

                scanContext =
                    null
            )

            return
        }

        mainHandler.removeCallbacks(
            decisionScanRunnable
        )

        mainHandler.postDelayed(
            decisionScanRunnable,
            minOf(
                requestedDelayMs,
                remainingTime
            )
        )
    }

    private fun runDecisionMonitorStep() {
        val offerId =
            monitoredOfferId
                ?: return

        val remainingTime =
            decisionDeadlineElapsedTime -
                    SystemClock.elapsedRealtime()

        if (
            remainingTime <= 0L
        ) {
            resolveCurrentDecision(
                eventType =
                    OrderEventType
                        .OFFER_NOT_ACCEPTED,

                confidence =
                    DataConfidence.LOW,

                scanContext =
                    null
            )

            return
        }

        if (
            isScanning
        ) {
            scheduleNextDecisionScan(
                BUSY_RETRY_DELAY_MS
            )

            return
        }

        Log.d(
            DECISION_LOG_TAG,
            "Requesting decision scan for $offerId."
        )

        val scanStarted =
            requestScreenScan(
                detectionSource =
                    null,

                scanMode =
                    ScreenCaptureService
                        .SCAN_MODE_CLASSIFY_ONLY,

                scanPurpose =
                    SCAN_PURPOSE_DECISION
            )

        if (
            !scanStarted
        ) {
            scheduleNextDecisionScan(
                BUSY_RETRY_DELAY_MS
            )
        }
    }

    private fun resolveCurrentDecision(
        eventType: String,
        confidence: String,
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            monitoredOfferId
                ?: return

        val eventWallTime =
            scanContext?.wallTime
                ?: System.currentTimeMillis()

        val eventElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        stopDecisionMonitor()

        saveDecisionEvent(
            offerId =
                offerId,

            eventType =
                eventType,

            wallTime =
                eventWallTime,

            elapsedTime =
                eventElapsedTime,

            source =
                OrderEventSource
                    .SCREEN_STATE,

            confidence =
                confidence
        )

        if (
            eventType ==
            OrderEventType
                .OFFER_ACCEPTED
        ) {
            waitingAreaTracker
                .onOfferAccepted()

            currentOfferId =
                offerId

            startLifecycleMonitor(
                offerId
            )
        } else {
            waitingAreaTracker
                .onOfferNotAccepted(
                    rejectedOfferId =
                        offerId,
                    rejectedAtWallTime =
                        eventWallTime
                )

            if (
                currentOfferId ==
                offerId
            ) {
                currentOfferId =
                    null
            }

            stopLifecycleMonitor()

            resetToIdleAfterOrder(
                reason =
                    "Offer was not accepted"
            )
        }

        Log.d(
            DECISION_LOG_TAG,
            "Resolved $offerId as $eventType " +
                    "with $confidence confidence."
        )
    }

    private fun stopDecisionMonitor() {
        mainHandler.removeCallbacks(
            decisionScanRunnable
        )

        monitoredOfferId =
            null

        decisionDeadlineElapsedTime =
            0L

        decisionWaitingSinceElapsedTime =
            0L
    }

    private fun startLifecycleMonitor(
        offerId: String
    ) {
        invalidateWaitingAreaRecommendationRequest()

        if (
            lifecycleOfferId ==
            offerId
        ) {
            return
        }

        stopLifecycleMonitor()

        lifecycleOfferId =
            offerId

        lifecycleStage =
            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_RESTAURANT

        lifecycleWaitingSinceElapsedTime =
            0L

        savedLifecycleEvents.clear()
        clearProvisionalCustomerArrival()

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Started lifecycle monitor for $offerId."
        )

        scheduleNextLifecycleScan(
            LIFECYCLE_FIRST_SCAN_DELAY_MS
        )
    }

    private fun scheduleNextLifecycleScan(
        delayMs: Long
    ) {
        if (
            lifecycleOfferId == null
        ) {
            return
        }

        mainHandler.removeCallbacks(
            lifecycleScanRunnable
        )

        mainHandler.postDelayed(
            lifecycleScanRunnable,
            delayMs
        )
    }

    private fun runLifecycleMonitorStep() {
        val offerId =
            lifecycleOfferId
                ?: return

        if (
            isScanning
        ) {
            scheduleNextLifecycleScan(
                BUSY_RETRY_DELAY_MS
            )

            return
        }

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Requesting lifecycle scan for $offerId."
        )

        val scanStarted =
            requestScreenScan(
                detectionSource =
                    null,

                scanMode =
                    ScreenCaptureService
                        .SCAN_MODE_CLASSIFY_ONLY,

                scanPurpose =
                    SCAN_PURPOSE_LIFECYCLE
            )

        if (
            !scanStarted
        ) {
            scheduleNextLifecycleScan(
                BUSY_RETRY_DELAY_MS
            )
        }
    }

    private fun processLifecycleStage(
        offerId: String,
        detectedStage: String,
        scanContext: ScanDetectionContext?
    ) {
        lifecycleWaitingSinceElapsedTime =
            0L

        if (
            lifecycleOfferId !=
            offerId
        ) {
            return
        }

        val eventWallTime =
            scanContext?.wallTime
                ?: System.currentTimeMillis()

        val eventElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        val currentRank =
            lifecycleStageRank(
                lifecycleStage
            )

        val detectedRank =
            lifecycleStageRank(
                detectedStage
            )

        if (
            detectedRank == 0 ||
            detectedRank < currentRank
        ) {
            return
        }

        /*
         * Bottom-only phrases are deliberately interpreted
         * using the lifecycle order. A later-stage phrase
         * may advance only one stage at a time; it can never
         * skip restaurant arrival or pickup confirmation.
         */
        if (
            detectedRank >
            currentRank + 1
        ) {
            Log.w(
                LIFECYCLE_LOG_TAG,
                "Ignored out-of-order lifecycle stage " +
                        "$detectedStage while current stage is " +
                        "$lifecycleStage for $offerId."
            )

            return
        }

        when (
            detectedStage
        ) {
            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_RESTAURANT -> {

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_TO_RESTAURANT
            }

            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_RESTAURANT -> {

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .ARRIVED_AT_RESTAURANT,

                    wallTime =
                        eventWallTime,

                    elapsedTime =
                        eventElapsedTime,

                    confidence =
                        DataConfidence.HIGH
                )

                if (
                    restaurantLocationRecordedOffers.add(
                        offerId
                    )
                ) {
                    RestaurantLocationRecorder.record(
                        context =
                            applicationContext,

                        offerId =
                            offerId,

                        restaurantPlaceId =
                            restaurantPlaceId,

                        restaurantName =
                            restaurant,

                        observedAtWallTime =
                            eventWallTime
                    )
                }

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_AT_RESTAURANT
            }

            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_CUSTOMER -> {

                val pickupConfidence =
                    if (
                        lifecycleStage ==
                        ScreenCaptureService
                            .LIFECYCLE_STAGE_AT_RESTAURANT
                    ) {
                        DataConfidence.HIGH
                    } else {
                        DataConfidence.MEDIUM
                    }

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .PICKUP_CONFIRMED,

                    wallTime =
                        eventWallTime,

                    elapsedTime =
                        eventElapsedTime,

                    confidence =
                        pickupConfidence
                )

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_TO_CUSTOMER
            }

            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER -> {

                customerNavigationSinceElapsedTime =
                    0L

                if (
                    currentRank <
                    lifecycleStageRank(
                        ScreenCaptureService
                            .LIFECYCLE_STAGE_TO_CUSTOMER
                    )
                ) {
                    saveLifecycleEventOnce(
                        offerId =
                            offerId,

                        eventType =
                            OrderEventType
                                .PICKUP_CONFIRMED,

                        wallTime =
                            eventWallTime,

                        elapsedTime =
                            eventElapsedTime,

                        confidence =
                            DataConfidence.MEDIUM
                    )
                }

                if (
                    provisionalCustomerArrivalWallTime ==
                    null
                ) {
                    provisionalCustomerArrivalWallTime =
                        eventWallTime

                    provisionalCustomerArrivalElapsedTime =
                        eventElapsedTime

                    CustomerMapLearningManager
                        .onProvisionalCustomerArrival(
                            context =
                                applicationContext,
                            offerId =
                                offerId,
                            requestedAtWallTimeMs =
                                eventWallTime
                        )
                }

                lifecycleStage =
                    ScreenCaptureService
                        .LIFECYCLE_STAGE_AT_CUSTOMER
            }
        }

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Lifecycle stage for $offerId is now " +
                    lifecycleStage
        )
    }

    private fun lifecycleStageRank(
        stage: String
    ): Int {
        return when (
            stage
        ) {
            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_RESTAURANT ->
                1

            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_RESTAURANT ->
                2

            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_CUSTOMER ->
                3

            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER ->
                4

            else ->
                0
        }
    }

    private fun saveLifecycleEventOnce(
        offerId: String,
        eventType: String,
        wallTime: Long,
        elapsedTime: Long,
        confidence: String
    ) {
        if (
            !savedLifecycleEvents.add(
                eventType
            )
        ) {
            return
        }

        saveDecisionEvent(
            offerId =
                offerId,

            eventType =
                eventType,

            wallTime =
                wallTime,

            elapsedTime =
                elapsedTime,

            source =
                OrderEventSource
                    .SCREEN_STATE,

            confidence =
                confidence
        )
    }

    private fun confirmNavigationAfterCustomerArrival(
        offerId: String,
        observedElapsedTime: Long
    ): Boolean {
        if (
            lifecycleOfferId !=
            offerId ||
            lifecycleStage !=
            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER
        ) {
            customerNavigationSinceElapsedTime =
                0L

            return false
        }

        if (
            customerNavigationSinceElapsedTime ==
            0L
        ) {
            customerNavigationSinceElapsedTime =
                observedElapsedTime

            Log.d(
                LIFECYCLE_LOG_TAG,
                "Navigation appeared after AT_CUSTOMER for $offerId; " +
                        "waiting for confirmation before rollback."
            )

            return false
        }

        if (
            observedElapsedTime -
            customerNavigationSinceElapsedTime <
            CUSTOMER_NAVIGATION_CONFIRM_MS
        ) {
            return false
        }

        lifecycleStage =
            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_CUSTOMER

        customerNavigationSinceElapsedTime =
            0L

        CustomerMapLearningManager
            .rollbackProvisionalCustomerArrival(
                context =
                    applicationContext,
                offerId =
                    offerId
            )

        clearProvisionalCustomerArrival()

        Log.w(
            LIFECYCLE_LOG_TAG,
            "Persistent navigation confirmed after AT_CUSTOMER for " +
                    "$offerId; reverted to TO_CUSTOMER."
        )

        return true
    }

    private fun clearProvisionalCustomerArrival() {
        provisionalCustomerArrivalWallTime =
            null

        provisionalCustomerArrivalElapsedTime =
            null

        customerNavigationSinceElapsedTime =
            0L
    }

    private fun finalizeLifecycleFromWaiting(
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            lifecycleOfferId
                ?: return

        val eventWallTime =
            scanContext?.wallTime
                ?: System.currentTimeMillis()

        val eventElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        when (
            lifecycleStage
        ) {
            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER -> {

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .ARRIVED_AT_CUSTOMER,

                    wallTime =
                        provisionalCustomerArrivalWallTime
                            ?: eventWallTime,

                    elapsedTime =
                        provisionalCustomerArrivalElapsedTime
                            ?: eventElapsedTime,

                    confidence =
                        DataConfidence.HIGH
                )

                CustomerMapLearningManager
                    .confirmDelivery(
                        context =
                            applicationContext,
                        offerId =
                            offerId,
                        restaurantPlaceId =
                            restaurantPlaceId,
                        confirmedAtWallTimeMs =
                            eventWallTime,
                        confirmationSource =
                            "WAITING_SCREEN_CONFIRMED",
                        learnable =
                            true
                    )

                clearProvisionalCustomerArrival()

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .DELIVERY_COMPLETED,

                    wallTime =
                        eventWallTime,

                    elapsedTime =
                        eventElapsedTime,

                    confidence =
                        DataConfidence.HIGH
                )

                waitingAreaTracker.onDeliveryCompleted(
                    previousOfferId =
                        offerId,

                    completedAtWallTime =
                        eventWallTime
                )

                requestWaitingAreaRecommendation()
            }

            ScreenCaptureService
                .LIFECYCLE_STAGE_TO_CUSTOMER -> {

                clearProvisionalCustomerArrival()

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .DELIVERY_COMPLETED,

                    wallTime =
                        eventWallTime,

                    elapsedTime =
                        eventElapsedTime,

                    confidence =
                        DataConfidence.MEDIUM
                )

                waitingAreaTracker.onDeliveryCompleted(
                    previousOfferId =
                        offerId,

                    completedAtWallTime =
                        eventWallTime
                )

                requestWaitingAreaRecommendation()
            }

            else -> {
                clearProvisionalCustomerArrival()

                saveLifecycleEventOnce(
                    offerId =
                        offerId,

                    eventType =
                        OrderEventType
                            .ORDER_ENDED_INCOMPLETE,

                    wallTime =
                        eventWallTime,

                    elapsedTime =
                        eventElapsedTime,

                    confidence =
                        DataConfidence.MEDIUM
                )
            }
        }

        finishLifecycleMonitor(
            offerId
        )
    }

    private fun finalizeLifecycleForNewOffer(
        scanContext: ScanDetectionContext?
    ) {
        val offerId =
            lifecycleOfferId
                ?: return

        val eventWallTime =
            scanContext?.wallTime
                ?: System.currentTimeMillis()

        val eventElapsedTime =
            scanContext?.elapsedTime
                ?: SystemClock.elapsedRealtime()

        val reachedCustomerLeg =
            lifecycleStageRank(
                lifecycleStage
            ) >=
                    lifecycleStageRank(
                        ScreenCaptureService
                            .LIFECYCLE_STAGE_TO_CUSTOMER
                    )

        if (
            lifecycleStage ==
            ScreenCaptureService
                .LIFECYCLE_STAGE_AT_CUSTOMER
        ) {
            saveLifecycleEventOnce(
                offerId =
                    offerId,

                eventType =
                    OrderEventType
                        .ARRIVED_AT_CUSTOMER,

                wallTime =
                    provisionalCustomerArrivalWallTime
                        ?: eventWallTime,

                elapsedTime =
                    provisionalCustomerArrivalElapsedTime
                        ?: eventElapsedTime,

                confidence =
                    DataConfidence.HIGH
            )

            CustomerMapLearningManager
                .confirmDelivery(
                    context =
                        applicationContext,
                    offerId =
                        offerId,
                    restaurantPlaceId =
                        restaurantPlaceId,
                    confirmedAtWallTimeMs =
                        eventWallTime,
                    confirmationSource =
                        "NEW_OFFER_FALLBACK",
                    learnable =
                        false
                )
        }

        clearProvisionalCustomerArrival()

        saveLifecycleEventOnce(
            offerId =
                offerId,

            eventType =
                if (
                    reachedCustomerLeg
                ) {
                    OrderEventType
                        .DELIVERY_COMPLETED
                } else {
                    OrderEventType
                        .ORDER_ENDED_INCOMPLETE
                },

            wallTime =
                eventWallTime,

            elapsedTime =
                eventElapsedTime,

            confidence =
                DataConfidence.MEDIUM
        )

        finishLifecycleMonitor(
            offerId =
                offerId,

            resetOverlayToIdle =
                false
        )
    }

    private fun finishLifecycleMonitor(
        offerId: String,
        resetOverlayToIdle: Boolean =
            true
    ) {
        CustomerAddressTracker.clear(
            offerId
        )

        customerAddressResult =
            null

        stopLifecycleMonitor()

        if (
            currentOfferId ==
            offerId
        ) {
            currentOfferId =
                null
        }

        if (
            resetOverlayToIdle
        ) {
            resetToIdleAfterOrder(
                reason =
                    "Lifecycle ended for $offerId"
            )
        } else {
            /*
             * A new offer is already waiting to be applied.
             * Remove every value belonging to the old order
             * without clearing the new ScreenCaptureService
             * result or briefly drawing an idle popup.
             */
            clearCachedOrderValues(
                screenState =
                    ScreenCaptureService
                        .SCREEN_STATE_UNKNOWN
            )

            isExpanded =
                false
        }

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Stopped lifecycle monitor for $offerId; " +
                    "idleReset=$resetOverlayToIdle."
        )
    }

    private fun clearCachedOrderValues(
        screenState: String
    ) {
        currentScreenState =
            screenState

        restaurant =
            getString(
                R.string.restaurant_not_found
            )

        restaurantPlaceId =
            null

        pay =
            getString(
                R.string.pay_not_found
            )

        miles =
            getString(
                R.string.mileage_not_found
            )

        timeToRestaurantMinutes =
            null

        distanceToRestaurantMiles =
            null

        routeCapturedAtWallTime =
            null

        routeSource =
            RouteSource.NOT_AVAILABLE

        routeStatus =
            RouteStatus.NOT_AVAILABLE

        currentScore =
            null

        currentRatingResult =
            null

        currentRestaurantWaitMinutes =
            null

        invalidatePredictedPostDeliveryEstimate()

        effectiveHourlyRateForScore =
            null

        currentDemandEstimate =
            null

        demandScoreAdjustment =
            0.0

        demandSummary =
            null

        customerAddressResult =
            null

        gasPriceUsed =
            null

        vehicleMpgUsed =
            null

        estimatedHourlyRate =
            "—"

        estimatedCompletionTime =
            "—"
    }

    private fun resetToIdleAfterOrder(
        reason: String
    ) {
        /*
         * Cancel any pending callback that could redraw
         * data from the order that just ended.
         */
        mainHandler.removeCallbacks(
            restoreOverlayRunnable
        )

        stopDecisionMonitor()

        isScanning =
            false

        pendingScanContext =
            null

        /*
         * Cancel any unfinished popup-expansion request
         * belonging to the order that just ended.
         */


        currentOfferId =
            null

        clearCachedOrderValues(
            screenState =
                ScreenCaptureService
                    .SCREEN_STATE_WAITING
        )

        scanStatus =
            "Waiting for offers"

        /*
         * Prevent a service restart or a partial future
         * scan from reusing the previous order's values.
         * KEY_READER_ACTIVE is intentionally preserved.
         */
        getSharedPreferences(
            ScreenCaptureService.PREFS_NAME,
            MODE_PRIVATE
        )
            .edit()
            .remove(
                ScreenCaptureService.KEY_RESTAURANT
            )
            .remove(
                ScreenCaptureService
                    .KEY_RESTAURANT_PLACE_ID
            )
            .remove(
                ScreenCaptureService.KEY_PAY
            )
            .remove(
                ScreenCaptureService.KEY_MILES
            )
            .remove(
                ScreenCaptureService
                    .KEY_HAS_GOOGLE_ROUTE
            )
            .remove(
                ScreenCaptureService
                    .KEY_TIME_TO_RESTAURANT
            )
            .remove(
                ScreenCaptureService
                    .KEY_DISTANCE_TO_RESTAURANT
            )
            .remove(
                ScreenCaptureService
                    .KEY_ROUTE_CAPTURED_AT_WALL_TIME
            )
            .remove(
                ScreenCaptureService
                    .KEY_ROUTE_SOURCE
            )
            .remove(
                ScreenCaptureService
                    .KEY_ROUTE_STATUS
            )
            .apply()

        isExpanded =
            false

        overlayView?.apply {
            visibility =
                View.VISIBLE

            alpha =
                1.0f
        }

        updateOverlayAppearance()

        Log.d(
            LIFECYCLE_LOG_TAG,
            "Idle reset completed: $reason."
        )
    }

    private fun stopLifecycleMonitor() {
        mainHandler.removeCallbacks(
            lifecycleScanRunnable
        )

        lifecycleOfferId =
            null

        lifecycleStage =
            ScreenCaptureService
                .LIFECYCLE_STAGE_NONE

        lifecycleWaitingSinceElapsedTime =
            0L

        savedLifecycleEvents.clear()
        clearProvisionalCustomerArrival()
    }

    private fun saveDecisionEvent(
        offerId: String,
        eventType: String,
        wallTime: Long,
        elapsedTime: Long,
        source: String,
        confidence: String
    ) {
        val shouldExportEndedOrder =
            eventType ==
                    OrderEventType
                        .DELIVERY_COMPLETED ||
                    eventType ==
                    OrderEventType
                        .ORDER_ENDED_INCOMPLETE

        /*
         * Capture route values before finishLifecycleMonitor()
         * clears all mutable order state. The export coroutine
         * must use this immutable copy.
         */
        val routeSnapshotForExport =
            if (
                shouldExportEndedOrder
            ) {
                RouteSnapshotEntity(
                    offerId =
                        offerId,

                    routeLeg =
                        RouteLeg
                            .CURRENT_TO_RESTAURANT,

                    capturedAtWallTime =
                        routeCapturedAtWallTime
                            ?: wallTime,

                    etaMinutes =
                        timeToRestaurantMinutes,

                    distanceMiles =
                        distanceToRestaurantMiles,

                    routeSource =
                        routeSource,

                    routeStatus =
                        routeStatus
                )
            } else {
                null
            }

        serviceScope.launch {
            eventSaveMutex.withLock {
                try {
                    repository.saveOrderEvent(
                        OrderEventEntity(
                            offerId =
                                offerId,

                            eventType =
                                eventType,

                            wallTime =
                                wallTime,

                            elapsedTime =
                                elapsedTime,

                            source =
                                source,

                            confidence =
                                confidence
                        )
                    )

                    Log.d(
                        DATA_LOG_TAG,
                        "Saved order event $eventType " +
                                "for $offerId."
                    )

                    /*
                     * Export both normal and incomplete
                     * lifecycle endings. The exporter reads
                     * all events from Room, lists missing
                     * stages, and excludes incomplete data
                     * from training.
                     */
                    if (
                        shouldExportEndedOrder
                    ) {
                        CompletedOrderExporter
                            .exportCompletedOrderWithRetry(
                                context =
                                    this@OverlayService,

                                repository =
                                    repository,

                                offerId =
                                    offerId,

                                routeSnapshot =
                                    routeSnapshotForExport
                            )
                    }
                } catch (
                    exception: Exception
                ) {
                    Log.e(
                        DATA_LOG_TAG,
                        "Could not save/export order event " +
                                "$eventType for $offerId.",
                        exception
                    )
                }
            }
        }
    }

    private fun loadLatestScan() {
        val preferences =
            getSharedPreferences(
                ScreenCaptureService.PREFS_NAME,
                MODE_PRIVATE
            )

        restaurant =
            preferences.getString(
                ScreenCaptureService.KEY_RESTAURANT,
                restaurant
            )
                ?: restaurant

        restaurantPlaceId =
            preferences.getString(
                ScreenCaptureService
                    .KEY_RESTAURANT_PLACE_ID,
                null
            )

        pay =
            preferences.getString(
                ScreenCaptureService.KEY_PAY,
                pay
            )
                ?: pay

        miles =
            preferences.getString(
                ScreenCaptureService.KEY_MILES,
                miles
            )
                ?: miles

        val hasGoogleRoute =
            preferences.getBoolean(
                ScreenCaptureService
                    .KEY_HAS_GOOGLE_ROUTE,
                false
            )

        timeToRestaurantMinutes =
            if (
                hasGoogleRoute
            ) {
                preferences.getFloat(
                    ScreenCaptureService
                        .KEY_TIME_TO_RESTAURANT,
                    0.0f
                ).toDouble()
            } else {
                null
            }

        distanceToRestaurantMiles =
            if (
                hasGoogleRoute
            ) {
                preferences.getFloat(
                    ScreenCaptureService
                        .KEY_DISTANCE_TO_RESTAURANT,
                    0.0f
                ).toDouble()
            } else {
                null
            }

        routeCapturedAtWallTime =
            if (
                preferences.contains(
                    ScreenCaptureService
                        .KEY_ROUTE_CAPTURED_AT_WALL_TIME
                )
            ) {
                preferences.getLong(
                    ScreenCaptureService
                        .KEY_ROUTE_CAPTURED_AT_WALL_TIME,
                    0L
                )
            } else {
                null
            }

        routeSource =
            preferences.getString(
                ScreenCaptureService
                    .KEY_ROUTE_SOURCE,
                RouteSource.NOT_AVAILABLE
            )
                ?: RouteSource.NOT_AVAILABLE

        routeStatus =
            preferences.getString(
                ScreenCaptureService
                    .KEY_ROUTE_STATUS,
                RouteStatus.NOT_AVAILABLE
            )
                ?: RouteStatus.NOT_AVAILABLE

        currentScreenState =
            ScreenCaptureService
                .SCREEN_STATE_UNKNOWN

        currentScore =
            null

        currentRatingResult =
            null

        currentRestaurantWaitMinutes =
            null

        currentDemandEstimate =
            null

        demandScoreAdjustment =
            0.0

        demandSummary =
            null

        gasPriceUsed =
            null

        vehicleMpgUsed =
            null

        estimatedHourlyRate =
            "—"

        estimatedCompletionTime =
            "—"
    }

    private fun applyOrderResult(
        intent: Intent,
        screenState: String
    ) {
        restaurant =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_RESTAURANT
            )
                ?: restaurant

        restaurantPlaceId =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_RESTAURANT_PLACE_ID
            )

        pay =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_PAY
            )
                ?: pay

        miles =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_MILES
            )
                ?: miles

        timeToRestaurantMinutes =
            if (
                intent.hasExtra(
                    ScreenCaptureService
                        .EXTRA_TIME_TO_RESTAURANT
                )
            ) {
                intent.getDoubleExtra(
                    ScreenCaptureService
                        .EXTRA_TIME_TO_RESTAURANT,
                    0.0
                )
            } else {
                null
            }

        distanceToRestaurantMiles =
            if (
                intent.hasExtra(
                    ScreenCaptureService
                        .EXTRA_DISTANCE_TO_RESTAURANT
                )
            ) {
                intent.getDoubleExtra(
                    ScreenCaptureService
                        .EXTRA_DISTANCE_TO_RESTAURANT,
                    0.0
                )
            } else {
                null
            }

        routeCapturedAtWallTime =
            if (
                intent.hasExtra(
                    ScreenCaptureService
                        .EXTRA_ROUTE_CAPTURED_AT_WALL_TIME
                )
            ) {
                intent.getLongExtra(
                    ScreenCaptureService
                        .EXTRA_ROUTE_CAPTURED_AT_WALL_TIME,
                    0L
                )
            } else {
                null
            }

        routeSource =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_ROUTE_SOURCE
            )
                ?: RouteSource.NOT_AVAILABLE

        routeStatus =
            intent.getStringExtra(
                ScreenCaptureService
                    .EXTRA_ROUTE_STATUS
            )
                ?: RouteStatus.NOT_AVAILABLE

        currentScreenState =
            screenState

        updateRating()
    }

    private fun showWaitingState() {
        clearDisplayedOrder(
            screenState =
                ScreenCaptureService
                    .SCREEN_STATE_WAITING
        )
    }

    private fun clearDisplayedOrder(
        screenState: String
    ) {
        currentScreenState =
            screenState

        currentScore =
            null

        currentRatingResult =
            null

        currentRestaurantWaitMinutes =
            null

        invalidatePredictedPostDeliveryEstimate()

        effectiveHourlyRateForScore =
            null

        currentDemandEstimate =
            null

        demandScoreAdjustment =
            0.0

        demandSummary =
            null

        gasPriceUsed =
            null

        vehicleMpgUsed =
            null

        estimatedHourlyRate =
            "—"

        estimatedCompletionTime =
            "—"
    }

    private fun startOverlayForegroundService() {
        val notification =
            Notification.Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable
                        .ic_dialog_info
                )
                .setContentTitle(
                    getString(
                        R.string
                            .overlay_notification_title
                    )
                )
                .setContentText(
                    getString(
                        R.string
                            .overlay_notification_text
                    )
                )
                .setCategory(
                    Notification.CATEGORY_SERVICE
                )
                .setOngoing(
                    true
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun openRecommendedCenterInMaps(): Boolean {
        val center =
            recommendedWaitingCenter
                ?: return false

        val mapsUrl =
            "https://www.google.com/maps/search/?api=1" +
                    "&query=${center.latitude},${center.longitude}" +
                    "&query_place_id=${Uri.encode(center.centerId)}"

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(mapsUrl)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return runCatching {
            startActivity(intent)
            true
        }.getOrElse { exception ->
            Log.w(
                DATA_LOG_TAG,
                "Could not open recommended center in Maps.",
                exception
            )
            false
        }
    }

    private fun hasActiveOrderOrOffer(): Boolean {
        return (
                lifecycleOfferId != null ||
                        monitoredOfferId != null ||
                        currentScreenState ==
                        ScreenCaptureService.SCREEN_STATE_OFFER ||
                        currentScreenState ==
                        ScreenCaptureService.SCREEN_STATE_ACTIVE_DELIVERY
                )
    }

    private fun openRestaurantInMaps(): Boolean {
        val cleanedRestaurantName =
            restaurant.trim()

        val cleanedPlaceId =
            restaurantPlaceId
                ?.trim()
                ?.takeIf { value ->
                    value.isNotEmpty()
                }

        if (
            cleanedRestaurantName.isEmpty() &&
            cleanedPlaceId == null
        ) {
            return false
        }

        val query =
            cleanedRestaurantName
                .takeIf { value ->
                    value.isNotEmpty()
                }
                ?: cleanedPlaceId
                ?: return false

        val mapsUrl =
            buildString {
                append(
                    "https://www.google.com/maps/search/?api=1"
                )
                append(
                    "&query="
                )
                append(
                    Uri.encode(query)
                )

                if (
                    cleanedPlaceId != null
                ) {
                    append(
                        "&query_place_id="
                    )
                    append(
                        Uri.encode(cleanedPlaceId)
                    )
                }
            }

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(mapsUrl)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return runCatching {
            startActivity(intent)
            true
        }.getOrElse { exception ->
            Log.w(
                DATA_LOG_TAG,
                "Could not open restaurant in Maps.",
                exception
            )
            false
        }
    }

    private fun openCustomerInMaps(): Boolean {
        val address =
            customerAddressResult
                ?.addressText
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return false

        /*
         * Use the actual DoorDash address string as the destination. Google Maps
         * resolves the address itself, so navigation still works even if Android's
         * diagnostic Geocoder did not return coordinates.
         */
        val mapsUrl =
            buildString {
                append(
                    "https://www.google.com/maps/dir/?api=1"
                )

                append(
                    "&destination="
                )

                append(
                    Uri.encode(
                        address
                    )
                )

                append(
                    "&travelmode=driving"
                )
            }

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    mapsUrl
                )
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        return runCatching {
            startActivity(
                intent
            )

            true
        }.getOrElse {
                exception ->

            Log.w(
                DATA_LOG_TAG,
                "Could not open customer address in Maps.",
                exception
            )

            false
        }
    }

    private fun openLongPressDestinationInMaps(): Boolean {
        return if (
            hasActiveOrderOrOffer()
        ) {
            /*
             * Once DoorDash exposes the customer address, long press becomes a
             * direct customer-navigation shortcut. Before that point it keeps
             * the existing restaurant-navigation behavior.
             */
            if (
                customerAddressResult != null
            ) {
                openCustomerInMaps()
            } else {
                openRestaurantInMaps()
            }
        } else {
            openRecommendedCenterInMaps()
        }
    }

    private fun createOverlay() {
        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        layoutParameters =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .WRAP_CONTENT,

                WindowManager.LayoutParams
                    .WRAP_CONTENT,

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,

                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,

                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or
                            Gravity.START

                x =
                    dp(
                        16
                    )

                y =
                    dp(
                        100
                    )
            }

        val touchSlop =
            ViewConfiguration
                .get(
                    this
                )
                .scaledTouchSlop

        var startingWindowX =
            0

        var startingWindowY =
            0

        var startingTouchX =
            0f

        var startingTouchY =
            0f

        var wasDragged =
            false

        var longPressTriggered =
            false

        var pendingLongPress:
                Runnable? = null

        overlayView =
            DashToolOverlayView(
                this
            ).apply {

                setOnLongClickListener {
                    openLongPressDestinationInMaps()
                }

                setOnClickListener {
                    if (
                        isExpanded
                    ) {
                        /*
                         * Expanded tap keeps the familiar collapse action.
                         */
                        expandAfterManualScan =
                            false

                        manualExpandScanStarted =
                            false

                        isExpanded =
                            false

                        updateOverlayAppearance()

                        return@setOnClickListener
                    }

                    /*
                     * Collapsed tap requests a fresh full offer scan. Keep
                     * the popup collapsed while the scan runs so stale data
                     * never flashes. When that scan finishes, the receiver
                     * expands the popup even if no offer was readable.
                     */
                    expandAfterManualScan =
                        true

                    if (
                        isScanning
                    ) {
                        /*
                         * A lifecycle / decision scan is already in flight.
                         * The receiver will start this manual full scan next.
                         */
                        return@setOnClickListener
                    }

                    manualExpandScanStarted =
                        requestScreenScan(
                            detectionSource =
                                OfferDetectionSource
                                    .MANUAL_SCAN,

                            scanMode =
                                ScreenCaptureService
                                    .SCAN_MODE_FULL_OFFER
                        )

                    if (
                        !manualExpandScanStarted
                    ) {
                        /*
                         * If a scan cannot start (for example the reader was
                         * just disabled), still honor the tap by opening the
                         * current idle / recommendation state.
                         */
                        expandAfterManualScan =
                            false

                        isExpanded =
                            true

                        updateOverlayAppearance()
                    }
                }

                setOnTouchListener {
                        view: View,
                        event: MotionEvent ->

                    when (
                        event.actionMasked
                    ) {
                        MotionEvent.ACTION_DOWN -> {
                            startingWindowX =
                                layoutParameters.x

                            startingWindowY =
                                layoutParameters.y

                            startingTouchX =
                                event.rawX

                            startingTouchY =
                                event.rawY

                            wasDragged =
                                false

                            longPressTriggered =
                                false

                            pendingLongPress?.let { runnable ->
                                mainHandler.removeCallbacks(
                                    runnable
                                )
                            }

                            val longPressRunnable =
                                Runnable {
                                    if (
                                        !wasDragged &&
                                        !longPressTriggered
                                    ) {
                                        longPressTriggered =
                                            view.performLongClick()
                                    }
                                }

                            pendingLongPress =
                                longPressRunnable

                            mainHandler.postDelayed(
                                longPressRunnable,
                                ViewConfiguration
                                    .getLongPressTimeout()
                                    .toLong()
                            )

                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val changeX =
                                (
                                        event.rawX -
                                                startingTouchX
                                        )
                                    .toInt()

                            val changeY =
                                (
                                        event.rawY -
                                                startingTouchY
                                        )
                                    .toInt()

                            if (
                                abs(
                                    changeX
                                ) >
                                touchSlop ||
                                abs(
                                    changeY
                                ) >
                                touchSlop
                            ) {
                                if (
                                    !wasDragged
                                ) {
                                    pendingLongPress?.let { runnable ->
                                        mainHandler.removeCallbacks(
                                            runnable
                                        )
                                    }
                                }

                                wasDragged =
                                    true
                            }

                            if (
                                wasDragged
                            ) {
                                moveOverlay(
                                    view =
                                        view,

                                    changeX =
                                        changeX,

                                    changeY =
                                        changeY,

                                    startingWindowX =
                                        startingWindowX,

                                    startingWindowY =
                                        startingWindowY
                                )
                            }

                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            pendingLongPress?.let { runnable ->
                                mainHandler.removeCallbacks(
                                    runnable
                                )
                            }

                            pendingLongPress =
                                null

                            if (
                                !wasDragged &&
                                !longPressTriggered
                            ) {
                                view.performClick()
                            }

                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            pendingLongPress?.let { runnable ->
                                mainHandler.removeCallbacks(
                                    runnable
                                )
                            }

                            pendingLongPress =
                                null

                            true
                        }

                        else ->
                            false
                    }
                }
            }

        updateOverlayAppearance()

        windowManager.addView(
            overlayView,
            layoutParameters
        )

        overlayView?.post {
            overlayView?.let {
                    view ->

                constrainOverlayToTopHalf(
                    view
                )
            }
        }
    }

    private fun requestScreenScan(
        detectionSource: String?,
        scanMode: String,
        scanPurpose: String =
            SCAN_PURPOSE_FULL_OFFER
    ): Boolean {
        val preferences =
            getSharedPreferences(
                ScreenCaptureService.PREFS_NAME,
                MODE_PRIVATE
            )

        val readerActive =
            preferences.getBoolean(
                ScreenCaptureService
                    .KEY_READER_ACTIVE,
                false
            )

        Log.d(
            "DashToolScan",
            "Scan requested: readerActive=$readerActive, " +
                    "isScanning=$isScanning, " +
                    "purpose=$scanPurpose, " +
                    "mode=$scanMode"
        )

        if (
            !readerActive
        ) {
            Log.e(
                "DashToolScan",
                "Scan rejected because screen reader is inactive."
            )
            isScanning =
                false

            pendingScanContext =
                null

            updateOverlayAppearance()

            return false
        }

        if (
            isScanning
        ) {
            Log.e(
                "DashToolScan",
                "Scan rejected because OverlayService still " +
                        "thinks a scan is active. Pending purpose=" +
                        pendingScanContext?.scanPurpose +
                        ", pending mode=" +
                        pendingScanContext?.scanMode
            )

            return false
        }

        pendingScanContext =
            ScanDetectionContext(
                detectionSource =
                    detectionSource,

                scanMode =
                    scanMode,

                wallTime =
                    System.currentTimeMillis(),

                elapsedTime =
                    SystemClock.elapsedRealtime(),

                scanPurpose =
                    scanPurpose
            )

        isScanning =
            true

        mainHandler.removeCallbacks(
            restoreOverlayRunnable
        )

        if (
            scanMode ==
            ScreenCaptureService
                .SCAN_MODE_CLASSIFY_ONLY
        ) {
            /*
             * Keep the overlay visible and unchanged.
             * First notify ScreenCaptureService that a
             * frame is wanted. Then make a nearly
             * invisible alpha change to produce a fresh
             * screen frame.
             */
            overlayView?.apply {
                visibility =
                    View.VISIBLE

                alpha =
                    1.0f
            }

            sendScanBroadcast(
                scanMode =
                    scanMode
            )

            mainHandler.postDelayed(
                {
                    forceClassificationFrame()
                },
                CLASSIFICATION_FRAME_DELAY_MS
            )
        } else {
            /*
             * Full offer scans continue hiding the
             * overlay so its text cannot interfere with
             * restaurant parsing.
             */
            updateOverlayAppearance()

            overlayView?.visibility =
                View.INVISIBLE

            mainHandler.postDelayed(
                {
                    sendScanBroadcast(
                        scanMode =
                            scanMode
                    )
                },
                200L
            )
        }

        mainHandler.postDelayed(
            restoreOverlayRunnable,
            OVERLAY_SCAN_TIMEOUT_MS
        )

        return true
    }

    private fun sendScanBroadcast(
        scanMode: String
    ) {
        val scanIntent =
            Intent(
                ScreenCaptureService
                    .ACTION_SCAN_NOW
            ).apply {
                setPackage(
                    packageName
                )

                putExtra(
                    ScreenCaptureService
                        .EXTRA_SCAN_MODE,
                    scanMode
                )
            }

        sendBroadcast(
            scanIntent
        )
    }

    private fun forceClassificationFrame() {
        val scanContext =
            pendingScanContext
                ?: return

        if (
            !isScanning ||
            scanContext.scanMode !=
            ScreenCaptureService
                .SCAN_MODE_CLASSIFY_ONLY
        ) {
            return
        }

        val view =
            overlayView
                ?: return

        view.alpha =
            CLASSIFICATION_PULSE_ALPHA

        view.invalidate()

        mainHandler.postDelayed(
            {
                if (
                    overlayView === view
                ) {
                    view.alpha =
                        1.0f

                    view.invalidate()
                }
            },
            CLASSIFICATION_PULSE_DURATION_MS
        )
    }

    private fun saveCurrentOffer(
        scanDetectionContext:
        ScanDetectionContext?
    ) {
        val payValue =
            extractNumber(
                pay
            )
                ?: return

        val milesValue =
            extractNumber(
                miles
            )
                ?: return

        val ratingResult =
            currentRatingResult
                ?: return

        val savedGasPrice =
            gasPriceUsed
                ?: return

        val savedVehicleMpg =
            vehicleMpgUsed
                ?: return

        val detectionContext =
            scanDetectionContext
                ?: ScanDetectionContext(
                    detectionSource =
                        OfferDetectionSource
                            .MANUAL_SCAN,

                    scanMode =
                        ScreenCaptureService
                            .SCAN_MODE_FULL_OFFER,

                    wallTime =
                        System.currentTimeMillis(),

                    elapsedTime =
                        SystemClock.elapsedRealtime()
                )

        val newOfferId =
            "offer_" +
                    UUID.randomUUID()
                        .toString()

        val savedRestaurant =
            restaurant

        val savedRestaurantPlaceId =
            restaurantPlaceId

        val savedTimeToRestaurant =
            timeToRestaurantMinutes

        val savedDistanceToRestaurant =
            distanceToRestaurantMiles

        val savedRouteCapturedAt =
            routeCapturedAtWallTime

        val savedRouteSource =
            routeSource

        val savedRouteStatus =
            routeStatus

        val savedScore =
            currentScore
                ?: return

        val savedRestaurantWaitMinutes =
            currentRestaurantWaitMinutes

        val savedDemandEstimate =
            currentDemandEstimate

        val savedDemandScoreAdjustment =
            demandScoreAdjustment

        val savedWaitingAreaRecommendation =
            lastWaitingAreaRecommendation

        val savedWaitingAreaRecommendationWallTime =
            lastWaitingAreaRecommendationWallTime

        val matchConfidence =
            when {
                savedRestaurantPlaceId != null &&
                        savedRouteStatus ==
                        RouteStatus.SUCCESS -> {

                    RestaurantMatchConfidence.HIGH
                }

                savedRestaurantPlaceId != null -> {

                    RestaurantMatchConfidence.MEDIUM
                }

                else -> {

                    RestaurantMatchConfidence.UNKNOWN
                }
            }

        serviceScope.launch {
            try {
                val sessionId =
                    repository
                        .getCachedActiveSessionId()
                        ?: repository
                            .recoverActiveSessionId()

                if (
                    sessionId == null
                ) {
                    Log.w(
                        DATA_LOG_TAG,
                        "Offer was not saved because no active session exists."
                    )

                    return@launch
                }

                val offer =
                    OfferEntity(
                        offerId =
                            newOfferId,

                        sessionId =
                            sessionId,

                        detectedAtWallTime =
                            detectionContext.wallTime,

                        detectedAtElapsedTime =
                            detectionContext.elapsedTime,

                        detectionSource =
                            detectionContext.detectionSource
                                ?: OfferDetectionSource
                                    .MANUAL_SCAN,

                        restaurantName =
                            savedRestaurant,

                        restaurantPlaceId =
                            savedRestaurantPlaceId,

                        restaurantMatchConfidence =
                            matchConfidence,

                        offeredPayout =
                            payValue,

                        displayedTotalMiles =
                            milesValue,

                        appVersion =
                            BuildConfig.VERSION_NAME,

                        parserVersion =
                            DashToolVersions
                                .PARSER_VERSION,

                        engineVersion =
                            ratingResult.engineVersion,

                        gasPriceUsed =
                            savedGasPrice,

                        vehicleMpgUsed =
                            savedVehicleMpg,

                        scoreShown =
                            savedScore
                    )

                val routeSnapshot =
                    RouteSnapshotEntity(
                        offerId =
                            newOfferId,

                        routeLeg =
                            RouteLeg
                                .CURRENT_TO_RESTAURANT,

                        capturedAtWallTime =
                            savedRouteCapturedAt
                                ?: detectionContext
                                    .wallTime,

                        etaMinutes =
                            savedTimeToRestaurant,

                        distanceMiles =
                            savedDistanceToRestaurant,

                        routeSource =
                            savedRouteSource,

                        routeStatus =
                            savedRouteStatus
                    )

                val savedOfferId =
                    repository
                        .saveOfferWithRouteIfNew(
                            offer =
                                offer,

                            routeSnapshot =
                                routeSnapshot
                        )

                OrderDecisionTelemetryStore
                    .recordIfAbsent(
                        context =
                            applicationContext,

                        offerId =
                            savedOfferId,

                        detectedAtWallTimeMs =
                            detectionContext.wallTime,

                        detectionSource =
                            detectionContext.detectionSource,

                        restaurantName =
                            savedRestaurant,

                        restaurantPlaceId =
                            savedRestaurantPlaceId,

                        payout =
                            payValue,

                        displayedMiles =
                            milesValue,

                        gasPricePerGallon =
                            savedGasPrice,

                        vehicleMpg =
                            savedVehicleMpg,

                        ratingResult =
                            ratingResult,

                        restaurantWaitMinutes =
                            savedRestaurantWaitMinutes,

                        finalScoreShown =
                            savedScore,

                        demandScoreAdjustment =
                            savedDemandScoreAdjustment,

                        demandEstimate =
                            savedDemandEstimate,

                        routeCapturedAtWallTimeMs =
                            savedRouteCapturedAt,

                        timeToRestaurantMinutes =
                            savedTimeToRestaurant,

                        distanceToRestaurantMiles =
                            savedDistanceToRestaurant,

                        routeSource =
                            savedRouteSource,

                        routeStatus =
                            savedRouteStatus,

                        waitingRecommendation =
                            savedWaitingAreaRecommendation,

                        waitingRecommendationCapturedAtWallTimeMs =
                            savedWaitingAreaRecommendationWallTime
                    )

                /*
                 * Keep the last waiting-area recommendation cached while an
                 * offer is being decided. If the offer is rejected, reopening
                 * the idle popup can immediately reuse that recommendation
                 * without bypassing the refresh cooldown. An accepted order
                 * will force a fresh recommendation after completion.
                 */
                waitingAreaTracker.onOfferDetected(
                    nextOfferId =
                        savedOfferId,

                    detectedAtWallTime =
                        detectionContext.wallTime
                )

                DoorDashDemandTracker.onOfferDetected(
                    context =
                        applicationContext,

                    offerId =
                        savedOfferId,

                    detectedAtWallTimeMs =
                        detectionContext.wallTime,

                    detectionSource =
                        detectionContext.detectionSource
                )

                CustomerMapLearningManager
                    .onOfferSaved(
                        context =
                            applicationContext,
                        offerId =
                            savedOfferId,
                        restaurantPlaceId =
                            savedRestaurantPlaceId,
                        detectedAtWallTimeMs =
                            detectionContext.wallTime
                    )

                val savedOffer =
                    repository.getOffer(
                        savedOfferId
                    )

                val existingEvents =
                    repository.getEventsForOffer(
                        savedOfferId
                    )

                val acceptedAlready =
                    existingEvents.any { event ->
                        event.eventType ==
                                OrderEventType
                                    .OFFER_ACCEPTED
                    }

                val notAcceptedAlready =
                    existingEvents.any { event ->
                        event.eventType ==
                                OrderEventType
                                    .OFFER_NOT_ACCEPTED
                    }

                val decisionAlreadyResolved =
                    acceptedAlready ||
                            notAcceptedAlready

                mainHandler.post {
                    val previousLifecycleOfferId =
                        lifecycleOfferId

                    if (
                        previousLifecycleOfferId != null &&
                        previousLifecycleOfferId !=
                        savedOfferId
                    ) {
                        finalizeLifecycleForNewOffer(
                            scanContext =
                                detectionContext
                        )
                    }

                    currentOfferId =
                        savedOfferId

                    beginPredictedPostDeliveryLookup(
                        offerId =
                            savedOfferId
                    )

                    when {
                        !decisionAlreadyResolved -> {
                            startDecisionMonitor(
                                offerId =
                                    savedOfferId,

                                originalDetectedAtElapsedTime =
                                    savedOffer
                                        ?.detectedAtElapsedTime
                                        ?: detectionContext
                                            .elapsedTime
                            )
                        }

                        acceptedAlready -> {
                            startLifecycleMonitor(
                                savedOfferId
                            )
                        }
                    }
                }

                Log.d(
                    DATA_LOG_TAG,
                    "Saved offer $savedOfferId: " +
                            "$savedRestaurant, " +
                            "$payValue, " +
                            "$milesValue miles, " +
                            "score $savedScore."
                )
            } catch (
                exception: Exception
            ) {
                Log.e(
                    DATA_LOG_TAG,
                    "Could not save offer.",
                    exception
                )
            }
        }
    }

    private fun getTopHalfOverlayMaxHeight(): Int {
        val screenHeight =
            windowManager
                .currentWindowMetrics
                .bounds
                .height()

        return (
                screenHeight *
                        OVERLAY_REGION_FRACTION
                )
            .toInt()
            .minus(
                dp(
                    OVERLAY_EDGE_MARGIN_DP
                )
            )
            .coerceAtLeast(
                dp(
                    48
                )
            )
    }

    private fun constrainOverlayToTopHalf(
        view: View
    ) {
        if (
            !::windowManager.isInitialized ||
            !::layoutParameters.isInitialized
        ) {
            return
        }

        val screenBounds =
            windowManager
                .currentWindowMetrics
                .bounds

        val maximumX =
            (
                    screenBounds.width() -
                            view.width
                    )
                .coerceAtLeast(
                    0
                )

        val topHalfBottom =
            (
                    screenBounds.height() *
                            OVERLAY_REGION_FRACTION
                    )
                .toInt()

        val maximumY =
            (
                    topHalfBottom -
                            dp(
                                OVERLAY_EDGE_MARGIN_DP
                            ) -
                            view.height
                    )
                .coerceAtLeast(
                    0
                )

        layoutParameters.x =
            layoutParameters.x.coerceIn(
                0,
                maximumX
            )

        layoutParameters.y =
            layoutParameters.y.coerceIn(
                0,
                maximumY
            )

        runCatching {
            windowManager.updateViewLayout(
                view,
                layoutParameters
            )
        }
    }

    private fun moveOverlay(
        view: View,
        changeX: Int,
        changeY: Int,
        startingWindowX: Int,
        startingWindowY: Int
    ) {
        val screenBounds =
            windowManager
                .currentWindowMetrics
                .bounds

        val maximumX =
            (
                    screenBounds.width() -
                            view.width
                    )
                .coerceAtLeast(
                    0
                )

        val topHalfBottom =
            (
                    screenBounds.height() *
                            OVERLAY_REGION_FRACTION
                    )
                .toInt()

        val maximumY =
            (
                    topHalfBottom -
                            dp(
                                OVERLAY_EDGE_MARGIN_DP
                            ) -
                            view.height
                    )
                .coerceAtLeast(
                    0
                )

        layoutParameters.x =
            (
                    startingWindowX +
                            changeX
                    )
                .coerceIn(
                    0,
                    maximumX
                )

        layoutParameters.y =
            (
                    startingWindowY +
                            changeY
                    )
                .coerceIn(
                    0,
                    maximumY
                )

        windowManager.updateViewLayout(
            view,
            layoutParameters
        )
    }

    private fun extractNumber(
        text: String
    ): Double? {
        return Regex(
            """\d+(?:\.\d+)?"""
        )
            .find(
                text.replace(
                    ",",
                    ""
                )
            )
            ?.value
            ?.toDoubleOrNull()
    }

    private fun invalidatePredictedPostDeliveryEstimate() {
        postDeliveryEstimateGeneration +=
            1L

        predictedPostDeliveryEstimate =
            null

        postDeliveryEstimateOfferId =
            null

        effectiveHourlyRateForScore =
            null

        finalScoreReadyForDisplay =
            false
    }

    private fun beginPredictedPostDeliveryLookup(
        offerId: String
    ) {
        if (
            postDeliveryEstimateOfferId ==
            offerId &&
            predictedPostDeliveryEstimate !=
            null
        ) {
            return
        }

        postDeliveryEstimateGeneration +=
            1L

        val generation =
            postDeliveryEstimateGeneration

        postDeliveryEstimateOfferId =
            offerId

        predictedPostDeliveryEstimate =
            null

        finalScoreReadyForDisplay =
            false

        tryPredictedPostDeliveryLookup(
            offerId = offerId,
            generation = generation,
            retryIndex = 0
        )
    }

    private fun tryPredictedPostDeliveryLookup(
        offerId: String,
        generation: Long,
        retryIndex: Int
    ) {
        if (
            generation !=
            postDeliveryEstimateGeneration ||
            postDeliveryEstimateOfferId !=
            offerId ||
            currentOfferId !=
            offerId
        ) {
            return
        }

        val prediction =
            CustomerMapLearningManager
                .latestPredictionForOffer(
                    context =
                        applicationContext,
                    offerId =
                        offerId
                )

        if (prediction == null) {
            val retryDelay =
                POST_DELIVERY_PREDICTION_RETRY_DELAYS_MS
                    .getOrNull(
                        retryIndex
                    )

            if (retryDelay != null) {
                mainHandler.postDelayed(
                    {
                        tryPredictedPostDeliveryLookup(
                            offerId =
                                offerId,
                            generation =
                                generation,
                            retryIndex =
                                retryIndex + 1
                        )
                    },
                    retryDelay
                )
            } else {
                finalScoreReadyForDisplay =
                    true

                updateRating()
                updateOverlayAppearance()

                Log.w(
                    DATA_LOG_TAG,
                    "No customer-coordinate prediction became available " +
                            "for $offerId; finalized grade with the historical wait fallback."
                )
            }

            return
        }

        waitingAreaRecommender
            .estimatePostDelivery(
                customerLatitude =
                    prediction.correctedLatitude,
                customerLongitude =
                    prediction.correctedLongitude
            ) { estimate ->
                if (
                    generation !=
                    postDeliveryEstimateGeneration ||
                    postDeliveryEstimateOfferId !=
                    offerId ||
                    currentOfferId !=
                    offerId
                ) {
                    return@estimatePostDelivery
                }

                if (estimate == null) {
                    finalScoreReadyForDisplay =
                        true

                    updateRating()
                    updateOverlayAppearance()

                    Log.w(
                        DATA_LOG_TAG,
                        "No routed shopping center was available for " +
                                "$offerId; finalized grade with the historical wait fallback."
                    )
                    return@estimatePostDelivery
                }

                predictedPostDeliveryEstimate =
                    estimate

                finalScoreReadyForDisplay =
                    true

                updateRating()
                updateOverlayAppearance()

                Log.d(
                    DATA_LOG_TAG,
                    buildString {
                        append(
                            "Post-delivery estimate for $offerId: "
                        )
                        append(
                            estimate.displayName
                                ?: estimate.center.centerName
                        )
                        append(
                            String.format(
                                Locale.US,
                                ", %.1f min / %.2f mi drive + %.1f min wait.",
                                estimate.driveMinutes,
                                estimate.driveMiles,
                                estimate.historicalWaitMinutes
                            )
                        )
                    }
                )
            }
    }

    private fun updateRating() {
        val payValue =
            extractNumber(
                pay
            )

        val milesValue =
            extractNumber(
                miles
            )

        if (
            payValue == null ||
            milesValue == null ||
            milesValue <= 0.0
        ) {
            currentScore =
                null

            currentRatingResult =
                null

            currentRestaurantWaitMinutes =
                null

            effectiveHourlyRateForScore =
                null

            currentDemandEstimate =
                null

            demandScoreAdjustment =
                0.0

            demandSummary =
                null

            gasPriceUsed =
                null

            vehicleMpgUsed =
                null

            estimatedHourlyRate =
                "—"

            estimatedCompletionTime =
                "—"

            return
        }

        val settings =
            getSharedPreferences(
                AppSettings.PREFS_NAME,
                MODE_PRIVATE
            )

        val gasPrice =
            settings.getFloat(
                AppSettings.KEY_GAS_PRICE,
                AppSettings
                    .DEFAULT_GAS_PRICE
                    .toFloat()
            ).toDouble()

        val vehicleMpg =
            settings.getFloat(
                AppSettings.KEY_VEHICLE_MPG,
                AppSettings
                    .DEFAULT_VEHICLE_MPG
                    .toFloat()
            ).toDouble()
        val engineConfig =
            EngineConfigStore.load(
                applicationContext
            )

        val restaurantWaitMinutes =
            engineConfig.restaurantWaitMinutes(
                placeId =
                    restaurantPlaceId,

                restaurantName =
                    restaurant
            )
        val ratingResult =
            OrderRatingEngine.calculate(
                input =
                    OrderRatingInput(
                        payout =
                            payValue,

                        displayedMiles =
                            milesValue,

                        gasPricePerGallon =
                            gasPrice,

                        vehicleMpg =
                            vehicleMpg,

                        timeToRestaurantMinutes =
                            timeToRestaurantMinutes,

                        distanceToRestaurantMiles =
                            distanceToRestaurantMiles,

                        restaurantWaitMinutes =
                            restaurantWaitMinutes
                    ),

                config =
                    engineConfig
            )

        gasPriceUsed =
            gasPrice

        vehicleMpgUsed =
            vehicleMpg

        currentRatingResult =
            ratingResult

        currentRestaurantWaitMinutes =
            restaurantWaitMinutes

        val demandEstimate =
            DoorDashDemandTracker.currentEstimate(
                applicationContext
            )

        /*
         * Demand telemetry remains available for analysis, but it no longer
         * adjusts the grade. The final score is exclusively a function of
         * effective projected net $/hr.
         */
        currentDemandEstimate =
            demandEstimate

        demandScoreAdjustment =
            0.0

        demandSummary =
            null

        val postDeliveryEstimate =
            predictedPostDeliveryEstimate

        val postDeliveryWaitMinutes =
            postDeliveryEstimate
                ?.historicalWaitMinutes
                ?: waitingAreaRecommender
                    .fallbackPostDeliveryWaitMinutes()

        val effectiveScoreResult =
            EffectiveHourlyScoreEngine.calculate(
                payout =
                    payValue,
                orderDisplayedMiles =
                    milesValue,
                orderMinutes =
                    ratingResult.estimatedMinutes,
                postDeliveryDriveMinutes =
                    postDeliveryEstimate
                        ?.driveMinutes
                        ?: 0.0,
                postDeliveryDriveMiles =
                    postDeliveryEstimate
                        ?.driveMiles
                        ?: 0.0,
                postDeliveryWaitMinutes =
                    postDeliveryWaitMinutes,
                gasPricePerGallon =
                    gasPrice,
                vehicleMpg =
                    vehicleMpg
            )

        currentScore =
            effectiveScoreResult.score

        effectiveHourlyRateForScore =
            effectiveScoreResult
                .effectiveHourlyRate

        Log.d(
            DATA_LOG_TAG,
            buildString {
                append(
                    "Grade: order="
                )
                append(
                    String.format(
                        Locale.US,
                        "$%.2f/hr",
                        ratingResult.netHourlyRate
                    )
                )
                append(
                    ", effective="
                )
                append(
                    String.format(
                        Locale.US,
                        "$%.2f/hr",
                        effectiveScoreResult.effectiveHourlyRate
                    )
                )
                append(
                    ", score="
                )
                append(
                    String.format(
                        Locale.US,
                        "%.1f",
                        effectiveScoreResult.score
                    )
                )
                append(
                    ", post="
                )
                append(
                    String.format(
                        Locale.US,
                        "%.1f drive + %.1f wait min",
                        effectiveScoreResult.postDeliveryDriveMinutes,
                        effectiveScoreResult.postDeliveryWaitMinutes
                    )
                )
            }
        )

        estimatedHourlyRate =
            String.format(
                Locale.US,
                "$%.2f/hr",
                ratingResult.netHourlyRate
            )

        estimatedCompletionTime =
            String.format(
                Locale.US,
                "%d min",
                ratingResult
                    .estimatedMinutes
                    .roundToInt()
            )
    }

    private fun scoreForDisplay(): Double? {
        return if (
            finalScoreReadyForDisplay
        ) {
            currentScore
        } else {
            null
        }
    }

    private fun shouldDisplayOrder(): Boolean {
        return (
                currentScreenState ==
                        ScreenCaptureService
                            .SCREEN_STATE_OFFER ||
                        currentScreenState ==
                        ScreenCaptureService
                            .SCREEN_STATE_ACTIVE_DELIVERY
                ) &&
                currentRatingResult != null
    }

    private fun updateOverlayAppearance() {
        /*
         * Any expanded idle popup should contain waiting-area information.
         * This helper is cooldown-protected, so frequent redraws/taps reuse
         * the cached recommendation instead of repeating API requests.
         */
        maybeRefreshWaitingAreaRecommendation()

        val accentColor =
            getOverlayColor()

        layoutParameters.width =
            WindowManager.LayoutParams.WRAP_CONTENT

        layoutParameters.height =
            WindowManager.LayoutParams.WRAP_CONTENT

        overlayView?.apply {
            minimumHeight =
                0

            if (
                isExpanded
            ) {
                when {
                    shouldDisplayOrder() -> {
                        showOrder(
                            restaurantName =
                                restaurant,
                            score =
                                scoreForDisplay(),
                            hourlyRate =
                                estimatedHourlyRate,
                            completionTime =
                                estimatedCompletionTime,
                            demandText =
                                demandSummary,
                            destinationSummary =
                                customerAddressResult
                                    ?.overlaySummary(),
                            accentColor =
                                accentColor
                        )
                    }

                    shouldShowWaitingInformation() -> {
                        showWaiting(
                            recommendationText =
                                waitingAreaRecommendationSummary,
                            centerName =
                                waitingAreaDisplayName,
                            accentColor =
                                accentColor
                        )
                    }

                    else -> {
                        /*
                         * An accepted lifecycle can remain active briefly while
                         * the visible screen is changing. Keep the last order
                         * instrument panel instead of flashing an idle state.
                         */
                        showOrder(
                            restaurantName =
                                restaurant,
                            score =
                                scoreForDisplay(),
                            hourlyRate =
                                estimatedHourlyRate,
                            completionTime =
                                estimatedCompletionTime,
                            demandText =
                                demandSummary,
                            destinationSummary =
                                customerAddressResult
                                    ?.overlaySummary(),
                            accentColor =
                                accentColor
                        )
                    }
                }
            } else {
                val scoreText =
                    if (
                        shouldDisplayOrder()
                    ) {
                        scoreForDisplay()?.let {
                                score ->

                            String.format(
                                Locale.US,
                                "%.1f",
                                score
                            )
                        }
                    } else {
                        null
                    }

                val fullOfferScanIsActive =
                    isScanning &&
                            pendingScanContext
                                ?.scanMode !=
                            ScreenCaptureService
                                .SCAN_MODE_CLASSIFY_ONLY

                val collapsedText =
                    when {
                        fullOfferScanIsActive ->
                            "•••"

                        scoreText != null ->
                            scoreText

                        shouldDisplayOrder() ->
                            "•••"

                        else ->
                            "READY"
                    }

                showCollapsed(
                    valueText =
                        collapsedText,
                    accentColor =
                        accentColor,
                    compact =
                        scoreText != null ||
                                fullOfferScanIsActive ||
                                shouldDisplayOrder()
                )
            }

            requestLayout()

            post {
                layoutParameters.width =
                    WindowManager.LayoutParams.WRAP_CONTENT

                layoutParameters.height =
                    WindowManager.LayoutParams.WRAP_CONTENT

                runCatching {
                    windowManager.updateViewLayout(
                        this,
                        layoutParameters
                    )
                }

                constrainOverlayToTopHalf(
                    this
                )
            }
        }
    }

    private fun getOverlayColor(): Int {
        val fullOfferScanIsActive =
            isScanning &&
                    pendingScanContext
                        ?.scanMode !=
                    ScreenCaptureService
                        .SCAN_MODE_CLASSIFY_ONLY

        if (
            fullOfferScanIsActive
        ) {
            return Color.rgb(
                84,
                126,
                255
            )
        }

        if (
            !shouldDisplayOrder()
        ) {
            /*
             * DashTool brand accent for idle / waiting-position mode.
             */
            return Color.rgb(
                218,
                54,
                67
            )
        }

        val score =
            scoreForDisplay()
                ?: return Color.rgb(
                    84,
                    126,
                    255
                )

        /*
         * Muted instrument-panel accents rather than painting the entire
         * overlay red/orange/yellow/green.
         */
        return when {
            score >= 8.0 ->
                Color.rgb(
                    71,
                    207,
                    145
                )

            score >= 6.0 ->
                Color.rgb(
                    225,
                    185,
                    70
                )

            score >= 4.0 ->
                Color.rgb(
                    238,
                    132,
                    68
                )

            else ->
                Color.rgb(
                    235,
                    82,
                    98
                )
        }
    }

    private fun createOverlayBackground(
        color: Int,
        cornerRadiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(
                color
            )

            cornerRadius =
                dp(
                    cornerRadiusDp
                ).toFloat()
        }
    }

    private fun createNotificationChannel() {
        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(
                    R.string
                        .overlay_channel_name
                ),
                NotificationManager
                    .IMPORTANCE_LOW
            ).apply {
                description =
                    getString(
                        R.string
                            .overlay_channel_description
                    )
            }

        notificationManager
            .createNotificationChannel(
                channel
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopDecisionMonitor()
        stopLifecycleMonitor()

        overlayView?.alpha =
            1.0f

        if (
            receiverRegistered
        ) {
            runCatching {
                unregisterReceiver(
                    scanResultReceiver
                )
            }

            receiverRegistered =
                false
        }

        if (
            autoScanReceiverRegistered
        ) {
            runCatching {
                unregisterReceiver(
                    automaticScanRequestReceiver
                )
            }

            autoScanReceiverRegistered =
                false
        }

        overlayView?.let {
                view ->

            if (
                ::windowManager
                    .isInitialized
            ) {
                windowManager.removeView(
                    view
                )
            }
        }

        overlayView =
            null

        mainHandler
            .removeCallbacksAndMessages(
                null
            )

        if (
            ::waitingAreaTracker.isInitialized
        ) {
            waitingAreaTracker.close()
        }

        serviceScope.cancel()
        if (
            accessibilityStageReceiverRegistered
        ) {
            unregisterReceiver(
                accessibilityStageReceiver
            )

            accessibilityStageReceiverRegistered =
                false
        }
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun dp(
        value: Int
    ): Int {
        return (
                value *
                        resources
                            .displayMetrics
                            .density
                )
            .toInt()
    }
}