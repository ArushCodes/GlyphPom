package com.closenheimer.glyphpom

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvTimerDisplay: TextView
    private lateinit var tvModeDisplay: TextView
    private lateinit var tvHintDisplay: TextView
    private lateinit var tvCycleDisplay: TextView
    private lateinit var tvDailyStats: TextView
    private lateinit var settingsOverlay: ScrollView
    private lateinit var btnMainAction: Button
    private lateinit var btnStop: Button

    private lateinit var swFlip: CheckBox
    private lateinit var swGlitch: CheckBox
    private lateinit var swFlow: CheckBox
    private lateinit var swPeek: CheckBox
    private lateinit var rbZen: RadioButton

    private val PREFS_NAME = "PomodoroPrefs"
    private var isStopConfirming = false
    private val resetHandler = Handler(Looper.getMainLooper())

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val timeString = intent?.getStringExtra("TIME_STRING") ?: "00:00"
            val mode = intent?.getStringExtra("MODE") ?: "READY"
            val currentCycle = intent?.getIntExtra("CURRENT_CYCLE", 1) ?: 1
            val totalCycles = intent?.getIntExtra("TOTAL_CYCLES", 0) ?: 0

            tvTimerDisplay.text = timeString
            val totalCycleStr = if (totalCycles == 0) "∞" else totalCycles.toString()
            tvCycleDisplay.text = "CYCLE: $currentCycle / $totalCycleStr"
            tvModeDisplay.text = mode

            updateDynamicHint(mode)
            updateButtonUI(mode)
        }
    }

    private fun updateDynamicHint(mode: String) {
        val hints = mutableListOf<String>()
        if (swGlitch.isChecked) hints.add("GLITCH")
        if (swFlow.isChecked) hints.add("FLOW")
        if (swPeek.isChecked) hints.add("PEEK")
        if (rbZen.isChecked) hints.add("ZEN")

        val activeMods = if (hints.isNotEmpty()) hints.joinToString(" | ") + " ACTIVE" else "STANDARD MODE"

        when (mode) {
            "REST" -> {
                tvModeDisplay.setTextColor(Color.parseColor("#4CAF50"))
                tvHintDisplay.text = "RELAX ($activeMods)"
            }
            "FLOWING" -> {
                tvModeDisplay.setTextColor(Color.parseColor("#4CAF50"))
                tvHintDisplay.text = if (swFlip.isChecked) "PICK UP TO PAUSE" else "FOCUS MAINTAINED"
            }
            "WAITING" -> {
                tvModeDisplay.setTextColor(Color.parseColor("#EA1111"))
                tvHintDisplay.text = "PLACE PHONE FACE DOWN TO START"
            }
            "PAUSED" -> {
                tvModeDisplay.setTextColor(Color.parseColor("#EA1111"))
                tvHintDisplay.text = if (swFlip.isChecked) "FLIP FACE DOWN TO RESUME" else ""
            }
            "WORK", "FOCUS", "PREPARING", "TRANSITION" -> {
                tvModeDisplay.setTextColor(Color.WHITE)
                tvHintDisplay.text = if (swFlip.isChecked) "PICK UP TO PAUSE" else ""
            }
            else -> {
                tvModeDisplay.setTextColor(Color.WHITE)
                if (swGlitch.isChecked) {
                    tvHintDisplay.text = "HARDCORE: DO NOT PICK UP PHONE"
                } else {
                    tvHintDisplay.text = activeMods
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvHintDisplay = findViewById(R.id.tvHintDisplay)
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay)
        tvModeDisplay = findViewById(R.id.tvModeDisplay)
        tvCycleDisplay = findViewById(R.id.tvCycleDisplay)
        tvDailyStats = findViewById(R.id.tvDailyStats)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        btnMainAction = findViewById(R.id.btnMainAction)
        btnStop = findViewById(R.id.btnStop)

        val btnSettings = findViewById<ImageView>(R.id.btnSettings)
        val btnCloseSettings = findViewById<Button>(R.id.btnCloseSettings)
        val mainRoot = findViewById<FrameLayout>(R.id.mainRoot)

        val etWork = findViewById<EditText>(R.id.etWorkMinutes)
        val etRest = findViewById<EditText>(R.id.etRestMinutes)
        val sbCycles = findViewById<SeekBar>(R.id.sbCycles)
        val sbReady = findViewById<SeekBar>(R.id.sbReadyTime)
        val tvCycleLabel = findViewById<TextView>(R.id.tvCycleLabel)
        val tvReadyLabel = findViewById<TextView>(R.id.tvReadyLabel)

        swFlip = findViewById(R.id.swFlipToPause)
        val swSound = findViewById<CheckBox>(R.id.swSound)
        swGlitch = findViewById(R.id.swGlitchMode)
        swFlow = findViewById(R.id.swFlowState)
        swPeek = findViewById(R.id.swPeekMode)
        rbZen = findViewById(R.id.rbZen)
        val etGlitchGrace = findViewById<EditText>(R.id.etGlitchGrace)
        val etPickupLimit = findViewById<EditText>(R.id.etPickupLimit)

        val rgRestVisuals = findViewById<RadioGroup>(R.id.rgRestVisuals)

        mainRoot.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            clearActiveFocus()
        }

        val editorListener = TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                clearActiveFocus()
                true
            } else false
        }
        etWork.setOnEditorActionListener(editorListener)
        etRest.setOnEditorActionListener(editorListener)
        etGlitchGrace.setOnEditorActionListener(editorListener)
        etPickupLimit.setOnEditorActionListener(editorListener)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        etWork.setText(prefs.getString("workTime", "25"))
        etRest.setText(prefs.getString("restTime", "5"))
        sbCycles.progress = prefs.getInt("cycleProgress", 3)
        sbReady.progress = prefs.getInt("readyProgress", 0)
        swSound.isChecked = prefs.getBoolean("soundEnabled", true)
        swFlow.isChecked = prefs.getBoolean("flowEnabled", false)
        swPeek.isChecked = prefs.getBoolean("peekEnabled", false)
        etGlitchGrace.setText(prefs.getInt("glitchGrace", 5).toString())
        etPickupLimit.setText(prefs.getInt("pickupLimit", 3).toString())

        val restBehavior = prefs.getInt("restBehavior", 2)
        if (restBehavior == 0) rbZen.isChecked = true
        else if (restBehavior == 1) findViewById<RadioButton>(R.id.rbDeplete).isChecked = true
        else findViewById<RadioButton>(R.id.rbBoth).isChecked = true

        swFlip.isChecked = prefs.getBoolean("flipEnabled", true)
        val savedGlitch = prefs.getBoolean("glitchEnabled", false)

        if (!swFlip.isChecked && savedGlitch) {
            swGlitch.isChecked = false
            prefs.edit().putBoolean("glitchEnabled", false).apply()
        } else {
            swGlitch.isChecked = savedGlitch
        }
        swGlitch.isEnabled = swFlip.isChecked

        tvCycleLabel.text = if (sbCycles.progress == 9) "CYCLES: ∞" else "CYCLES: ${sbCycles.progress + 1}"
        tvReadyLabel.text = "GET READY TIME: ${sbReady.progress + 3} SECS"
        updateDynamicHint("READY")
        updateButtonUI("READY")

        val applyBounce = { view: View ->
            view.setOnTouchListener { v, event ->
                if (v.isEnabled) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    }
                }
                false
            }
        }
        applyBounce(btnMainAction); applyBounce(btnStop); applyBounce(btnCloseSettings)

        val sbListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (f) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (s?.id == R.id.sbCycles) {
                    tvCycleLabel.text = if (p == 9) "CYCLES: ∞" else "CYCLES: ${p + 1}"
                    prefs.edit().putInt("cycleProgress", p).apply()
                }
                if (s?.id == R.id.sbReadyTime) {
                    tvReadyLabel.text = "GET READY TIME: ${p + 3} SECS"
                    prefs.edit().putInt("readyProgress", p).apply()
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        }
        sbCycles.setOnSeekBarChangeListener(sbListener)
        sbReady.setOnSeekBarChangeListener(sbListener)

        swFlip.setOnCheckedChangeListener { v, isChecked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().putBoolean("flipEnabled", isChecked).apply()
            swGlitch.isEnabled = isChecked
            if (!isChecked && swGlitch.isChecked) {
                swGlitch.isChecked = false
                prefs.edit().putBoolean("glitchEnabled", false).apply()
            }
            updateDynamicHint(tvModeDisplay.text.toString())
        }

        swGlitch.setOnCheckedChangeListener { v, isChecked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().putBoolean("glitchEnabled", isChecked).apply()
            updateDynamicHint(tvModeDisplay.text.toString())
        }

        swPeek.setOnCheckedChangeListener { v, isChecked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().putBoolean("peekEnabled", isChecked).apply()
            updateDynamicHint(tvModeDisplay.text.toString())
        }

        rgRestVisuals.setOnCheckedChangeListener { _, checkedId ->
            val behavior = if (checkedId == R.id.rbZen) 0 else if (checkedId == R.id.rbDeplete) 1 else 2
            prefs.edit().putInt("restBehavior", behavior).apply()
            updateDynamicHint(tvModeDisplay.text.toString())
        }

        swSound.setOnCheckedChangeListener { v, isChecked -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); prefs.edit().putBoolean("soundEnabled", isChecked).apply() }
        swFlow.setOnCheckedChangeListener { v, isChecked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().putBoolean("flowEnabled", isChecked).apply()
            updateDynamicHint(tvModeDisplay.text.toString())
        }

        btnMainAction.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            clearActiveFocus()
            val currentState = btnMainAction.text.toString()

            if (currentState == "PAUSE" || currentState == "RESUME") {
                startService(Intent(this, PomodoroService::class.java).apply { action = "ACTION_PAUSE" })
            } else {
                val serviceIntent = Intent(this, PomodoroService::class.java).apply {
                    action = "START_TIMER"
                    putExtra("WORK_DURATION", (etWork.text.toString().toLongOrNull() ?: 25L) * 60000L)
                    putExtra("REST_DURATION", (etRest.text.toString().toLongOrNull() ?: 5L) * 60000L)
                    putExtra("TOTAL_CYCLES", if (sbCycles.progress == 9) 0 else sbCycles.progress + 1)
                    putExtra("FLIP_ENABLED", swFlip.isChecked)
                    putExtra("SOUND_ENABLED", swSound.isChecked)
                    putExtra("GLITCH_ENABLED", swGlitch.isChecked)
                    putExtra("FLOW_ENABLED", swFlow.isChecked)
                    putExtra("PEEK_ENABLED", swPeek.isChecked)
                    val rbMode = if (rbZen.isChecked) 0 else if (findViewById<RadioButton>(R.id.rbDeplete).isChecked) 1 else 2
                    putExtra("REST_BEHAVIOR", rbMode)
                    putExtra("GLITCH_GRACE", etGlitchGrace.text.toString().toIntOrNull() ?: 5)
                    putExtra("PICKUP_LIMIT", etPickupLimit.text.toString().toIntOrNull() ?: 3)
                    putExtra("READY_SECS", sbReady.progress + 3)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

        btnStop.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

            if (!isStopConfirming) {
                isStopConfirming = true
                updateButtonUI(tvModeDisplay.text.toString())
                resetHandler.postDelayed({
                    isStopConfirming = false
                    updateButtonUI(tvModeDisplay.text.toString())
                }, 3000)
            } else {
                resetHandler.removeCallbacksAndMessages(null)
                isStopConfirming = false
                startService(Intent(this, PomodoroService::class.java).apply { action = "STOP_TIMER" })
                updateButtonUI("READY")
            }
        }

        setupInfoClickListeners()

        // 2. Settings Animation Upgrade (Bottom Sheet Slide)
        btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            clearActiveFocus()

            // Start off-screen at the bottom
            settingsOverlay.translationY = mainRoot.height.toFloat()
            settingsOverlay.alpha = 1f
            settingsOverlay.visibility = View.VISIBLE

            // Slide up cleanly
            settingsOverlay.animate()
                .translationY(0f)
                .setDuration(300)
                .start()
        }

        btnCloseSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            prefs.edit().apply {
                putString("workTime", etWork.text.toString())
                putString("restTime", etRest.text.toString())
                putInt("glitchGrace", etGlitchGrace.text.toString().toIntOrNull() ?: 5)
                putInt("pickupLimit", etPickupLimit.text.toString().toIntOrNull() ?: 3)
                apply()
            }

            // Slide back down to hide
            settingsOverlay.animate()
                .translationY(mainRoot.height.toFloat())
                .setDuration(300)
                .withEndAction {
                    settingsOverlay.visibility = View.GONE
                }
                .start()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsOverlay.visibility == View.VISIBLE) {
                    btnCloseSettings.performClick()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        updateDailyStats()
    }

    private fun clearActiveFocus() {
        val view = currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun updateButtonUI(mode: String) {
        if (mode == "READY" || mode == "COMPLETE") {
            btnMainAction.text = "START"
            btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
            btnMainAction.setTextColor(Color.BLACK)

            btnStop.isEnabled = false
            btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#222222")))
            btnStop.setTextColor(Color.parseColor("#555555"))
            btnStop.text = "STOP"
            isStopConfirming = false
        } else {
            btnStop.isEnabled = true

            if (isStopConfirming) {
                btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#880000")))
                btnStop.setTextColor(Color.WHITE)
                btnStop.text = "SURE?"
            } else {
                btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EA1111")))
                btnStop.setTextColor(Color.WHITE)
                btnStop.text = "STOP"
            }

            when (mode) {
                "FOCUS", "REST", "FLOWING", "PREPARING", "TRANSITION", "WORK" -> {
                    btnMainAction.text = "PAUSE"
                    btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#222222")))
                    btnMainAction.setTextColor(Color.WHITE)
                }
                "PAUSED", "WAITING", "GLITCH" -> {
                    btnMainAction.text = "RESUME"
                    btnMainAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
                    btnMainAction.setTextColor(Color.BLACK)
                }
            }
        }
    }

    private fun setupInfoClickListeners() {
        val infoListener = View.OnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (v.id) {
                R.id.infoGlitch -> showNothingInfo("GLITCH MODE", "Requires FLIP TO PAUSE. STRICT: Picking up the phone glitches Glyph lights. Put it back within the limit or the session resets.")
                R.id.infoFlow -> showNothingInfo("FLOW STATE", "MOMENTUM: Focus continues past 00:00 with a calm breathing animation.")
                R.id.infoAudio -> showNothingInfo("PHASE AUDIO", "Tactile sounds trigger only when a work or rest block finishes.")
                R.id.infoFlip -> showNothingInfo("FLIP TO PAUSE", "Signature mechanic: Face-down to run, face-up to pause.")
                R.id.infoPeek -> showNothingInfo("WIGGLE TO PEEK", "Lights remain off during focus. Give the phone a small wiggle to peek at your progress for 5 seconds.")
            }
        }
        findViewById<ImageView>(R.id.infoGlitch).setOnClickListener(infoListener)
        findViewById<ImageView>(R.id.infoFlow).setOnClickListener(infoListener)
        findViewById<ImageView>(R.id.infoAudio).setOnClickListener(infoListener)
        findViewById<ImageView>(R.id.infoFlip).setOnClickListener(infoListener)
        findViewById<ImageView>(R.id.infoPeek).setOnClickListener(infoListener)
    }

    private fun showNothingInfo(title: String, message: String) {
        val view = layoutInflater.inflate(R.layout.dialog_nothing_info, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        val okBtn = view.findViewById<Button>(R.id.dialogButton)
        okBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun updateDailyStats() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mins = prefs.getInt("daily_$today", 0)
        val sessions = prefs.getInt("sessions_$today", 0)
        tvDailyStats.text = "TODAY: $mins MINS | $sessions SESSIONS"
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.closenheimer.glyphpom.TICK")
        ContextCompat.registerReceiver(
            this,
            timerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateDailyStats()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(timerReceiver)
        } catch (e: IllegalArgumentException) {}
    }
}