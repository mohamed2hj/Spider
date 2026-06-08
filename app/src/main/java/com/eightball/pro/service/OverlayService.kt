package com.eightball.pro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Toast
import com.eightball.pro.MainActivity
import com.eightball.pro.view.ControlPanel
import com.eightball.pro.view.LineView
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.*
import kotlin.math.*
import kotlin.random.Random

class OverlayService : Service(), ControlPanel.ControlCallback, SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var controlPanel: ControlPanel
    private lateinit var lineView: LineView
    private lateinit var prefs: SharedPreferences
    private lateinit var sensorManager: SensorManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isAimActive = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var dailySignature = ""
    private var frameHandler: Handler? = null
    private var frameUpdater: Runnable? = null

    private var lastShakeTime = 0L
    private var shakeCount = 0
    private val SHAKE_THRESHOLD = 15f
    private val SHAKE_INTERVAL = 500L
    private val REQUIRED_SHAKES = 2

    companion object {
        private const val NOTIFICATION_ID = 1001
        private var instance: OverlayService? = null
        fun getInstance(): OverlayService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("overlay_cache", MODE_PRIVATE)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }

        generateDailySignature()
        setupControlPanel()
        setupLineView()
        setupForegroundService()
        loadCachedTableData()
    }

    private fun generateDailySignature() {
        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_YEAR)
        val year = calendar.get(Calendar.YEAR)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val seed = "$today-$year-${deviceId?.take(8)}"
        dailySignature = hashString(seed).take(8)
    }

    private fun hashString(input: String): String {
        val bytes = input.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getHumanDelay(): Long = Random.nextInt(250).toLong() + 150L

    private fun addThinkingDelay() {
        val thinkingTime = Random.nextInt(1700) + 800
        Thread.sleep(thinkingTime.toLong())
    }

    private fun addCueBallError(originalPoint: PointF): PointF {
        val errorX = Random.nextInt(-5, 6).toFloat()
        val errorY = Random.nextInt(-5, 6).toFloat()
        return PointF(originalPoint.x + errorX, originalPoint.y + errorY)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val acceleration = sqrt(x * x + y * y + z * z.toDouble()).toFloat()
            if (acceleration > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_INTERVAL) shakeCount = 1 else shakeCount++
                lastShakeTime = now
                if (shakeCount >= REQUIRED_SHAKES) emergencyStop()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun setupControlPanel() {
        controlPanel = ControlPanel(this).apply { setCallback(this@OverlayService) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(controlPanel, params)
    }

    override fun onPlayPressed() {
        if (isAimActive) return
        controlPanel.highlightButtons()
        Handler(Looper.getMainLooper()).postDelayed({ performPlayAction() }, getHumanDelay())
    }

    private fun performPlayAction() {
        Toast.makeText(this, "جاري التحليل...", Toast.LENGTH_SHORT).show()
        isAimActive = true
        if (mediaProjection == null) requestMediaProjection() else performFullAnalysis()
        startRandomFrameUpdater()
    }

    override fun onStopPressed() {
        if (!isAimActive) return
        controlPanel.highlightButtons()
        isAimActive = false
        lineView.clearAll()
        stopFrameUpdater()
    }

    override fun onClosePressed() {
        controlPanel.highlightButtons()
        emergencyStop()
    }

    private fun startRandomFrameUpdater() {
        frameHandler = Handler(Looper.getMainLooper())
        frameUpdater = object : Runnable {
            override fun run() {
                if (isAimActive) {
                    val randomDelay = Random.nextInt(12, 51)
                    frameHandler?.postDelayed(this, randomDelay.toLong())
                }
            }
        }
        frameHandler?.post(frameUpdater!!)
    }

    private fun stopFrameUpdater() {
        frameUpdater?.let { frameHandler?.removeCallbacks(it) }
        frameHandler = null
        frameUpdater = null
    }

    private fun performFullAnalysis() {
        scope.launch {
            try {
                val screenshot = captureScreen() ?: return@launch
                addThinkingDelay()
            } catch (e: Exception) { withContext(Dispatchers.Main) { isAimActive = false } }
        }
    }

    private fun loadCachedTableData() { /*...*/ }

    private fun setupLineView() {
        lineView = LineView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(lineView, params)
    }

    private fun setupForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_channel", "Spider", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, "overlay_channel")
                .setContentTitle("Spider").setContentText("جاهز").setSmallIcon(android.R.drawable.ic_menu_edit).build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("request_projection", true)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun onMediaProjectionGranted(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        performFullAnalysis()
    }

    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels / 2; val height = metrics.heightPixels / 2
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCapture", width, height, metrics.densityDpi, 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
            val image = imageReader?.acquireLatestImage() ?: return@withContext null
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            image.close()
            bitmap
        } catch (e: Exception) { null }
    }

    private fun emergencyStop() {
        isAimActive = false
        stopFrameUpdater()
        windowManager.removeView(controlPanel)
        windowManager.removeView(lineView)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
