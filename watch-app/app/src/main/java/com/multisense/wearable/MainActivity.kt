package com.multisense.wearable

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

// ── Shared preferences keys ───────────────────────────────────────────────────
private const val PREFS_NAME  = "multisense_prefs"
private const val KEY_IP      = "server_ip"
private const val KEY_PORT    = "server_port"
private const val KEY_DEVICE  = "device_id"
private const val TAG         = "MainActivity"

/**
 * MainActivity – Wear OS entry point.
 *
 * Renders a minimal Compose for Wear UI:
 *   • Editable server IP and port fields (persisted in SharedPreferences)
 *   • Editable device ID field
 *   • Start / Stop button that launches / stops [SensorService]
 *   • Live status indicator ("Streaming ●" / "Stopped")
 *
 * The UI intentionally stays small so it fits comfortably inside the
 * Galaxy Watch 4's circular 450 × 450 dp display.
 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private val serviceRunningState: MutableState<Boolean> = mutableStateOf(false)
    private val streamConnectedState: MutableState<Boolean> = mutableStateOf(false)
    private val streamStatusLabel: MutableState<String> = mutableStateOf("Idle")
    private val streamStatusSummary: MutableState<String> = mutableStateOf("Enter the backend IP and tap Start.")
    private val audioCaptureState: MutableState<String> = mutableStateOf("Idle")
    private val audioCaptureSummary: MutableState<String> = mutableStateOf("Run a voice check from the watch.")
    private var pendingAction: PendingAction? = null

    private val streamStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != ACTION_STREAM_STATUS) return
            streamConnectedState.value = intent.getBooleanExtra(EXTRA_STREAM_CONNECTED, false)
            streamStatusLabel.value = intent.getStringExtra(EXTRA_STREAM_STATE) ?: "Idle"
            streamStatusSummary.value =
                intent.getStringExtra(EXTRA_STREAM_SUMMARY) ?: "Enter the backend IP and tap Start."
        }
    }

    private val audioStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != ACTION_AUDIO_STATUS) return
            audioCaptureState.value = intent.getStringExtra(EXTRA_AUDIO_STATE) ?: "Idle"
            audioCaptureSummary.value =
                intent.getStringExtra(EXTRA_AUDIO_SUMMARY) ?: "Run a voice check from the watch."
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (missingRuntimePermissions().isNotEmpty()) {
                pendingAction = null
                return@registerForActivityResult
            }

            when (val action = pendingAction) {
                is PendingAction.StartStreaming -> {
                    if (startSensorService(action.ip, action.port, action.deviceId)) {
                        persistConnectionSettings(action.ip, action.port, action.deviceId)
                        serviceRunningState.value = true
                        streamStatusLabel.value = "Streaming"
                        streamStatusSummary.value = "Streaming started. Awaiting backend confirmation..."
                    }
                }
                PendingAction.TriggerAudio -> triggerAudioCapture()
                null -> Unit
            }
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        ContextCompat.registerReceiver(
            this,
            audioStatusReceiver,
            IntentFilter(ACTION_AUDIO_STATUS),
            RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            streamStatusReceiver,
            IntentFilter(ACTION_STREAM_STATUS),
            RECEIVER_EXPORTED
        )

        setContent {
            var ip       by remember { mutableStateOf(prefs.getString(KEY_IP, "192.168.1.100")!!) }
            var port     by remember { mutableStateOf(prefs.getString(KEY_PORT, "8000")!!) }
            var deviceId by remember { mutableStateOf(prefs.getString(KEY_DEVICE, "galaxy_watch_4")!!) }
            val serviceRunning by serviceRunningState
            val streamConnected by streamConnectedState
            val streamLabel by streamStatusLabel
            val streamSummary by streamStatusSummary
            val voiceState by audioCaptureState
            val voiceSummary by audioCaptureSummary

            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {

                        // App title
                        Text(
                            text = "MultiSense",
                            style = MaterialTheme.typography.title2,
                            color = Color(0xFF60A5FA)
                        )

                        // ── Server IP ───────────────────────────────────────
                        WatchInputField(
                            label = "Server IP",
                            value = ip,
                            keyboardType = KeyboardType.Uri,
                            onValueChange = { ip = it }
                        )

                        // ── Port ────────────────────────────────────────────
                        WatchInputField(
                            label = "Port",
                            value = port,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { port = it }
                        )

                        // ── Device ID ───────────────────────────────────────
                        WatchInputField(
                            label = "Device ID",
                            value = deviceId,
                            keyboardType = KeyboardType.Text,
                            onValueChange = { deviceId = it }
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // ── Start / Stop ────────────────────────────────────
                        Button(
                            modifier = Modifier.fillMaxWidth(0.72f),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (serviceRunning) Color(0xFFEF4444) else Color(0xFF3B82F6)
                            ),
                            onClick = {
                                if (serviceRunning) {
                                    stopSensorService()
                                    serviceRunningState.value = false
                                    streamConnectedState.value = false
                                    streamStatusLabel.value = "Idle"
                                    streamStatusSummary.value = "Streaming stopped."
                                } else {
                                    val request = PendingAction.StartStreaming(
                                        ip = ip,
                                        port = port,
                                        deviceId = deviceId
                                    )
                                    val missingPermissions = missingRuntimePermissions()
                                    if (missingPermissions.isNotEmpty()) {
                                        pendingAction = request
                                        permissionLauncher.launch(missingPermissions.toTypedArray())
                                    } else if (startSensorService(ip, port, deviceId)) {
                                        persistConnectionSettings(ip, port, deviceId)
                                        serviceRunningState.value = true
                                        streamStatusLabel.value = "Streaming"
                                        streamStatusSummary.value = "Streaming started. Awaiting backend confirmation..."
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = if (serviceRunning) "Stop" else "Start",
                                fontSize = 14.sp
                            )
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth(0.72f),
                            colors = ButtonDefaults.secondaryButtonColors(
                                backgroundColor = if (voiceState == "Listening") Color(0xFFF59E0B) else Color(0xFF0F766E)
                            ),
                            enabled = serviceRunning && voiceState != "Listening",
                            onClick = {
                                val missingPermissions = missingRuntimePermissions()
                                if (missingPermissions.isNotEmpty()) {
                                    pendingAction = PendingAction.TriggerAudio
                                    permissionLauncher.launch(missingPermissions.toTypedArray())
                                } else {
                                    triggerAudioCapture()
                                }
                            }
                        ) {
                            Text(
                                text = if (voiceState == "Listening") "Listening…" else "Voice Check",
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = voiceState,
                            color = when (voiceState) {
                                "Agitated" -> Color(0xFFFB7185)
                                "Calm" -> Color(0xFF34D399)
                                "Listening", "Analyzing" -> Color(0xFFFBBF24)
                                "Error" -> Color(0xFFF87171)
                                else -> Color(0xFF94A3B8)
                            },
                            fontSize = 11.sp
                        )

                        Text(
                            text = if (!serviceRunning || !streamConnected) streamSummary else voiceSummary,
                            color = Color(0xFF94A3B8),
                            fontSize = 9.sp
                        )

                        // ── Status indicator ────────────────────────────────
                        Text(
                            text = if (serviceRunning) "● $streamLabel" else streamLabel,
                            color = when {
                                serviceRunning && streamLabel != "Disconnected" -> Color(0xFF22C55E)
                                streamLabel == "Connecting" -> Color(0xFFFBBF24)
                                streamLabel == "Disconnected" -> Color(0xFFF87171)
                                else -> Color(0xFF475569)
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(audioStatusReceiver)
        unregisterReceiver(streamStatusReceiver)
        super.onDestroy()
    }

    // ── Service control ───────────────────────────────────────────────────────

    private fun missingRuntimePermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.BODY_SENSORS
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.RECORD_AUDIO
        }

        return permissions
    }

    private fun persistConnectionSettings(ip: String, port: String, deviceId: String) {
        prefs.edit()
            .putString(KEY_IP, ip)
            .putString(KEY_PORT, port)
            .putString(KEY_DEVICE, deviceId)
            .apply()
    }

    /**
     * Validate the address and start [SensorService].
     * Returns false (without starting anything) if the IP is blank or port
     * is not a valid number.
     */
    private fun startSensorService(ip: String, port: String, deviceId: String): Boolean {
        val portNum = port.trim().toIntOrNull()
        if (ip.isBlank() || portNum == null || portNum !in 1..65535) return false

        val url = "ws://${ip.trim()}:$portNum/ws/wearable"
        return try {
            startForegroundService(
                Intent(this, SensorService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_DEVICE, deviceId.trim().ifBlank { "galaxy_watch_4" })
                }
            )
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SensorService blocked by foreground-service policy", e)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to start SensorService", e)
            false
        }
    }

    private fun stopSensorService() {
        startService(
            Intent(this, SensorService::class.java).apply { action = ACTION_STOP }
        )
    }

    private fun triggerAudioCapture() {
        startService(
            Intent(this, SensorService::class.java).apply { action = ACTION_TRIGGER_AUDIO }
        )
    }
}

private sealed interface PendingAction {
    data class StartStreaming(
        val ip: String,
        val port: String,
        val deviceId: String,
    ) : PendingAction

    data object TriggerAudio : PendingAction
}

// ── Reusable Wear input component ─────────────────────────────────────────────

@Composable
private fun WatchInputField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color(0xFF64748B)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E2433))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
