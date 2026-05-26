package com.multisense.wearable

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.coroutines.resume

private const val TAG = "SensorService"

// ─── Intent actions ───────────────────────────────────────────────────────────
const val ACTION_START  = "com.multisense.START"
const val ACTION_STOP   = "com.multisense.STOP"
const val ACTION_TRIGGER_AUDIO = "com.multisense.TRIGGER_AUDIO"
const val EXTRA_URL     = "extra_server_url"
const val EXTRA_DEVICE  = "extra_device_id"
const val ACTION_AUDIO_STATUS = "com.multisense.AUDIO_STATUS"
const val EXTRA_AUDIO_STATE = "extra_audio_state"
const val EXTRA_AUDIO_SUMMARY = "extra_audio_summary"
const val ACTION_STREAM_STATUS = "com.multisense.STREAM_STATUS"
const val EXTRA_STREAM_CONNECTED = "extra_stream_connected"
const val EXTRA_STREAM_STATE = "extra_stream_state"
const val EXTRA_STREAM_SUMMARY = "extra_stream_summary"

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
private const val AUDIO_SAMPLE_RATE = 16_000
private const val AUDIO_WINDOW_SECONDS = 5
private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val AUDIO_FRAME_SIZE = 400
private const val SPEECH_LISTEN_WINDOW_MS = 5_000L
private const val SPEECH_RECOGNITION_TIMEOUT_MS = 8_000L

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
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartRateSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    private val profanityTerms by lazy { loadProfanityTerms() }

    // Ring-style buffers — cleared after each flush
    private val accelBuf = ArrayList<FloatArray>(BATCH_SIZE + 4)
    private val gyroBuf  = ArrayList<FloatArray>(BATCH_SIZE + 4)
    private val tsBuf    = ArrayList<Long>(BATCH_SIZE + 4)

    private var deviceId = "galaxy_watch_4"
    @Volatile private var latestHeartRate: Float? = null
    @Volatile private var latestSteps: Int? = null
    @Volatile private var latestAudioAnalysis: AudioAnalysis? = null
    @Volatile private var audioCaptureInProgress = false
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
                broadcastStreamStatus(
                    connected = false,
                    state = "Connecting",
                    summary = "Connecting to backend…"
                )
                acquireWakeLock()
                initSocket(url)
                registerSensors()
            }

            ACTION_TRIGGER_AUDIO -> {
                if (!::socketClient.isInitialized || !socketClient.isConnected) {
                    broadcastAudioStatus("Error", "Start live streaming before running a voice check.")
                } else if (audioCaptureInProgress) {
                    broadcastAudioStatus("Listening", "Voice check already running.")
                } else {
                    captureAudioWindow()
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "Stop command received")
                broadcastStreamStatus(
                    connected = false,
                    state = "Idle",
                    summary = "Streaming stopped."
                )
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
        serviceScope.cancel()
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
            val summary = when (status) {
                "connected" -> "Connected to backend."
                "connecting" -> "Connecting to backend…"
                else -> "Backend unreachable. Check watch IP/port."
            }
            broadcastStreamStatus(
                connected = status == "connected",
                state = status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                summary = summary
            )
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
            .put("audio",       latestAudioAnalysis?.toJson() ?: JSONObject.NULL)
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

    private fun captureAudioWindow() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            broadcastAudioStatus("Error", "Microphone permission is missing.")
            return
        }

        serviceScope.launch {
            audioCaptureInProgress = true
            broadcastAudioStatus("Listening", "Capturing 5 seconds of microphone audio…")

            val recognitionOutcome = try {
                withTimeoutOrNull(SPEECH_RECOGNITION_TIMEOUT_MS) {
                    recognizeSpeechWindow()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Speech recognition failed", t)
                null
            }

            if (recognitionOutcome == null) {
                broadcastAudioStatus("Analyzing", "Speech recognizer unavailable. Falling back to microphone analysis…")
            }

            val analysis = when {
                recognitionOutcome != null -> extractAudioAnalysis(
                    samples = recognitionOutcome.audioBuffer,
                    transcript = recognitionOutcome.transcript,
                    transcriptConfidence = recognitionOutcome.confidence,
                    recognitionMode = recognitionOutcome.mode
                )
                else -> try {
                    analyzeMicrophoneWindow()
                } catch (t: Throwable) {
                    Log.e(TAG, "Audio capture fallback failed", t)
                    null
                }
            }

            if (analysis == null) {
                latestAudioAnalysis = null
                broadcastAudioStatus("Error", "Voice check failed. Try again.")
            } else {
                latestAudioAnalysis = analysis
                broadcastAudioStatus(
                    if (analysis.agitated) "Agitated" else "Calm",
                    analysis.summary
                )
                sendImmediateTelemetryFrame()
            }

            audioCaptureInProgress = false
        }
    }

    private fun analyzeMicrophoneWindow(): AudioAnalysis? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_CONFIG,
            AUDIO_ENCODING
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Invalid audio buffer size: $minBufferBytes")
            return null
        }

        val recordBufferBytes = maxOf(minBufferBytes, AUDIO_FRAME_SIZE * 4)
        val totalSamples = AUDIO_SAMPLE_RATE * AUDIO_WINDOW_SECONDS
        val captureBuffer = ShortArray(recordBufferBytes / 2)
        val allSamples = ShortArray(totalSamples)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_CONFIG,
            AUDIO_ENCODING,
            recordBufferBytes
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "AudioRecord failed to initialize")
            return null
        }

        var offset = 0
        try {
            recorder.startRecording()
            broadcastAudioStatus("Analyzing", "Processing captured speech cues…")
            while (offset < totalSamples) {
                val read = recorder.read(
                    captureBuffer,
                    0,
                    minOf(captureBuffer.size, totalSamples - offset)
                )
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    return null
                }
                System.arraycopy(captureBuffer, 0, allSamples, offset, read)
                offset += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        return extractAudioAnalysis(allSamples)
    }

    private suspend fun recognizeSpeechWindow(): RecognitionOutcome? =
        suspendCancellableCoroutine { continuation ->
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            mainHandler.post {
                val recognizer = try {
                    if (android.os.Build.VERSION.SDK_INT >= 31 &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                    ) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(this)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to create SpeechRecognizer", t)
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }

                val rmsSamples = ArrayList<Float>()
                val audioSamples = ArrayList<Short>()
                var finished = false

                fun complete(outcome: RecognitionOutcome?) {
                    if (finished) return
                    finished = true
                    runCatching { recognizer.cancel() }
                    recognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(outcome)
                    }
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        broadcastAudioStatus("Listening", "Speak now for the voice check…")
                    }

                    override fun onBeginningOfSpeech() {
                        broadcastAudioStatus("Analyzing", "Listening for speech content…")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        rmsSamples += rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        if (buffer == null) return
                        var idx = 0
                        while (idx + 1 < buffer.size) {
                            val high = buffer[idx].toInt() and 0xFF
                            val low = buffer[idx + 1].toInt() and 0xFF
                            val sample = ((high shl 8) or low).toShort()
                            audioSamples += sample
                            idx += 2
                        }
                    }

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        complete(null)
                    }

                    override fun onResults(results: Bundle?) {
                        val transcripts = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        val confidences = results
                            ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val transcript = transcripts.firstOrNull()
                        val confidence = confidences?.firstOrNull()
                        val samples = when {
                            audioSamples.isNotEmpty() -> audioSamples.toShortArray()
                            else -> rmsSamplesToShortArray(rmsSamples)
                        }
                        val mode = if (
                            android.os.Build.VERSION.SDK_INT >= 31 &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(this@SensorService)
                        ) {
                            "speech_recognizer_on_device"
                        } else {
                            "speech_recognizer"
                        }
                        complete(
                            RecognitionOutcome(
                                transcript = transcript,
                                confidence = confidence,
                                audioBuffer = samples,
                                mode = mode
                            )
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SPEECH_LISTEN_WINDOW_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                }

                continuation.invokeOnCancellation {
                    mainHandler.post {
                        runCatching { recognizer.cancel() }
                        recognizer.destroy()
                    }
                }

                recognizer.startListening(recognizerIntent)
                mainHandler.postDelayed(
                    {
                        if (!finished) {
                            runCatching { recognizer.stopListening() }
                        }
                    },
                    SPEECH_LISTEN_WINDOW_MS
                )
            }
        }

    private fun extractAudioAnalysis(
        samples: ShortArray,
        transcript: String? = null,
        transcriptConfidence: Float? = null,
        recognitionMode: String = "audio_record",
    ): AudioAnalysis {
        var sumSquares = 0.0
        var peak = 0.0
        var zeroCrossings = 0
        var previous = 0.0

        for (i in samples.indices) {
            val normalized = samples[i] / 32768.0
            val magnitude = abs(normalized)
            sumSquares += normalized * normalized
            if (magnitude > peak) peak = magnitude
            if (i > 0 && (normalized >= 0) != (previous >= 0)) {
                zeroCrossings += 1
            }
            previous = normalized
        }

        val rms = sqrt(sumSquares / samples.size)
        val zcr = zeroCrossings.toDouble() / samples.size.coerceAtLeast(1)

        var voicedFrames = 0
        var frameCount = 0
        val frameEnergies = ArrayList<Double>()
        var cursor = 0
        while (cursor + AUDIO_FRAME_SIZE <= samples.size) {
            var frameEnergy = 0.0
            var frameCrossings = 0
            var prevSample = samples[cursor] / 32768.0
            for (i in cursor until cursor + AUDIO_FRAME_SIZE) {
                val normalized = samples[i] / 32768.0
                frameEnergy += normalized * normalized
                if (i > cursor && (normalized >= 0) != (prevSample >= 0)) {
                    frameCrossings += 1
                }
                prevSample = normalized
            }
            val frameRms = sqrt(frameEnergy / AUDIO_FRAME_SIZE)
            val frameZcr = frameCrossings.toDouble() / AUDIO_FRAME_SIZE
            frameEnergies += frameRms
            if (frameRms >= 0.035 && frameZcr in 0.02..0.25) {
                voicedFrames += 1
            }
            frameCount += 1
            cursor += AUDIO_FRAME_SIZE
        }

        val speechRatio = if (frameCount == 0) 0.0 else voicedFrames.toDouble() / frameCount
        val avgFrameEnergy = if (frameEnergies.isEmpty()) 0.0 else frameEnergies.average()
        val energyVariance = if (frameEnergies.size <= 1) {
            0.0
        } else {
            frameEnergies.sumOf { (it - avgFrameEnergy) * (it - avgFrameEnergy) } / frameEnergies.size
        }

        val profanityMatch = detectProfanity(transcript)
        val loudVoice = rms >= 0.14 || peak >= 0.82
        val strainedVoice = speechRatio >= 0.35 && zcr >= 0.09
        val agitated =
            profanityMatch != null || loudVoice || (rms >= 0.09 && strainedVoice) || peak >= 0.9
        val riskLevel = when {
            profanityMatch != null -> "high"
            peak >= 0.9 || rms >= 0.2 -> "high"
            agitated -> "medium"
            speechRatio >= 0.2 -> "low"
            else -> "none"
        }

        val summary = when {
            profanityMatch != null -> "Profanity detected in speech. Patient may be agitated."
            agitated && loudVoice -> "Raised voice detected. Patient may be agitated."
            agitated -> "Strained speech detected. Monitor agitation."
            speechRatio < 0.1 -> "Little speech detected in this voice check."
            else -> "Voice check did not indicate agitation."
        }

        return AudioAnalysis(
            capturedAtMs = System.currentTimeMillis(),
            agitated = agitated,
            riskLevel = riskLevel,
            loudVoice = loudVoice,
            speechDetected = speechRatio >= 0.1,
            cursingDetected = profanityMatch != null,
            flaggedPhrase = profanityMatch,
            transcript = transcript,
            transcriptConfidence = transcriptConfidence,
            recognitionMode = recognitionMode,
            summary = summary,
            rmsEnergy = rms,
            peakAmplitude = peak,
            zeroCrossingRate = zcr,
            speechRatio = speechRatio,
            frameEnergyVariance = energyVariance
        )
    }

    private fun rmsSamplesToShortArray(rmsSamples: List<Float>): ShortArray {
        if (rmsSamples.isEmpty()) return ShortArray(0)
        return ShortArray(rmsSamples.size) { index ->
            val normalized = ((rmsSamples[index] + 2f) / 12f).coerceIn(0f, 1f)
            (normalized * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun detectProfanity(transcript: String?): String? {
        if (transcript.isNullOrBlank()) return null
        val normalized = transcript.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return profanityTerms.firstOrNull { term ->
            Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(normalized)
        }
    }

    private fun loadProfanityTerms(): Set<String> =
        runCatching {
            assets.open("profanity_terms.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        }.getOrElse {
            Log.w(TAG, "Unable to load profanity terms asset", it)
            emptySet()
        }

    private fun sendImmediateTelemetryFrame() {
        if (!::socketClient.isInitialized || !socketClient.isConnected) return

        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("phase", "live")
            .put("heart_rate", latestHeartRate?.round1() ?: JSONObject.NULL)
            .put("steps", latestSteps ?: JSONObject.NULL)
            .put("audio", latestAudioAnalysis?.toJson() ?: JSONObject.NULL)
            .put("motion", JSONArray())

        socketClient.send(payload.toString())
    }

    private fun broadcastAudioStatus(state: String, summary: String) {
        sendBroadcast(
            Intent(ACTION_AUDIO_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_AUDIO_STATE, state)
                putExtra(EXTRA_AUDIO_SUMMARY, summary)
            }
        )
    }

    private fun broadcastStreamStatus(connected: Boolean, state: String, summary: String) {
        sendBroadcast(
            Intent(ACTION_STREAM_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_STREAM_CONNECTED, connected)
                putExtra(EXTRA_STREAM_STATE, state)
                putExtra(EXTRA_STREAM_SUMMARY, summary)
            }
        )
    }

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

private data class AudioAnalysis(
    val capturedAtMs: Long,
    val agitated: Boolean,
    val riskLevel: String,
    val loudVoice: Boolean,
    val speechDetected: Boolean,
    val cursingDetected: Boolean?,
    val flaggedPhrase: String?,
    val transcript: String?,
    val transcriptConfidence: Float?,
    val recognitionMode: String,
    val summary: String,
    val rmsEnergy: Double,
    val peakAmplitude: Double,
    val zeroCrossingRate: Double,
    val speechRatio: Double,
    val frameEnergyVariance: Double,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("captured_at", capturedAtMs)
            .put("agitated", agitated)
            .put("risk_level", riskLevel)
            .put("loud_voice", loudVoice)
            .put("speech_detected", speechDetected)
            .put("cursing_detected", cursingDetected ?: JSONObject.NULL)
            .put("flagged_phrase", flaggedPhrase ?: JSONObject.NULL)
            .put("transcript", transcript ?: JSONObject.NULL)
            .put("transcript_confidence", transcriptConfidence ?: JSONObject.NULL)
            .put("recognition_mode", recognitionMode)
            .put("summary", summary)
            .put("features", JSONObject()
                .put("rms_energy", rmsEnergy)
                .put("peak_amplitude", peakAmplitude)
                .put("zero_crossing_rate", zeroCrossingRate)
                .put("speech_ratio", speechRatio)
                .put("frame_energy_variance", frameEnergyVariance))
}

private data class RecognitionOutcome(
    val transcript: String?,
    val confidence: Float?,
    val audioBuffer: ShortArray,
    val mode: String,
)
