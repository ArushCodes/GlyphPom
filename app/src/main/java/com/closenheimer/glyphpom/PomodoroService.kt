package com.closenheimer.glyphpom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class PomodoroService : Service(), SensorEventListener {

    private var mGM: GlyphManager? = null
    private var mCallback: GlyphManager.Callback? = null
    private var isGlyphConnected = false

    private var timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var phaseEndTimeMs: Long = 0L

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var vibrator: Vibrator
    private var toneGen: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var workDurationMs: Long = 0L
    private var restDurationMs: Long = 0L
    private var timeRemainingMs: Long = 0L
    private var currentCycle: Int = 1
    private var totalCycles: Int = 0

    private var isFlipToPauseEnabled = true
    private var isSoundEnabled = true
    private var restBehavior = 2
    private var getReadySecs = 3

    private var isGlitchModeEnabled = false
    private var isFlowStateEnabled = false
    private var isPeekModeEnabled = false
    private var isGlitchActive = false
    private var inFlowState = false
    private var glitchPickups = 0
    private var pickupLimit = 3
    private var glitchGraceSecs = 5

    // Peek Tracking to prevent ghost animations
    private var isPeeking = false
    private var lastXAccel = 0f
    private var peekGeneration = 0

    private var isWidgetConfirmingStop = false
    private val widgetResetHandler = Handler(Looper.getMainLooper())

    private var isRestMode = false
    private var isFaceDown = false
    private var timerState = TimerState.IDLE

    // Pulse Sync Variables
    private var lastActiveLeds = -1
    private var pendingRestLeds = -1
    private var breathStartTimeMs = 0L
    private var nextTroughTimeMs = 0L
    private val BREATH_PERIOD = 2000L

    enum class TimerState {
        IDLE, WAITING_FOR_FLIP, GET_READY, RUNNING, PAUSED, ANIMATING, GLITCH
    }

    private val fullGlyphTrack = intArrayOf(
        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32, 33, 34, 35,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19
    )

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_PAUSE" -> {
                    if (timerState == TimerState.RUNNING || timerState == TimerState.GLITCH) forcePause()
                    else if (timerState == TimerState.PAUSED) forceResume()
                }
                "ACTION_SKIP" -> if (timerState == TimerState.RUNNING || timerState == TimerState.PAUSED) {
                    stopPreciseTimer(); triggerPhaseEnd()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphPom::TimerWakeLock")

        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}

        val filter = IntentFilter().apply {
            addAction("ACTION_PAUSE")
            addAction("ACTION_SKIP")
        }
        ContextCompat.registerReceiver(this, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        startForegroundPriority()
        initGlyphHardware()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TIMER" -> {
                if (timerState == TimerState.ANIMATING) return START_NOT_STICKY
                workDurationMs = intent.getLongExtra("WORK_DURATION", 1500000L)
                restDurationMs = intent.getLongExtra("REST_DURATION", 300000L)
                totalCycles = intent.getIntExtra("TOTAL_CYCLES", 0)
                isFlipToPauseEnabled = intent.getBooleanExtra("FLIP_ENABLED", true)
                isSoundEnabled = intent.getBooleanExtra("SOUND_ENABLED", true)
                restBehavior = intent.getIntExtra("REST_BEHAVIOR", 2)
                getReadySecs = intent.getIntExtra("READY_SECS", 3)
                isGlitchModeEnabled = intent.getBooleanExtra("GLITCH_ENABLED", false)
                isFlowStateEnabled = intent.getBooleanExtra("FLOW_ENABLED", false)
                isPeekModeEnabled = intent.getBooleanExtra("PEEK_ENABLED", false)
                glitchGraceSecs = intent.getIntExtra("GLITCH_GRACE", 5)
                pickupLimit = intent.getIntExtra("PICKUP_LIMIT", 3)

                currentCycle = 1
                glitchPickups = 0
                timeRemainingMs = workDurationMs
                isRestMode = false
                inFlowState = false

                cancelPeek()
                lastActiveLeds = -1

                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
                acquireWakeLock()

                if (isFlipToPauseEnabled) {
                    timerState = TimerState.WAITING_FOR_FLIP
                    broadcastUIUpdate("WAITING")
                } else triggerGetReadyPhase()
            }
            "ACTION_PAUSE" -> {
                if (timerState == TimerState.RUNNING || timerState == TimerState.GLITCH) forcePause()
                else if (timerState == TimerState.PAUSED) forceResume()
            }
            "STOP_TIMER" -> {
                stopPreciseTimer()
                isWidgetConfirmingStop = false
                try { mGM?.turnOff() } catch (e: Exception) {}
                sensorManager.unregisterListener(this)
                releaseWakeLock()
                timeRemainingMs = if (isRestMode) restDurationMs else workDurationMs
                timerState = TimerState.IDLE
                inFlowState = false
                isGlitchActive = false
                cancelPeek()
                broadcastUIUpdate("READY")
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(120 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val xAxis = event.values[0]
            val zAxis = event.values[2]

            if (isPeekModeEnabled && timerState == TimerState.RUNNING && !isRestMode && !isPeeking) {
                val deltaX = abs(xAxis - lastXAccel)
                lastXAccel = xAxis
                if (deltaX > 4.5f) triggerPeekSequence()
            }

            if (isFlipToPauseEnabled || isGlitchModeEnabled) {
                val currentlyFaceDown = zAxis < -7.5
                if (currentlyFaceDown != isFaceDown) {
                    isFaceDown = currentlyFaceDown
                    evaluateOrientationState()
                }
            }
        }
    }

    private fun cancelPeek() {
        isPeeking = false
        peekGeneration++ // Invalidates any pending peek runnables
    }

    private fun triggerPeekSequence() {
        isPeeking = true
        peekGeneration++
        val currentGen = peekGeneration
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

        val targetLeds = ceil((timeRemainingMs.toDouble() / workDurationMs.toDouble()) * fullGlyphTrack.size).toInt()
        var currentLed = 0
        val handler = Handler(Looper.getMainLooper())

        val fillRunnable = object : Runnable {
            override fun run() {
                if (currentGen != peekGeneration) return // Ghost kill
                if (currentLed > targetLeds) {
                    handler.postDelayed({ drainPeekSequence(targetLeds, currentGen) }, 5000)
                    return
                }
                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    val offset = fullGlyphTrack.size - currentLed
                    for (i in offset until fullGlyphTrack.size) builder.buildChannel(fullGlyphTrack[i])
                    mGM?.toggle(builder.build())
                } catch(e: Exception) {}
                currentLed++
                handler.postDelayed(this, 15)
            }
        }
        handler.post(fillRunnable)
    }

    private fun drainPeekSequence(startLeds: Int, gen: Int) {
        var currentLed = startLeds
        val handler = Handler(Looper.getMainLooper())

        val drainRunnable = object : Runnable {
            override fun run() {
                if (gen != peekGeneration) return // Ghost kill
                if (currentLed <= 0) {
                    try { mGM?.turnOff() } catch(e: Exception){}
                    isPeeking = false
                    return
                }
                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    val offset = fullGlyphTrack.size - currentLed
                    for (i in offset until fullGlyphTrack.size) builder.buildChannel(fullGlyphTrack[i])
                    mGM?.toggle(builder.build())
                } catch(e: Exception) {}
                currentLed--
                handler.postDelayed(this, 15)
            }
        }
        handler.post(drainRunnable)
    }

    private fun evaluateOrientationState() {
        if (isFaceDown) {
            if (timerState == TimerState.WAITING_FOR_FLIP) triggerGetReadyPhase()
            else if (isGlitchActive) {
                isGlitchActive = false
                timerState = TimerState.RUNNING
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                startTimerExecution()
            } else if (timerState == TimerState.PAUSED && isFlipToPauseEnabled) forceResume()
        } else {
            if (timerState == TimerState.RUNNING) {
                if (isGlitchModeEnabled && !isRestMode) triggerGlitchMode()
                else if (isFlipToPauseEnabled) forcePause()
            } else if (inFlowState) {
                inFlowState = false
                triggerPhaseEnd()
            }
        }
    }

    private fun triggerGlitchMode() {
        glitchPickups++
        if (glitchPickups > pickupLimit) {
            failSession("LIMIT EXCEEDED")
            return
        }
        isGlitchActive = true
        timerState = TimerState.GLITCH
        cancelPeek()
        stopPreciseTimer()
        broadcastUIUpdate("PAUSED")

        var graceLeft = glitchGraceSecs
        timerRunnable = object : Runnable {
            override fun run() {
                if (!isGlitchActive) return
                if (graceLeft <= 0) {
                    failSession("TIMEOUT")
                    return
                }
                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    val shuffled = fullGlyphTrack.toMutableList().apply { shuffle() }
                    for (i in 0..12) builder.buildChannel(shuffled[i])
                    mGM?.toggle(builder.build())
                } catch (e: Exception) {}
                vibrator.vibrate(VibrationEffect.createOneShot(100, 255))
                graceLeft--
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun failSession(reason: String) {
        stopPreciseTimer()
        isGlitchActive = false
        if (isSoundEnabled) toneGen?.startTone(ToneGenerator.TONE_DTMF_D, 1000)
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 100, 500), -1))
        timeRemainingMs = workDurationMs
        timerState = TimerState.WAITING_FOR_FLIP
        broadcastUIUpdate("WAITING")
    }

    private fun forcePause() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        timerState = TimerState.PAUSED
        cancelPeek()
        stopPreciseTimer()
        releaseWakeLock()
        try {
            val phaseDuration = if (isRestMode) restDurationMs else workDurationMs
            val progressPercentage = timeRemainingMs.toDouble() / phaseDuration.toDouble()
            val activeLedsCount = ceil(progressPercentage * fullGlyphTrack.size).toInt()
            updateGlyphHardware(activeLedsCount)
        } catch (e: Exception) {}
        broadcastUIUpdate("PAUSED")
        updateForegroundNotification()
    }

    private fun forceResume() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        timerState = TimerState.RUNNING
        lastActiveLeds = -1
        acquireWakeLock()
        startTimerExecution()
    }

    private fun triggerGetReadyPhase() {
        timerState = TimerState.GET_READY
        var step = 0
        val chargeHandler = Handler(Looper.getMainLooper())
        val chargeRunnable = object : Runnable {
            override fun run() {
                if (step >= fullGlyphTrack.size) return
                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    for (i in 0..step) builder.buildChannel(fullGlyphTrack[i])
                    mGM?.toggle(builder.build())
                } catch (e: Exception) {}
                step++
                chargeHandler.postDelayed(this, 15)
            }
        }
        chargeHandler.post(chargeRunnable)

        var secsLeft = getReadySecs
        timerRunnable = object : Runnable {
            override fun run() {
                if (timerState != TimerState.GET_READY) return
                if (secsLeft > 0) {
                    applyNativeAnimate(fullGlyphTrack.size, 1000)
                    broadcastUIUpdate("PREPARING", String.format(Locale.getDefault(), "00:%02d", secsLeft))
                    secsLeft--
                    timerHandler.postDelayed(this, 1000)
                } else {
                    timerState = TimerState.RUNNING
                    startTimerExecution()
                }
            }
        }
        timerHandler.postDelayed(timerRunnable!!, 600)
    }

    private fun startTimerExecution() {
        stopPreciseTimer()
        val phaseDuration = if (isRestMode) restDurationMs else workDurationMs
        phaseEndTimeMs = System.currentTimeMillis() + timeRemainingMs
        breathStartTimeMs = System.currentTimeMillis()

        timerRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                timeRemainingMs = phaseEndTimeMs - now
                if (timeRemainingMs <= 0) {
                    timeRemainingMs = 0
                    if (!isRestMode && isFlowStateEnabled) triggerFlowState()
                    else triggerPhaseEnd()
                    return
                }
                processHardwareUpdate(phaseDuration)
                timerHandler.postDelayed(this, 50)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun triggerFlowState() {
        inFlowState = true
        broadcastUIUpdate("FLOWING", "00:00")
        applyNativeAnimate(fullGlyphTrack.size, 3000)
    }

    private fun stopPreciseTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
    }

    private fun processHardwareUpdate(phaseDuration: Long) {
        if (timerState != TimerState.ANIMATING && !inFlowState) broadcastUIUpdate(if (isRestMode) "REST" else "FOCUS")
        updateForegroundNotification()
        if (!isGlyphConnected) return

        val progressPercentage = timeRemainingMs.toDouble() / phaseDuration.toDouble()
        val activeLedsCount = ceil(progressPercentage * fullGlyphTrack.size).toInt()

        // 3. Mathematical Pulse Sync Logic
        if (isRestMode && (restBehavior == 0 || restBehavior == 2)) {
            pendingRestLeds = activeLedsCount

            if (lastActiveLeds == -1) {
                // Initialize the sine wave
                lastActiveLeds = pendingRestLeds
                applyNativeAnimate(lastActiveLeds, BREATH_PERIOD.toInt())
                breathStartTimeMs = System.currentTimeMillis()
                nextTroughTimeMs = breathStartTimeMs + (BREATH_PERIOD / 2)
            } else if (System.currentTimeMillis() >= nextTroughTimeMs) {
                // We have exactly hit the absolute dimmest point of the animation.
                // Check if LEDs need to drop, and update them silently while dark.
                if (lastActiveLeds != pendingRestLeds) {
                    lastActiveLeds = pendingRestLeds
                    applyNativeAnimate(lastActiveLeds, BREATH_PERIOD.toInt())
                }
                // Schedule next trough
                nextTroughTimeMs += BREATH_PERIOD
            }
        } else if (activeLedsCount != lastActiveLeds) {
            lastActiveLeds = activeLedsCount

            if (isPeekModeEnabled && !isRestMode) {
                if (!isPeeking) {
                    try { mGM?.turnOff() } catch (e: Exception) {}
                } else if (isPeeking && lastActiveLeds > 0) {
                    updateGlyphHardware(lastActiveLeds)
                }
            } else {
                if (isRestMode) updateGlyphHardware(activeLedsCount)
                else {
                    if (progressPercentage <= 0.15) applyNativeAnimate(activeLedsCount, 800)
                    else updateGlyphHardware(activeLedsCount)
                }
            }
        }
    }

    private fun applyNativeAnimate(ledCount: Int, periodMs: Int) {
        try {
            val builder = mGM?.glyphFrameBuilder ?: return
            if (isRestMode) {
                for (i in 0 until ledCount) builder.buildChannel(fullGlyphTrack[i])
            } else {
                val offset = fullGlyphTrack.size - ledCount
                for (i in offset until fullGlyphTrack.size) builder.buildChannel(fullGlyphTrack[i])
            }
            builder.buildPeriod(periodMs).buildCycles(1000).buildInterval(0)
            mGM?.animate(builder.build())
        } catch (e: Exception) {}
    }

    private fun updateGlyphHardware(activeLeds: Int) {
        try {
            val builder = mGM?.glyphFrameBuilder ?: return
            if (isRestMode) {
                for (i in 0 until activeLeds) builder.buildChannel(fullGlyphTrack[i])
            } else {
                val offset = fullGlyphTrack.size - activeLeds
                for (i in offset until fullGlyphTrack.size) builder.buildChannel(fullGlyphTrack[i])
            }
            mGM?.toggle(builder.build())
        } catch (e: GlyphException) {}
    }

    private fun triggerPhaseEnd() {
        if (!isRestMode) logDailyFocusTime()
        cancelPeek()

        if (!isRestMode) {
            timerState = TimerState.ANIMATING
            broadcastUIUpdate("TRANSITION", "--:--")
            try { mGM?.turnOff() } catch (e: Exception) {}
            if (isSoundEnabled) toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 600)

            playWorkCompleteRotation()
        } else {
            if (totalCycles > 0 && currentCycle >= totalCycles) {
                broadcastUIUpdate("COMPLETE", "00:00")
                if (isSoundEnabled) toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 1000)
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                shutdownProcess()
            } else {
                timerState = TimerState.ANIMATING
                broadcastUIUpdate("TRANSITION", "--:--")
                try { mGM?.turnOff() } catch (e: Exception) {}
                if (isSoundEnabled) toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)

                applyNativeAnimate(fullGlyphTrack.size, 800)
                vibrator.vibrate(VibrationEffect.createOneShot(800, 50))
                Handler(Looper.getMainLooper()).postDelayed({ playRestCompleteSequence() }, 900)
            }
        }
    }

    private fun playWorkCompleteRotation() {
        var step = 0
        val handler = Handler(Looper.getMainLooper())
        val trackSize = fullGlyphTrack.size
        val totalSteps = trackSize * 3

        val runnable = object : Runnable {
            override fun run() {
                if (step >= totalSteps) {
                    try { mGM?.turnOff() } catch (e: Exception) {}
                    isRestMode = true
                    timeRemainingMs = restDurationMs
                    lastActiveLeds = -1
                    timerState = TimerState.RUNNING
                    startTimerExecution()
                    return
                }

                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    for (i in 0..5) {
                        val targetIndex = ((step - i) % trackSize + trackSize) % trackSize
                        builder.buildChannel(fullGlyphTrack[targetIndex])
                    }
                    mGM?.toggle(builder.build())
                } catch (e: Exception) {}

                if (step % 6 == 0) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                step++
                handler.postDelayed(this, 30)
            }
        }
        handler.post(runnable)
    }

    private fun playRestCompleteSequence() {
        var step = 0
        val handler = Handler(Looper.getMainLooper())
        val trackSize = fullGlyphTrack.size
        val runnable = object : Runnable {
            override fun run() {
                if (step >= trackSize) {
                    try { mGM?.turnOff() } catch (e: Exception) {}
                    handleCycleCompletion()
                    return
                }
                try {
                    val builder = mGM?.glyphFrameBuilder ?: return
                    for (i in 0..5) {
                        val targetIndex = ((-step + i) % trackSize + trackSize) % trackSize
                        builder.buildChannel(fullGlyphTrack[targetIndex])
                    }
                    mGM?.toggle(builder.build())
                } catch (e: Exception) {}
                if (step % 6 == 0) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                step++
                handler.postDelayed(this, 35)
            }
        }
        handler.post(runnable)
    }

    private fun handleCycleCompletion() {
        currentCycle++
        isRestMode = false
        timeRemainingMs = workDurationMs
        lastActiveLeds = -1
        if (isFlipToPauseEnabled && !isFaceDown) {
            timerState = TimerState.WAITING_FOR_FLIP
            broadcastUIUpdate("WAITING")
        } else triggerGetReadyPhase()
    }

    private fun logDailyFocusTime() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences("PomodoroPrefs", Context.MODE_PRIVATE)
        val currentMins = prefs.getInt("daily_$today", 0)
        val currentSessions = prefs.getInt("sessions_$today", 0)
        val addedMins = (workDurationMs / 60000).toInt()
        prefs.edit().apply {
            putInt("daily_$today", currentMins + addedMins)
            putInt("sessions_$today", currentSessions + 1)
            apply()
        }
    }

    private fun broadcastUIUpdate(mode: String, overrideTime: String? = null) {
        val minutes = (timeRemainingMs / 1000) / 60
        val seconds = (timeRemainingMs / 1000) % 60
        val timeString = overrideTime ?: String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val intent = Intent("com.closenheimer.glyphpom.TICK").apply {
            setPackage(packageName)
            putExtra("TIME_STRING", timeString)
            putExtra("MODE", mode)
            putExtra("CURRENT_CYCLE", currentCycle)
            putExtra("TOTAL_CYCLES", totalCycles)
        }
        sendBroadcast(intent)
    }

    private fun initGlyphHardware() {
        mCallback = object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                if (Common.is24111()) mGM?.register(Glyph.DEVICE_24111)
                try {
                    mGM?.openSession()
                    isGlyphConnected = true
                } catch (e: GlyphException) {}
            }
            override fun onServiceDisconnected(componentName: ComponentName) {
                mGM?.closeSession()
                isGlyphConnected = false
            }
        }
        mGM = GlyphManager.getInstance(applicationContext)
        mGM?.init(mCallback)
    }

    private fun startForegroundPriority() {
        val channel = NotificationChannel("pomodoro_channel", "Glyph Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        ServiceCompat.startForeground(this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val pauseIntent = PendingIntent.getBroadcast(this, 1, Intent("ACTION_PAUSE").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val skipIntent = PendingIntent.getBroadcast(this, 2, Intent("ACTION_SKIP").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val pauseLabel = if (timerState == TimerState.PAUSED) "Resume" else "Pause"

        val modeStr = if (timerState == TimerState.PAUSED) "Paused" else when {
            isRestMode -> "Resting"
            inFlowState -> "Flowing"
            else -> "Focusing"
        }

        val minutes = (timeRemainingMs / 1000) / 60
        val seconds = (timeRemainingMs / 1000) % 60
        val timeStr = if (inFlowState) "∞" else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        return NotificationCompat.Builder(this, "pomodoro_channel")
            .setContentTitle("GlyphPom")
            .setContentText("$modeStr • $timeStr")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, pauseLabel, pauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Skip", skipIntent)
            .build()
    }

    private fun shutdownProcess() {
        stopPreciseTimer()
        releaseWakeLock()
        try { sensorManager.unregisterListener(this) } catch (e: Exception) {}
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        try { toneGen?.release() } catch (e: Exception) {}
        try { mGM?.turnOff(); mGM?.closeSession() } catch (e: Exception) {}
        try { mGM?.unInit() } catch (e: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        shutdownProcess()
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null
}