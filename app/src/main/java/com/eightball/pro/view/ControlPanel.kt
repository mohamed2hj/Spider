package com.eightball.pro.view

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.eightball.pro.AboutActivity
import kotlin.random.Random

class ControlPanel(context: Context) : View(context) {

    interface ControlCallback {
        fun onPlayPressed()
        fun onStopPressed()
        fun onClosePressed()
    }

    private var callback: ControlCallback? = null

    private val buttonSize = 200
    private val buttonMargin = 25
    private val buttonRadius = 35f

    private var currentAlpha = 60
    private var scaleFactor = 1f

    private lateinit var playGradient: LinearGradient
    private lateinit var stopGradient: LinearGradient
    private lateinit var closeGradient: LinearGradient

    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.FILL
    }

    private var screenWidth = 0
    private var screenHeight = 0
    private var playRect: RectF? = null
    private var stopRect: RectF? = null
    private var closeRect: RectF? = null
    private var infoRect: RectF? = null

    private var isProcessing = false
    private val handler = Handler(Looper.getMainLooper())

    private var pulseAnimator: ValueAnimator? = null
    private var pulseValue = 0f

    init {
        setupGradients()
        startPulseAnimation()
    }

    private fun setupGradients() {
        playGradient = LinearGradient(0f, 0f, 0f, buttonSize.toFloat(),
            intArrayOf(Color.parseColor("#FF4081"), Color.parseColor("#E91E63")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        stopGradient = LinearGradient(0f, 0f, 0f, buttonSize.toFloat(),
            intArrayOf(Color.parseColor("#FF9800"), Color.parseColor("#F57C00")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        closeGradient = LinearGradient(0f, 0f, 0f, buttonSize.toFloat(),
            intArrayOf(Color.parseColor("#F44336"), Color.parseColor("#D32F2F")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setCallback(callback: ControlCallback) { this.callback = callback }

    fun setButtonsAlpha(alpha: Int) {
        currentAlpha = alpha
        invalidate()
    }

    fun highlightButtons() {
        setButtonsAlpha(255)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ setButtonsAlpha(60) }, 2000)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenWidth = w
        screenHeight = h

        val startX = screenWidth - (buttonSize * 3 + buttonMargin * 2).toFloat()
        val y = screenHeight - buttonSize - buttonMargin - 100

        playRect = RectF(startX, y, startX + buttonSize, y + buttonSize)
        stopRect = RectF(startX + buttonSize + buttonMargin, y,
            startX + buttonSize * 2 + buttonMargin, y + buttonSize)
        closeRect = RectF(startX + (buttonSize + buttonMargin) * 2, y,
            startX + buttonSize * 3 + buttonMargin * 2, y + buttonSize)

        val infoX = buttonMargin.toFloat()
        val infoY = screenHeight - 60 - buttonMargin - 100
        infoRect = RectF(infoX, infoY, infoX + 60, infoY + 60)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawGlassBackground(canvas)

        drawButton(canvas, playRect, playGradient, "▶", playPaint)
        drawButton(canvas, stopRect, stopGradient, "■", stopPaint)
        drawButton(canvas, closeRect, closeGradient, "✕", closePaint)

        infoRect?.let { rect ->
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            canvas.drawRoundRect(rect, 30f, 30f, infoPaint)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 32f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            }
            canvas.drawText("i", centerX, centerY + 12, textPaint)
        }
    }

    private fun drawGlassBackground(canvas: Canvas) {
        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.FILL
        }
        val bgRect = RectF(
            (playRect?.left ?: 0f) - 20,
            (playRect?.top ?: 0f) - 20,
            (closeRect?.right ?: 0f) + 20,
            (playRect?.bottom ?: 0f) + 60
        )
        canvas.drawRoundRect(bgRect, 50f, 50f, glassPaint)
    }

    private fun drawButton(canvas: Canvas, rect: RectF?, gradient: LinearGradient, icon: String, paint: Paint) {
        if (rect == null) return

        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = rect.width() / 2

        val pulseRadius = radius + 8 * (0.5f + pulseValue * 0.3f)
        glowPaint.color = when (icon) {
            "▶" -> Color.parseColor("#FF4081")
            "■" -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
        glowPaint.alpha = (100 - pulseValue * 50).toInt()
        canvas.drawCircle(cx, cy, pulseRadius, glowPaint)

        canvas.drawCircle(cx, cy + 6, radius, shadowPaint)

        gradient.setLocalMatrix(Matrix().apply { setTranslate(rect.left, rect.top) })
        paint.shader = gradient
        paint.alpha = currentAlpha
        canvas.drawCircle(cx, cy, radius, paint)

        val currentScale = scaleFactor
        canvas.save()
        canvas.scale(currentScale, currentScale, cx, cy)

        when (icon) {
            "▶" -> drawPlayIcon(canvas, cx, cy)
            "■" -> drawStopIcon(canvas, cx, cy)
            "✕" -> drawCloseIcon(canvas, cx, cy)
        }

        textPaint.alpha = currentAlpha
        val label = when (icon) {
            "▶" -> "تشغيل"
            "■" -> "إيقاف"
            else -> "إغلاق"
        }
        canvas.drawText(label, cx, rect.bottom + 40, textPaint)

        canvas.restore()
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float) {
        val size = 45f
        val path = Path().apply {
            moveTo(cx - size/2, cy - size/1.8f)
            lineTo(cx + size/1.8f, cy)
            lineTo(cx - size/2, cy + size/1.8f)
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawStopIcon(canvas: Canvas, cx: Float, cy: Float) {
        val size = 35f
        canvas.drawRect(cx - size/2, cy - size/2, cx + size/2, cy + size/2, iconPaint)
    }

    private fun drawCloseIcon(canvas: Canvas, cx: Float, cy: Float) {
        val size = 35f
        canvas.drawLine(cx - size/2, cy - size/2, cx + size/2, cy + size/2, iconPaint)
        canvas.drawLine(cx + size/2, cy - size/2, cx - size/2, cy + size/2, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                scaleFactor = 0.9f
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                scaleFactor = 1f
                invalidate()

                highlightButtons()
                val x = event.x
                val y = event.y

                when {
                    playRect?.contains(x, y) == true && !isProcessing -> {
                        isProcessing = true
                        callback?.onPlayPressed()
                        handler.postDelayed({ isProcessing = false }, 1000)
                    }
                    stopRect?.contains(x, y) == true -> callback?.onStopPressed()
                    closeRect?.contains(x, y) == true -> callback?.onClosePressed()
                    infoRect?.contains(x, y) == true -> {
                        val intent = Intent(context, AboutActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
