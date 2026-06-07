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
import android.view.*
import android.widget.Toast
import com.eightball.pro.MainActivity
import com.eightball.pro.view.ControlPanel
import com.eightball.pro.view.LineView
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.*
import kotlin.math.*

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
    private var shotCounter = 0

    private var cachedBoundaries: FloatArray? = null
    private var cachedPockets: List<PointF>? = null
    private var lastDetectionTime = 0L
    private val CACHE_VALID_DURATION = 5000L

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

    private fun getHumanDelay(): Long = Random().nextInt(250) + 150

    private fun addThinkingDelay() {
        val thinkingTime = Random().nextInt(1700) + 800
        Thread.sleep(thinkingTime)
    }

    private fun getAdaptiveJitter(shotNumber: Int, power: Int): Float {
        val baseJitter = 3f
        val powerFactor = when (power) {
            in 0..30 -> 1.0f
            in 31..70 -> 1.5f
            else -> 2.2f
        }
        val shotFactor = 1f + (shotNumber % 5) * 0.1f
        return baseJitter * powerFactor * shotFactor
    }

    private fun getAngleDeviation(): Double {
        return (Random().nextInt(40) - 20) / 10.0
    }

    private fun addCueBallError(originalPoint: PointF): PointF {
        val errorX = (Random.nextInt(-5, 5)).toFloat()
        val errorY = (Random.nextInt(-5, 5)).toFloat()
        return PointF(originalPoint.x + errorX, originalPoint.y + errorY)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = sqrt(x * x + y * y + z * z.toDouble()).toFloat()

            if (acceleration > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_INTERVAL) {
                    shakeCount = 1
                } else {
                    shakeCount++
                }
                lastShakeTime = now

                if (shakeCount >= REQUIRED_SHAKES) {
                    emergencyStop()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun setupControlPanel() {
        controlPanel = ControlPanel(this).apply {
            setCallback(this@OverlayService)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(controlPanel, params)
    }

    override fun onPlayPressed() {
        if (isAimActive) return
        controlPanel.highlightButtons()
        
        val delay = getHumanDelay()
        Handler(Looper.getMainLooper()).postDelayed({
            performPlayAction()
        }, delay)
    }

    private fun performPlayAction() {
        Toast.makeText(this, "جاري التحليل...", Toast.LENGTH_SHORT).show()
        isAimActive = true
        
        if (mediaProjection == null) {
            requestMediaProjection()
        } else {
            performFullAnalysis()
        }
        
        startRandomFrameUpdater()
    }

    override fun onStopPressed() {
        if (!isAimActive) return
        controlPanel.highlightButtons()
        isAimActive = false
        lineView.clearAll()
        stopFrameUpdater()
        Toast.makeText(this, "تم إيقاف الأسهم", Toast.LENGTH_SHORT).show()
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
                    updateAimLines()
                    val randomDelay = Random.nextInt(12, 50)
                    frameHandler?.postDelayed(this, randomDelay.toLong())
                }
            }
        }
        frameHandler?.post(frameUpdater!!)
    }

    private fun stopFrameUpdater() {
        frameHandler?.removeCallbacks(frameUpdater!!)
        frameHandler = null
        frameUpdater = null
    }

    private fun updateAimLines() {}

    private fun performFullAnalysis() {
        scope.launch {
            try {
                val screenshot = captureScreen()
                if (screenshot == null) {
                    withContext(Dispatchers.Main) { isAimActive = false }
                    return@launch
                }

                var boundaries: FloatArray?
                var pockets: List<PointF>

                if (isCacheValid()) {
                    boundaries = cachedBoundaries
                    pockets = cachedPockets!!
                } else {
                    boundaries = detectTableBoundaries(screenshot)
                    if (boundaries == null) {
                        withContext(Dispatchers.Main) { isAimActive = false }
                        return@launch
                    }
                    pockets = detectPockets(boundaries)
                    saveTableDataToCache(boundaries, pockets)
                }

                var cueBall = detectCueBall(screenshot)
                if (cueBall == null) {
                    withContext(Dispatchers.Main) { isAimActive = false }
                    return@launch
                }
                
                cueBall = addCueBallError(cueBall)

                val targets = detectTargetBalls(screenshot, cueBall)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "تم التحليل", Toast.LENGTH_SHORT).show()
                }

                addThinkingDelay()
                shotCounter++

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isAimActive = false }
            }
        }
    }

    private fun loadCachedTableData() {
        val boundariesStr = prefs.getString("cached_boundaries", null)
        if (boundariesStr != null) {
            val parts = boundariesStr.split(",")
            if (parts.size == 4) {
                cachedBoundaries = floatArrayOf(
                    parts[0].toFloat(), parts[1].toFloat(),
                    parts[2].toFloat(), parts[3].toFloat()
                )
            }
        }
        val pocketsStr = prefs.getString("cached_pockets", null)
        if (pocketsStr != null) {
            val parts = pocketsStr.split(";")
            cachedPockets = parts.map {
                val coords = it.split(",")
                PointF(coords[0].toFloat(), coords[1].toFloat())
            }
        }
        lastDetectionTime = prefs.getLong("last_cache_time", 0L)
    }

    private fun saveTableDataToCache(boundaries: FloatArray, pockets: List<PointF>) {
        prefs.edit().apply {
            putString("cached_boundaries", boundaries.joinToString(","))
            putString("cached_pockets", pockets.joinToString(";") { "${it.x},${it.y}" })
            putLong("last_cache_time", System.currentTimeMillis())
            apply()
        }
        cachedBoundaries = boundaries
        cachedPockets = pockets
        lastDetectionTime = System.currentTimeMillis()
    }

    private fun isCacheValid(): Boolean {
        return cachedBoundaries != null &&
                cachedPockets != null &&
                (System.currentTimeMillis() - lastDetectionTime) < CACHE_VALID_DURATION
    }

    private fun setupLineView() {
        lineView = LineView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(lineView, params)
    }

    private fun setupForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_channel",
                "Spider",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            
            val notification = Notification.Builder(this, "overlay_channel")
                .setContentTitle("Spider")
                .setContentText("جاهز للمساعدة")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setPriority(Notification.PRIORITY_MIN)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("request_projection", true)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            val width = metrics.widthPixels / 2
            val height = metrics.heightPixels / 2
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixels = IntArray(image.width * image.height)
                buffer.rewind()
                buffer.asIntBuffer().get(pixels)
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                image.close()
                return@withContext bitmap
            }
            null
        } catch (e: Exception) { null }
    }

    private fun detectTableBoundaries(bitmap: Bitmap): FloatArray? = floatArrayOf(100f, 400f, 980f, 2000f)
    private fun detectPockets(boundaries: FloatArray): List<PointF> {
        val left = boundaries[0]; val top = boundaries[1]; val right = boundaries[2]; val bottom = boundaries[3]
        val centerX = (left + right) / 2
        return listOf(
            PointF(left + 30, top + 30), PointF(centerX, top + 20), PointF(right - 30, top + 30),
            PointF(left + 30, bottom - 30), PointF(centerX, bottom - 20), PointF(right - 30, bottom - 30)
        )
    }
    private fun detectCueBall(bitmap: Bitmap): PointF? = PointF(540f, 1500f)
    private fun detectTargetBalls(bitmap: Bitmap, cueBall: PointF): List<PointF> = listOf(
        PointF(400f, 1200f), PointF(600f, 1100f), PointF(700f, 1300f)
    )

    private fun emergencyStop() {
        isAimActive = false
        stopFrameUpdater()
        lineView.clearAll()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        windowManager.removeView(controlPanel)
        windowManager.removeView(lineView)
        stopForeground(true)
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
