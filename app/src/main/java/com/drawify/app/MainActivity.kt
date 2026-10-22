package com.drawify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hint: TextView
    private lateinit var tiltLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var lockButton: ImageButton
    private lateinit var visibilityButton: ImageButton
    private lateinit var flashButton: ImageButton
    private lateinit var filterButton: MaterialButton
    private lateinit var tiltButton: MaterialButton
    private lateinit var cornersButton: MaterialButton

    private val prefs by lazy { getSharedPreferences("drawify", MODE_PRIVATE) }

    private var camera: Camera? = null
    private var torchOn = false
    private var cameraDenied = false

    private var source: Bitmap? = null
    private var filter = ImageFilter.ORIGINAL
    private var filterJob: Job? = null

    // Tilt correction
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var tiltOn = false
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var sensorFocalMm = 0f
    private var sensorLongSideMm = 0f
    private val tiltMatrix = Matrix()

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadImage(uri)
        }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraDenied = !granted
            if (granted) startCamera()
            updateHint()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        hint = findViewById(R.id.hint)
        tiltLabel = findViewById(R.id.tiltLabel)
        opacitySeek = findViewById(R.id.opacitySeek)
        opacityValue = findViewById(R.id.opacityValue)
        lockButton = findViewById(R.id.lockButton)
        visibilityButton = findViewById(R.id.visibilityButton)
        flashButton = findViewById(R.id.flashButton)
        filterButton = findViewById(R.id.filterButton)
        tiltButton = findViewById(R.id.tiltButton)
        cornersButton = findViewById(R.id.cornersButton)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        applyInsets()
        setupControls()

        if (!handleShareIntent(intent)) {
            lifecycleScope.launch {
                val last = withContext(Dispatchers.IO) { BitmapLoader.loadLast(this@MainActivity) }
                if (last != null && source == null) setSource(last)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        updateHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (tiltOn) registerSensor()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        // The camera closes in the background, which switches the torch off.
        torchOn = false
        flashButton.setImageResource(R.drawable.ic_flash_off)
    }

    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        val bottomPanel = findViewById<View>(R.id.bottomPanel)
        val topPad = topBar.paddingTop
        val bottomPad = bottomPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = topPad + bars.top)
            bottomPanel.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }
    }

    private fun setupControls() {
        val savedOpacity = prefs.getInt("opacity", 50)
        opacitySeek.progress = savedOpacity
        setOpacity(savedOpacity)
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) =
                setOpacity(progress)

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.edit().putInt("opacity", seekBar.progress).apply()
            }
        })

        filter = ImageFilter.entries.getOrElse(prefs.getInt("filter", 0)) { ImageFilter.ORIGINAL }
        filterButton.setText(filter.labelRes)

        findViewById<View>(R.id.pickButton).setOnClickListener { openPicker() }
        hint.setOnClickListener {
            if (cameraDenied) requestCamera.launch(Manifest.permission.CAMERA) else openPicker()
        }

        filterButton.setOnClickListener {
            filter = ImageFilter.entries[(filter.ordinal + 1) % ImageFilter.entries.size]
            prefs.edit().putInt("filter", filter.ordinal).apply()
            filterButton.setText(filter.labelRes)
            applyFilter(fit = false)
        }

        tiltButton.setOnClickListener { setTilt(!tiltOn) }
        cornersButton.setOnClickListener { setCornerMode(!overlay.cornerMode) }

        findViewById<View>(R.id.flipButton).setOnClickListener { overlay.flipHorizontal() }
        findViewById<View>(R.id.resetButton).setOnClickListener {
            setCornerMode(false)
            if (tiltOn) updateTiltPerspective() else overlay.clearPerspective()
            overlay.resetPosition()
        }

        flashButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            flashButton.setImageResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        lockButton.setOnClickListener {
            overlay.locked = !overlay.locked
            lockButton.setImageResource(if (overlay.locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
            Toast.makeText(
                this,
                if (overlay.locked) R.string.locked else R.string.unlocked,
                Toast.LENGTH_SHORT,
            ).show()
        }

        visibilityButton.setOnClickListener {
            val show = overlay.visibility != View.VISIBLE
            overlay.visibility = if (show) View.VISIBLE else View.INVISIBLE
            visibilityButton.setImageResource(if (show) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        }
    }

    private fun setActive(button: MaterialButton, active: Boolean) {
        val color = if (active) getColor(R.color.accent) else getColor(android.R.color.white)
        button.iconTint = ColorStateList.valueOf(color)
        button.setTextColor(color)
    }

    /** Turns automatic tilt correction (from the gravity sensor) on or off. */
    private fun setTilt(on: Boolean) {
        if (on && gravitySensor == null) {
            Toast.makeText(this, R.string.tilt_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        tiltOn = on
        setActive(tiltButton, on)
        if (on) {
            setCornerMode(false)
            hasGravity = false
            registerSensor()
            Toast.makeText(this, R.string.tilt_on, Toast.LENGTH_LONG).show()
        } else {
            sensorManager.unregisterListener(this)
            tiltLabel.visibility = View.GONE
            overlay.clearPerspective()
        }
        // The old placement was made under a different perspective, so start fresh.
        overlay.resetPosition()
    }

    /** Corner mode: drag the image corners by hand. Stops tilt tracking but keeps its current shape. */
    private fun setCornerMode(on: Boolean) {
        if (on && !overlay.hasImage) return
        if (on && tiltOn) {
            tiltOn = false
            setActive(tiltButton, false)
            sensorManager.unregisterListener(this)
            tiltLabel.visibility = View.GONE
        }
        overlay.cornerMode = on
        setActive(cornersButton, overlay.cornerMode)
        if (overlay.cornerMode) {
            overlay.locked = false
            lockButton.setImageResource(R.drawable.ic_lock_open)
            Toast.makeText(this, R.string.corners_on, Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerSensor() {
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // While locked the image must stay exactly where it is, even if the phone wobbles.
        if (!tiltOn || overlay.locked) return
        if (!hasGravity) {
            event.values.copyInto(gravity, 0, 0, 3)
            hasGravity = true
        } else {
            // Low-pass filter to smooth out hand shake.
            for (i in 0 until 3) gravity[i] += SMOOTHING * (event.values[i] - gravity[i])
        }
        updateTiltPerspective()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun updateTiltPerspective() {
        if (!hasGravity) return
        val w = overlay.width
        val h = overlay.height
        if (w == 0 || h == 0) return
        val degrees = TiltPerspective.tiltDegrees(gravity).roundToInt()
        tiltLabel.visibility = View.VISIBLE
        if (TiltPerspective.compute(gravity, w, h, focalPx(w, h), tiltMatrix)) {
            tiltLabel.text = getString(R.string.tilt_angle, degrees)
            overlay.setPerspective(tiltMatrix)
        } else {
            tiltLabel.text = getString(R.string.tilt_too_steep, degrees)
        }
    }

    /** Camera focal length in screen pixels, for a 4:3 preview filling the view (FILL_CENTER). */
    private fun focalPx(viewW: Int, viewH: Int): Float {
        val longView = max(viewH.toFloat(), viewW * 4f / 3f)
        return if (sensorFocalMm > 0f && sensorLongSideMm > 0f) {
            sensorFocalMm / sensorLongSideMm * longView
        } else {
            // Typical phone main camera: ~70° field of view across the long side.
            longView / 2f / tan(Math.toRadians(35.0)).toFloat()
        }
    }

    private fun setOpacity(percent: Int) {
        overlay.opacity = percent / 100f
        opacityValue.text = "$percent%"
    }

    private fun openPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun handleShareIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return false
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return false
        loadImage(uri)
        return true
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    BitmapLoader.load(this@MainActivity, uri).also {
                        BitmapLoader.saveLast(this@MainActivity, it)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp == null) {
                Toast.makeText(this@MainActivity, R.string.image_error, Toast.LENGTH_LONG).show()
            } else {
                setCornerMode(false)
                if (!tiltOn) overlay.clearPerspective()
                setSource(bmp)
            }
        }
    }

    private fun setSource(bmp: Bitmap) {
        source = bmp
        applyFilter(fit = true)
    }

    private fun applyFilter(fit: Boolean) {
        val src = source ?: return
        val selected = filter
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) { ImageFilters.apply(src, selected) }
            overlay.setBitmap(out, fit)
            updateHint()
        }
    }

    private fun updateHint() {
        when {
            cameraDenied -> {
                hint.setText(R.string.no_camera_permission)
                hint.visibility = View.VISIBLE
            }
            !overlay.hasImage -> {
                hint.setText(R.string.empty_hint)
                hint.visibility = View.VISIBLE
            }
            else -> hint.visibility = View.GONE
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                camera = cam
                readLensInfo(cam)
                torchOn = false
                flashButton.setImageResource(R.drawable.ic_flash_off)
                flashButton.isEnabled = cam.cameraInfo.hasFlashUnit()
                flashButton.alpha = if (flashButton.isEnabled) 1f else 0.4f
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readLensInfo(cam: Camera) {
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && focal.isNotEmpty() && size != null) {
                sensorFocalMm = focal[0]
                sensorLongSideMm = max(size.width, size.height)
            }
        } catch (e: Exception) {
            // Fall back to a typical field of view in focalPx().
        }
    }

    private companion object {
        const val SMOOTHING = 0.08f
    }
}
