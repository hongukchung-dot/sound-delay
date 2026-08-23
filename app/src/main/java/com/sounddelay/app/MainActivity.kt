package com.sounddelay.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    companion object {
        private val OUTPUT_USAGES = intArrayOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )
        private const val PREF_DELAY_SEC = "delay_sec"
        private const val PREF_OUTPUT_INDEX = "output_index"
    }

    private lateinit var slider: Slider
    private lateinit var delayValueText: TextView
    private lateinit var outputSpinner: Spinner
    private lateinit var volumeSlider: Slider
    private lateinit var startStopButton: Button
    private lateinit var statusText: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi(intent?.getStringExtra(AudioDelayService.EXTRA_ERROR))
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRecordPermission()) {
                launchProjectionRequest()
            } else {
                statusText.text = getString(R.string.error_record_permission)
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startDelayService(result.resultCode, data)
            } else {
                statusText.text = getString(R.string.error_projection_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        slider = findViewById(R.id.delay_slider)
        delayValueText = findViewById(R.id.delay_value)
        outputSpinner = findViewById(R.id.output_spinner)
        volumeSlider = findViewById(R.id.volume_slider)
        startStopButton = findViewById(R.id.btn_start_stop)
        statusText = findViewById(R.id.status_text)

        slider.value = prefs.getFloat(PREF_DELAY_SEC, 3.0f).coerceIn(0.5f, 30.0f)
        outputSpinner.setSelection(
            prefs.getInt(PREF_OUTPUT_INDEX, 0).coerceIn(0, OUTPUT_USAGES.lastIndex)
        )

        setupVolumeSlider()
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                runCatching {
                    audioManager.setStreamVolume(currentOutputStream(), value.toInt(), 0)
                }
            }
        }
        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                setupVolumeSlider()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        slider.addOnChangeListener { _, _, _ -> updateDelayLabel() }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                sendDelayToService()
            }
        })

        findViewById<Button>(R.id.btn_minus).setOnClickListener { nudgeDelay(-0.1f) }
        findViewById<Button>(R.id.btn_plus).setOnClickListener { nudgeDelay(+0.1f) }

        startStopButton.setOnClickListener {
            if (AudioDelayService.isRunning) stopDelayService() else startFlow()
        }

        updateDelayLabel()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(AudioDelayService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setupVolumeSlider()
        updateUi(null)
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        prefs.edit()
            .putFloat(PREF_DELAY_SEC, slider.value)
            .putInt(PREF_OUTPUT_INDEX, outputSpinner.selectedItemPosition)
            .apply()
        super.onStop()
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startFlow() {
        val needed = mutableListOf<String>()
        if (!hasRecordPermission()) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            launchProjectionRequest()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun launchProjectionRequest() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startDelayService(resultCode: Int, resultData: Intent) {
        val usageIndex = outputSpinner.selectedItemPosition.coerceIn(0, OUTPUT_USAGES.lastIndex)
        val intent = Intent(this, AudioDelayService::class.java)
            .setAction(AudioDelayService.ACTION_START)
            .putExtra(AudioDelayService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioDelayService.EXTRA_RESULT_DATA, resultData)
            .putExtra(AudioDelayService.EXTRA_DELAY_MS, currentDelayMs())
            .putExtra(AudioDelayService.EXTRA_OUTPUT_USAGE, OUTPUT_USAGES[usageIndex])
        ContextCompat.startForegroundService(this, intent)
        statusText.text = getString(R.string.status_starting)
    }

    private fun stopDelayService() {
        startService(
            Intent(this, AudioDelayService::class.java)
                .setAction(AudioDelayService.ACTION_STOP)
        )
    }

    private fun nudgeDelay(deltaSec: Float) {
        val newValue = (round((slider.value + deltaSec) * 10f) / 10f).coerceIn(0.5f, 30.0f)
        slider.value = newValue
        sendDelayToService()
    }

    private fun sendDelayToService() {
        if (!AudioDelayService.isRunning) return
        startService(
            Intent(this, AudioDelayService::class.java)
                .setAction(AudioDelayService.ACTION_SET_DELAY)
                .putExtra(AudioDelayService.EXTRA_DELAY_MS, currentDelayMs())
        )
    }

    private fun currentDelayMs(): Long = (slider.value * 1_000f).toLong()

    private fun currentOutputStream(): Int {
        val usageIndex = outputSpinner.selectedItemPosition.coerceIn(0, OUTPUT_USAGES.lastIndex)
        return when (OUTPUT_USAGES[usageIndex]) {
            AudioAttributes.USAGE_MEDIA -> AudioManager.STREAM_MUSIC
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> AudioManager.STREAM_ACCESSIBILITY
            else -> AudioManager.STREAM_ALARM
        }
    }

    private fun setupVolumeSlider() {
        val stream = currentOutputStream()
        val min = audioManager.getStreamMinVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= min) return
        volumeSlider.valueFrom = min.toFloat()
        volumeSlider.valueTo = max.toFloat()
        volumeSlider.stepSize = 1f
        volumeSlider.value = audioManager.getStreamVolume(stream).coerceIn(min, max).toFloat()
    }

    private fun updateDelayLabel() {
        delayValueText.text = getString(R.string.delay_value_format, slider.value)
    }

    private fun updateUi(error: String?) {
        val running = AudioDelayService.isRunning
        startStopButton.text = getString(if (running) R.string.stop else R.string.start)
        outputSpinner.isEnabled = !running
        statusText.text = when {
            error != null -> getString(R.string.status_error, error)
            running -> getString(R.string.status_running)
            else -> getString(R.string.status_idle)
        }
    }
}
