package com.multisense.wearable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SensorService"

// ─── Intent actions ───────────────────────────────────────────────────────────
const val ACTION_START  = "com.multisense.START"
const val ACTION_STOP   = "com.multisense.STOP"
const val EXTRA_URL     = "extra_server_url"
const val EXTRA_DEVICE  = "extra_device_id"

// ─── Sensor configuration ─────────────────────────────────────────────────────
/**
 * 50 000 µs = 50 ms period = 20 Hz sampling rate.
 *
 * Note: SensorManager delivers events as fast as possible if the hardware
 * cannot match the requested rate; in practice a Galaxy Watch 4 honours
 * ~20 Hz at this value.  We do NOT use SENSOR_DELAY_GAME (20 ms / 50 Hz)
 * because that would require more aggressive batching and higher CPU duty.
 */
private const val SAMPLING_PERIOD_US = 50_000

/**
 * Batch 20 accelerometer samples before serialising and sending.
 * At 20 Hz this yields exactly one network packet per second.
 */
private const val BATCH_SIZE = 20

/**
 * If the socket is disconnected, keep at most 3 × BATCH_SIZE pending
 * samples to limit memory growth during outages.
 */
private const val MAX_PENDING = BATCH_SIZE * 3

// ─── Notification ─────────────────────────────────────────────────────────────
private const val NOTIF_ID    = 1
private const val CHANNEL_ID  = "multisense_sensor_stream"

/**
 * SensorService – foreground service that:
 *
 *  1. Acquires a PARTIAL_WAKE_LOCK so the CPU stays alive in ambient / screen-off.
 *  2. Registers TYPE_ACCELEROMETER and TYPE_GYROSCOPE listeners at 20 Hz.
 *  3. Batches 20 accelerometer samples (≈ 1 s) and transmits them as a JSON
 *     packet over a persistent WebSocket ([SocketClient]).
 *  4. Auto-reconnects the socket within 2 s on any network failure.
 *
 * Streams motion sensors continuously and augments each batch with the latest
 * available heart-rate and session-step values from standard sensor APIs.
 */
class SensorService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var socketClient: SocketClient
    private lateinit var wakeLock: PowerManager.WakeLock
    private var heartRateSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null

    // Ring-style buffers — cleared after each flush
    private val accelBuf = ArrayList<FloatArray>(BATCH_SIZE + 4)
    private val gyroBuf  = ArrayList<FloatArray>(BATCH_SIZE + 4)
    private val tsBuf    = ArrayList<Long>(BATCH_SIZE + 4)

    private var deviceId = "galaxy_watch_4"
    @Volatile private var latestHeartRate: Float? = null
    @Volatile private var latestSteps: Int? = null
    private var stepCounterBaseline: Float? = null

    /** Cumulative count of motion samples dispatched (for diagnostics). */
    @Volatile private var totalSamplesSent = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                    ?: run { Log.e(TAG, "No server URL supplied"); stopSelf(); return START_NOT_STICKY }
                deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: "galaxy_watch_4"
                latestHeartRate = null
                latestSteps = 0
                stepCounterBaseline = null

                startForeground(NOTIF_ID, buildNotification("Connecting to $url …"))
                acquireWakeLock()
                initSocket(url)
                registerSensors()
            }

            ACTION_STOP -> {
                Log.i(TAG, "Stop command received")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(sensorListener)
        if (::socketClient.isInitialized) socketClient.disconnect()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        Log.i(TAG, "Destroyed – total motion samples sent: $totalSamplesSent")
    }

    // ── Initialisation helpers ────────────────────────────────────────────────

    private fun initSocket(url: String) {
        socketClient = SocketClient { status ->
            val text = when (status) {
                "connected"    -> "Streaming ● $deviceId → server"
                "connecting"   -> "Reconnecting …"
                else           -> "Disconnected – retrying in 2 s"
            }
            updateNotification(text)
        }
        socketClient.connect(url)
    }

    private fun registerSensors() {
        // Deliver callbacks on a dedicated sensor thread (avoids blocking main)
        val handler = Handler(Looper.getMainLooper())

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accel == null) Log.e(TAG, "Accelerometer not found on this device!")
        if (gyro  == null) Log.w(TAG, "Gyroscope not found – gyro fields will be zero")
        if (heartRateSensor == null) Log.w(TAG, "Heart rate sensor unavailable")
        if (stepCounterSensor == null) Log.w(TAG, "Step counter sensor unavailable")

        accel?.let { sensorManager.registerListener(sensorListener, it, SAMPLING_PERIOD_US, handler) }
        gyro?.let  { sensorManager.registerListener(sensorListener, it, SAMPLING_PERIOD_US, handler) }
        heartRateSensor?.let { sensorManager.registerListener(sensorListener, it, SAMPLING_PERIOD_US, handler) }
        stepCounterSensor?.let { sensorManager.registerListener(sensorListener, it, SAMPLING_PERIOD_US, handler) }

        Log.i(TAG, "Sensors registered at ${1_000_000 / SAMPLING_PERIOD_US} Hz target, batch=$BATCH_SIZE")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MultiSense::SensorWakeLock"
        )
        // Maximum hold: 2 hours.  Service should be stopped by the user long before this.
        wakeLock.acquire(2 * 60 * 60 * 1_000L)
        Log.i(TAG, "WakeLock acquired")
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.d(TAG, "${sensor.name} accuracy → $accuracy")
        }

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelBuf.add(event.values.clone())
                    tsBuf.add(System.currentTimeMillis())
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroBuf.add(event.values.clone())
                }
                Sensor.TYPE_HEART_RATE -> {
                    val bpm = event.values.firstOrNull()
                    if (event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && bpm != null && bpm > 0f) {
                        latestHeartRate = bpm
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSteps = event.values.firstOrNull() ?: return
                    val baseline = stepCounterBaseline
                    if (baseline == null || totalSteps < baseline) {
                        stepCounterBaseline = totalSteps
                        latestSteps = 0
                    } else {
                        latestSteps = (totalSteps - baseline).toInt().coerceAtLeast(0)
                    }
                }
            }

            // Trigger a flush once we have a full accelerometer batch
            if (accelBuf.size >= BATCH_SIZE) flushBatch()
        }
    }

    // ── Packet serialisation & dispatch ──────────────────────────────────────

    private fun flushBatch() {
        val count = minOf(BATCH_SIZE, accelBuf.size)

        if (!socketClient.isConnected) {
            // Discard oldest samples to cap memory during disconnections
            if (accelBuf.size > MAX_PENDING) {
                accelBuf.subList(0, count).clear()
                tsBuf.subList(0, count).clear()
                if (gyroBuf.size >= count) gyroBuf.subList(0, count).clear()
                Log.w(TAG, "Dropped $count samples (socket not connected)")
            }
            return
        }

        val motionArray = JSONArray()
        for (i in 0 until count) {
            val a = accelBuf[i]
            val g = if (i < gyroBuf.size) gyroBuf[i] else FloatArray(3)

            motionArray.put(
                JSONObject()
                    .put("ts", tsBuf[i])
                    .put("accel", JSONObject()
                        .put("x", a[0].round4())
                        .put("y", a[1].round4())
                        .put("z", a[2].round4()))
                    .put("gyro", JSONObject()
                        .put("x", g[0].round4())
                        .put("y", g[1].round4())
                        .put("z", g[2].round4()))
            )
        }

        val payload = JSONObject()
            .put("device_id",   deviceId)
            .put("phase",       "live")          // Phase 3 will set activity type
            .put("heart_rate",  latestHeartRate?.round1() ?: JSONObject.NULL)
            .put("steps",       latestSteps ?: JSONObject.NULL)
            .put("motion",      motionArray)

        val sent = socketClient.send(payload.toString())
        if (sent) {
            totalSamplesSent += count
            Log.v(TAG, "Sent packet: $count samples, total=$totalSamplesSent")
        } else {
            Log.w(TAG, "Send returned false – socket closed unexpectedly")
        }

        // Remove consumed samples from the front of each buffer
        accelBuf.subList(0, count).clear()
        tsBuf.subList(0, count).clear()
        if (gyroBuf.size >= count) gyroBuf.subList(0, count).clear()
    }

    /** Round to 4 decimal places to keep JSON payload compact. */
    private fun Float.round4(): Double = (this.toDouble() * 10_000).toLong() / 10_000.0
    private fun Float.round1(): Double = (this.toDouble() * 10).toLong() / 10.0

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sensor Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while MultiSense is streaming sensor data"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SensorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiSense Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_sensor)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status))
    }
}
