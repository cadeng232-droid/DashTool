package com.example.dashtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.dashtool.data.DashToolRepository
import kotlinx.coroutines.launch
import android.util.Log

private const val KEY_HOME_ADDRESS =
    "home_address"

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    DashToolSettingsScreen()
                }
            }
        }
    }
}

@Composable
fun DashToolSettingsScreen() {
    val context =
        LocalContext.current

    val coroutineScope =
        rememberCoroutineScope()

    val repository =
        remember(context) {
            DashToolRepository.getInstance(
                context
            )
        }

    val settings =
        remember(context) {
            context.getSharedPreferences(
                AppSettings.PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }

    val scanPreferences =
        remember(context) {
            context.getSharedPreferences(
                ScreenCaptureService.PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }

    var homeAddress by rememberSaveable {
        mutableStateOf(
            settings.getString(
                KEY_HOME_ADDRESS,
                ""
            ) ?: ""
        )
    }

    var mpgText by rememberSaveable {
        mutableStateOf(
            settings.getFloat(
                AppSettings.KEY_VEHICLE_MPG,
                AppSettings.DEFAULT_VEHICLE_MPG
                    .toFloat()
            ).toString()
        )
    }

    var gasPriceText by rememberSaveable {
        mutableStateOf(
            settings.getFloat(
                AppSettings.KEY_GAS_PRICE,
                AppSettings.DEFAULT_GAS_PRICE
                    .toFloat()
            ).toString()
        )
    }

    var isRunning by rememberSaveable {
        mutableStateOf(
            scanPreferences.getBoolean(
                ScreenCaptureService.KEY_READER_ACTIVE,
                false
            )
        )
    }

    /*
     * Prevents the Start or Stop button from being
     * pressed repeatedly during database or permission
     * operations.
     */
    var isChangingState by rememberSaveable {
        mutableStateOf(false)
    }

    var statusHasError by rememberSaveable {
        mutableStateOf(false)
    }

    var statusMessage by rememberSaveable {
        mutableStateOf(
            if (isRunning) {
                "DashTool is running."
            } else {
                "Review your settings, then start DashTool."
            }
        )
    }

    fun notificationAccessIsEnabled(): Boolean {
        return NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun saveSettings(): Boolean {
        val mpg =
            mpgText.toDoubleOrNull()

        val gasPrice =
            gasPriceText.toDoubleOrNull()

        val settingsAreInvalid =
            homeAddress.isBlank() ||
                    mpg == null ||
                    gasPrice == null ||
                    mpg <= 0.0 ||
                    gasPrice <= 0.0

        if (settingsAreInvalid) {
            statusHasError = true

            statusMessage =
                "Enter your home address, vehicle MPG, " +
                        "and a positive gas price."

            return false
        }

        settings.edit()
            .putString(
                KEY_HOME_ADDRESS,
                homeAddress.trim()
            )
            .putFloat(
                AppSettings.KEY_VEHICLE_MPG,
                mpg.toFloat()
            )
            .putFloat(
                AppSettings.KEY_GAS_PRICE,
                gasPrice.toFloat()
            )
            .apply()

        statusHasError = false
        statusMessage = "Settings saved."

        return true
    }

    /*
     * Starts a database session before either service
     * begins running.
     *
     * This guarantees that an automatic offer scan
     * cannot occur before a session exists.
     */
    fun startDashToolServices() {
        isChangingState = true
        statusHasError = false
        statusMessage = "Starting DashTool..."

        coroutineScope.launch {
            try {
                val configSync =
                    EngineConfigManager.refresh(
                        context.applicationContext
                    )

                Log.d(
                    "DashToolEngineConfig",
                    configSync.message
                )

                repository.startNewSession()

                val screenReaderIntent =
                    Intent(
                        context,
                        ScreenCaptureService::class.java
                    ).apply {
                        action =
                            ScreenCaptureService
                                .ACTION_START_ACCESSIBILITY_PROCESSOR
                    }

                ContextCompat.startForegroundService(
                    context,
                    screenReaderIntent
                )

                val overlayIntent =
                    Intent(
                        context,
                        OverlayService::class.java
                    )

                ContextCompat.startForegroundService(
                    context,
                    overlayIntent
                )

                isRunning = true
                statusHasError = false
                statusMessage =
                    "DashTool is running with engine v" +
                            configSync.config.engineVersion +
                            ". Automatic scanning is enabled."
            } catch (exception: Exception) {
                context.stopService(
                    Intent(
                        context,
                        ScreenCaptureService::class.java
                    )
                )

                context.stopService(
                    Intent(
                        context,
                        OverlayService::class.java
                    )
                )

                runCatching {
                    repository.closeActiveSession()
                }

                isRunning = false
                statusHasError = true
                statusMessage =
                    "DashTool could not start: " +
                            (
                                    exception.message
                                        ?: exception.javaClass.simpleName
                                    )
            } finally {
                isChangingState = false
            }
        }
    }



    /*
     * Requests permission for DashTool's own ongoing
     * notifications, then starts the services.
     */
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestPermission()
        ) { permissionGranted ->

            if (!permissionGranted) {
                Toast.makeText(
                    context,
                    "Notifications are disabled, but DashTool can still run.",
                    Toast.LENGTH_LONG
                ).show()
            }

            startDashToolServices()
        }

    fun continueAfterLocationPermission() {
        val notificationPermissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (notificationPermissionGranted) {
            startDashToolServices()
        } else {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    /*
     * Requests location permission for the existing
     * Google restaurant travel estimate.
     */
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()
        ) { permissions ->

            val fineLocationGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            val coarseLocationGranted =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] == true

            if (
                fineLocationGranted ||
                coarseLocationGranted
            ) {
                continueAfterLocationPermission()
            } else {
                isChangingState = false
                statusHasError = true

                statusMessage =
                    "Location permission is required " +
                            "for Google travel estimates."
            }
        }

    fun continueAfterAccessibilityAccess() {
        if (
            !DashToolAccessibilityService
                .isEnabled(
                    context
                )
        ) {
            isChangingState = false
            statusHasError = true
            statusMessage =
                "Accessibility screenshot access was not enabled."

            return
        }

        val fineLocationGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (
            fineLocationGranted ||
            coarseLocationGranted
        ) {
            continueAfterLocationPermission()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val accessibilityAccessLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .StartActivityForResult()
        ) {
            continueAfterAccessibilityAccess()
        }

    fun continueAfterNotificationAccess() {
        if (!notificationAccessIsEnabled()) {
            isChangingState = false
            statusHasError = true

            statusMessage =
                "Notification access was not enabled. " +
                        "Enable DashTool to use automatic scanning."

            return
        }

        if (
            DashToolAccessibilityService
                .isEnabled(
                    context
                )
        ) {
            continueAfterAccessibilityAccess()
        } else {
            statusHasError = false
            statusMessage =
                "Enable DashTool Screenshot Scanner, " +
                        "then return to the app."

            accessibilityAccessLauncher.launch(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        }
    }

    val notificationAccessLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .StartActivityForResult()
        ) {
            continueAfterNotificationAccess()
        }

    fun continueAfterOverlayPermission() {
        if (!Settings.canDrawOverlays(context)) {
            isChangingState = false
            statusHasError = true

            statusMessage =
                "Overlay permission was not granted."

            return
        }

        if (notificationAccessIsEnabled()) {
            continueAfterNotificationAccess()
        } else {
            statusHasError = false

            statusMessage =
                "Enable notification access for DashTool, " +
                        "then return to the app."

            notificationAccessLauncher.launch(
                Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
            )
        }
    }

    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .StartActivityForResult()
        ) {
            continueAfterOverlayPermission()
        }

    /*
     * Stops both foreground services immediately,
     * then closes the active Room session.
     */
    fun stopDashTool() {
        isChangingState = true
        statusHasError = false
        statusMessage = "Stopping DashTool..."

        context.stopService(
            Intent(
                context,
                ScreenCaptureService::class.java
            )
        )

        context.stopService(
            Intent(
                context,
                OverlayService::class.java
            )
        )

        coroutineScope.launch {
            try {
                repository.closeActiveSession()

                isRunning = false
                statusHasError = false
                statusMessage =
                    "DashTool has stopped."
            } catch (exception: Exception) {
                /*
                 * The services are already stopped, even
                 * if closing the database session failed.
                 */
                isRunning = false
                statusHasError = true

                statusMessage =
                    "DashTool stopped, but the session " +
                            "could not be closed: " +
                            (
                                    exception.message
                                        ?: exception.javaClass.simpleName
                                    )
            } finally {
                isChangingState = false
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(24.dp),

        verticalArrangement =
            Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "DashTool",

            style =
                MaterialTheme
                    .typography
                    .headlineLarge,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "Configure your driver information " +
                        "and start the order assistant.",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )

        Card(
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Column(
                modifier =
                    Modifier.padding(20.dp),

                verticalArrangement =
                    Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Driver settings",

                    style =
                        MaterialTheme
                            .typography
                            .titleLarge,

                    fontWeight =
                        FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = homeAddress,

                    onValueChange = {
                        homeAddress = it
                    },

                    label = {
                        Text("Home address")
                    },

                    supportingText = {
                        Text(
                            "Saved for future destination " +
                                    "and return-home calculations."
                        )
                    },

                    keyboardOptions =
                        KeyboardOptions(
                            capitalization =
                                KeyboardCapitalization.Words,

                            keyboardType =
                                KeyboardType.Text
                        ),

                    modifier =
                        Modifier.fillMaxWidth()
                )

                SettingsNumberField(
                    label = "Vehicle MPG",
                    value = mpgText,

                    onValueChange = {
                        mpgText = it
                    }
                )

                SettingsNumberField(
                    label =
                        "Gas price per gallon ($)",

                    value = gasPriceText,

                    onValueChange = {
                        gasPriceText = it
                    }
                )

                OutlinedButton(
                    onClick = {
                        saveSettings()
                    },

                    enabled =
                        !isChangingState,

                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("Save Settings")
                }
            }
        }

        Card(
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Column(
                modifier =
                    Modifier.padding(20.dp),

                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text =
                        if (isRunning) {
                            "Active"
                        } else {
                            "Status"
                        },

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge,

                    color =
                        if (isRunning) {
                            MaterialTheme
                                .colorScheme
                                .primary
                        } else {
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                        }
                )

                Text(
                    text = statusMessage,

                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,

                    color =
                        if (statusHasError) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            MaterialTheme
                                .colorScheme
                                .onSurface
                        }
                )
            }
        }

        Button(
            onClick = {
                if (isRunning) {
                    stopDashTool()
                } else {
                    val settingsSaved =
                        saveSettings()

                    if (!settingsSaved) {
                        return@Button
                    }

                    isChangingState = true

                    if (
                        Settings.canDrawOverlays(
                            context
                        )
                    ) {
                        continueAfterOverlayPermission()
                    } else {
                        statusHasError = false

                        statusMessage =
                            "Grant overlay permission, " +
                                    "then return to DashTool."

                        val permissionIntent =
                            Intent(
                                Settings
                                    .ACTION_MANAGE_OVERLAY_PERMISSION,

                                "package:${context.packageName}"
                                    .toUri()
                            )

                        overlayPermissionLauncher.launch(
                            permissionIntent
                        )
                    }
                }
            },

            enabled =
                !isChangingState,

            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (isRunning) {
                            MaterialTheme
                                .colorScheme
                                .error
                        } else {
                            MaterialTheme
                                .colorScheme
                                .primary
                        }
                ),

            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                text =
                    when {
                        isChangingState && isRunning ->
                            "Stopping..."

                        isChangingState ->
                            "Starting..."

                        isRunning ->
                            "Stop DashTool"

                        else ->
                            "Start DashTool"
                    },

                modifier =
                    Modifier.padding(
                        vertical = 6.dp
                    )
            )
        }

        Text(
            text =
                "Starting DashTool activates the floating overlay, " +
                        "accessibility screenshot scanner, and automatic scans " +
                        "for new DoorDash offers. It does not create a continuous " +
                        "screen-sharing session.",

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,

        onValueChange =
            onValueChange,

        label = {
            Text(label)
        },

        singleLine = true,

        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    KeyboardType.Decimal
            ),

        modifier =
            Modifier.fillMaxWidth()
    )
}