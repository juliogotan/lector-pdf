package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/** Muestra una página con zoom (pellizco / doble toque), arrastre y zonas de toque tipo Kindle. */
class PageView(context: Context) : View(context) {

    /** -1 = zona izquierda, 0 = centro, 1 = zona derecha */
    var onTapZone: ((Int) -> Unit)? = null

    /** -1 = página anterior, 1 = página siguiente */
    var onSwipe: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val m = Matrix()
    private val v = FloatArray(9)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var fitScale = 1f
    private val maxZoom = 5f

    /** 0 = normal, 1 = sepia, 2 = noche */
    fun setMode(mode: Int) {
        paint.colorFilter = when (mode) {
            1 -> ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 0.93f, 0.78f, 1f) })
            2 -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            else -> null
        }
        setBackgroundColor(
            when (mode) {
                1 -> 0xFFE9DBB8.toInt()
                2 -> Color.BLACK
                else -> 0xFFE8E8E8.toInt()
            }
        )
        invalidate()
    }

    fun setPage(bmp: Bitmap?) {
        bitmap = bmp
        resetFit()
    }

    private fun currentScale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun resetFit() {
        val b = bitmap
        if (b == null || width == 0 || height == 0) {
            invalidate()
            return
        }
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(fitScale, fitScale)
        fixTranslation()
        invalidate()
    }

    private fun fixTranslation() {
        val b = bitmap ?: return
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val w = b.width * s
        val h = b.height * s
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        v[Matrix.MTRANS_X] = tx
        v[Matrix.MTRANS_Y] = ty
        m.setValues(v)
    }

    private fun toggleZoom(x: Float, y: Float) {
        val b = bitmap ?: return
        val widthScale = width.toFloat() / b.width
        val target = if (currentScale() > fitScale * 1.1f) fitScale
        else maxOf(widthScale, fitScale * 2f)
        val f = target / currentScale()
        m.postScale(f, f, x, y)
        fixTranslation()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, m, paint) }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (bitmap == null) return true
                val target = (currentScale() * d.scaleFactor).coerceIn(fitScale, fitScale * maxZoom)
                val f = target / currentScale()
                m.postScale(f, f, d.focusX, d.focusY)
                fixTranslation()
                invalidate()
                return true
            }
        })

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val zone = when {
                    e.x < width * 0.3f -> -1
                    e.x > width * 0.7f -> 1
                    else -> 0
                }
                onTapZone?.invoke(zone)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                m.postTranslate(-dx, -dy)
                fixTranslation()
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val atFit = currentScale() <= fitScale * 1.05f
                if (atFit && abs(vx) > abs(vy) && abs(vx) > 800f) {
                    onSwipe?.invoke(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}
