package com.eightball.pro.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

class LineView(context: Context) : View(context) {

    data class PathData(
        val points: List<PointF>,
        val color: Int,
        val isMainPath: Boolean,
        val isRecommended: Boolean = false,
        val confidence: Int = 100
    )

    data class PredictedBallMovement(
        val currentPosition: PointF,
        val predictedPosition: PointF,
        val ballType: String,
        val willPocket: Boolean = false,
        val pocketPosition: PointF? = null
    )

    private var paths = mutableListOf<PathData>()
    private var predictedMovements = mutableListOf<PredictedBallMovement>()
    private var realTimeCuePath = mutableListOf<PointF>()
    private var realTimeTargetPath = mutableListOf<PointF>()
    private var realTimePredictions = mutableListOf<PredictedBallMovement>()
    private var realTimeDirection: PointF? = null
    private var currentPower = 0
    private var isShowingRealTime = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64B5F6")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val predictionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3364B5F6")
        style = Paint.Style.FILL
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pocketFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF4444")
        style = Paint.Style.FILL
    }

    fun drawPaths(pathsData: List<PathData>) {
        paths.clear()
        paths.addAll(pathsData)
        invalidate()
    }

    fun drawRealTimeWithPredictions(
        cueBall: PointF,
        targetBall: PointF,
        direction: PointF,
        predictions: List<PredictedBallMovement>,
        power: Int
    ) {
        isShowingRealTime = true
        realTimeDirection = direction
        // تم الحل هنا: إضافة .toMutableList()
        realTimeCuePath = listOf(cueBall, targetBall).toMutableList()
        realTimeTargetPath = listOf(targetBall, findNearestPocketInDirection(targetBall, direction)).toMutableList()
        realTimePredictions.clear()
        realTimePredictions.addAll(predictions)
        currentPower = power
        invalidate()
    }

    fun clearRealTimeDrawings() {
        if (isShowingRealTime) {
            isShowingRealTime = false
            realTimeCuePath.clear()
            realTimeTargetPath.clear()
            realTimePredictions.clear()
            realTimeDirection = null
            invalidate()
        }
    }

    // ... بقية دوال الرسم (onDraw, drawPath, إلخ) تظل كما هي ...

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShowingRealTime) return

        realTimeDirection?.let { dir ->
            val cueBall = realTimeCuePath.firstOrNull() ?: return@let
            val lineEnd = PointF(cueBall.x + dir.x * 400f, cueBall.y + dir.y * 400f)
            val helperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#44FFFFFF")
                strokeWidth = 2f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(15f, 20f), 0f)
            }
            canvas.drawLine(cueBall.x, cueBall.y, lineEnd.x, lineEnd.y, helperPaint)
        }

        if (realTimeCuePath.size >= 2) {
            drawPath(canvas, realTimeCuePath, Color.parseColor("#00E676"), 6f)
            drawArrow(canvas, realTimeCuePath[0], realTimeCuePath[1], Color.parseColor("#00E676"))
            drawPowerText(canvas, realTimeCuePath[0], currentPower)
        }

        if (realTimeTargetPath.size >= 2) {
            drawPath(canvas, realTimeTargetPath, Color.parseColor("#FFEA00"), 5f)
            drawArrow(canvas, realTimeTargetPath[0], realTimeTargetPath[1], Color.parseColor("#FFEA00"))
        }
        drawPredictedBalls(canvas)
    }

    private fun drawPath(canvas: Canvas, points: List<PointF>, color: Int, width: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
        }
        for (i in 0 until points.size - 1) {
            canvas.drawLine(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y, paint)
        }
    }

    private fun drawArrow(canvas: Canvas, start: PointF, end: PointF, color: Int) {
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowSize = 22f
        val arrowX1 = end.x - arrowSize * cos(angle - Math.PI / 6).toFloat()
        val arrowY1 = end.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
        val arrowX2 = end.x - arrowSize * cos(angle + Math.PI / 6).toFloat()
        val arrowY2 = end.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
        val path = Path().apply {
            moveTo(end.x, end.y); lineTo(arrowX1, arrowY1); lineTo(arrowX2, arrowY2); close()
        }
        arrowPaint.color = color
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawPowerText(canvas: Canvas, position: PointF, power: Int) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
        val powerText = "${power}%"
        val textWidth = textPaint.measureText(powerText)
        canvas.drawRoundRect(position.x - 40f, position.y - 50f, position.x + textWidth - 20f, position.y - 15f, 15f, 15f, bgPaint)
        canvas.drawText(powerText, position.x - 30f, position.y - 25f, textPaint)
    }

    private fun drawPredictedBalls(canvas: Canvas) {
        for (movement in realTimePredictions) {
            val current = movement.currentPosition
            val predicted = movement.predictedPosition
            drawPredictionArrow(canvas, current, predicted)
            val radius = 12f
            val paint = if (movement.willPocket) pocketPaint else predictionPaint
            val fill = if (movement.willPocket) pocketFill else predictionFill
            canvas.drawCircle(predicted.x, predicted.y, radius, fill)
            canvas.drawCircle(predicted.x, predicted.y, radius, paint)
            if (movement.willPocket) drawPocketMark(canvas, predicted.x, predicted.y)
        }
    }

    private fun drawPredictionArrow(canvas: Canvas, start: PointF, end: PointF) {
        canvas.drawLine(start.x, start.y, end.x, end.y, predictionPaint)
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowSize = 12f
        val arrowX1 = end.x - arrowSize * cos(angle - Math.PI / 6).toFloat()
        val arrowY1 = end.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
        val arrowX2 = end.x - arrowSize * cos(angle + Math.PI / 6).toFloat()
        val arrowY2 = end.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
        val path = Path().apply {
            moveTo(end.x, end.y); lineTo(arrowX1, arrowY1); lineTo(arrowX2, arrowY2); close()
        }
        canvas.drawPath(path, predictionPaint)
    }

    private fun drawPocketMark(canvas: Canvas, x: Float, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE }
        canvas.drawLine(x - 8f, y - 8f, x + 8f, y + 8f, paint)
        canvas.drawLine(x + 8f, y - 8f, x - 8f, y + 8f, paint)
    }

    private fun findNearestPocketInDirection(point: PointF, direction: PointF): PointF {
        val pockets = listOf(PointF(100f, 100f), PointF(540f, 80f), PointF(980f, 100f), PointF(100f, 1700f), PointF(540f, 1720f), PointF(980f, 1700f))
        var bestPocket = pockets[0]
        var bestScore = Float.NEGATIVE_INFINITY
        for (pocket in pockets) {
            val dx = pocket.x - point.x; val dy = pocket.y - point.y
            val distance = sqrt(dx * dx + dy * dy)
            val score = (direction.x * (dx/distance) + direction.y * (dy/distance)) - (distance / 1000f)
            if (score > bestScore) { bestScore = score; bestPocket = pocket }
        }
        return bestPocket
    }
}
