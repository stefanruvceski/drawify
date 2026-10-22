package com.drawify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hint: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var lockButton: ImageButton
    private lateinit var visibilityButton: ImageButton
    private lateinit var filterButton: MaterialButton
    private lateinit var flashButton: MaterialButton

    private val prefs by lazy { getSharedPreferences("drawify", MODE_PRIVATE) }

    private var camera: Camera? = null
    private var torchOn = false
    private var cameraDenied = false

    private var source: Bitmap? = null
    private var filter = ImageFilter.ORIGINAL
    private var filterJob: Job? = null

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
        opacitySeek = findViewById(R.id.opacitySeek)
        opacityValue = findViewById(R.id.opacityValue)
        lockButton = findViewById(R.id.lockButton)
        visibilityButton = findViewById(R.id.visibilityButton)
        filterButton = findViewById(R.id.filterButton)
        flashButton = findViewById(R.id.flashButton)

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

    override fun onStop() {
        super.onStop()
        // The camera closes in the background, which switches the torch off.
        torchOn = false
        flashButton.setIconResource(R.drawable.ic_flash_off)
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

        findViewById<View>(R.id.flipButton).setOnClickListener { overlay.flipHorizontal() }
        findViewById<View>(R.id.resetButton).setOnClickListener { overlay.resetPosition() }

        flashButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            flashButton.setIconResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
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
                torchOn = false
                flashButton.setIconResource(R.drawable.ic_flash_off)
                flashButton.isEnabled = cam.cameraInfo.hasFlashUnit()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
