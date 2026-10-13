package com.nurasql77.sorr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Экрандағы сенсорлық басқару: D-pad (сол жақта), A/B/X/Y түймелері (оң жақта),
 * Select/Start (ортада). Көп саусақпен басуды қолдайды.
 * Әр өзгерісте onKey(keyCode, down) шақырылады.
 */
class TouchControlsView(
    context: Context,
    private val onKey: (keyCode: Int, down: Boolean) -> Unit
) : View(context) {

    private class Btn(
        val keyCode: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f
    )

    private val btnA = Btn(KeyEvent.KEYCODE_BUTTON_A, "A")
    private val btnB = Btn(KeyEvent.KEYCODE_BUTTON_B, "B")
    private val btnX = Btn(KeyEvent.KEYCODE_BUTTON_X, "X")
    private val btnY = Btn(KeyEvent.KEYCODE_BUTTON_Y, "Y")
    private val btnStart = Btn(KeyEvent.KEYCODE_BUTTON_START, "START")
    private val btnSelect = Btn(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT")
    private val allButtons = listOf(btnA, btnB, btnX, btnY, btnStart, btnSelect)

    // D-pad геометриясы
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadR = 0f

    private val pressed = mutableSetOf<Int>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val u = h.toFloat()
        val margin = u * 0.06f

        // D-pad: сол жақ төменде
        dpadR = u * 0.24f
        dpadCx = margin + dpadR
        dpadCy = h - margin - dpadR

        // Әрекет түймелері: оң жақ төменде (ромб)
        val cx = w - margin - u * 0.34f
        val cy = h - margin - u * 0.30f
        val d = u * 0.17f
        val r = u * 0.115f
        btnA.apply { this.cx = cx; this.cy = cy + d; this.r = r }
        btnB.apply { this.cx = cx + d; this.cy = cy; this.r = r }
        btnX.apply { this.cx = cx - d; this.cy = cy; this.r = r }
        btnY.apply { this.cx = cx; this.cy = cy - d; this.r = r }

        // Select / Start: ортада төменде
        val sr = u * 0.07f
        btnSelect.apply { this.cx = w / 2f - sr * 2.2f; this.cy = h - margin - sr; this.r = sr }
        btnStart.apply { this.cx = w / 2f + sr * 2.2f; this.cy = h - margin - sr; this.r = sr }

        textPaint.textSize = r * 0.8f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = mutableSetOf<Int>()
        val action = e.actionMasked
        val releasingAll = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1

        if (!releasingAll) {
            for (i in 0 until e.pointerCount) {
                if (i == liftedIndex) continue
                collectKeys(e.getX(i), e.getY(i), now)
            }
        }

        // Өзгерген түймелерді ғана жібереміз
        var newPress = false
        for (k in pressed.toList()) {
            if (k !in now) onKey(k, false)
        }
        for (k in now) {
            if (k !in pressed) {
                onKey(k, true)
                newPress = true
            }
        }
        if (newPress) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        pressed.clear()
        pressed.addAll(now)
        invalidate()
        return true
    }

    private fun collectKeys(x: Float, y: Float, out: MutableSet<Int>) {
        // D-pad
        val dx = x - dpadCx
        val dy = y - dpadCy
        if (hypot(dx, dy) <= dpadR * 1.5f) {
            val t = dpadR * 0.30f
            if (dx < -t) out.add(KeyEvent.KEYCODE_DPAD_LEFT)
            if (dx > t) out.add(KeyEvent.KEYCODE_DPAD_RIGHT)
            if (dy < -t) out.add(KeyEvent.KEYCODE_DPAD_UP)
            if (dy > t) out.add(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        // Түймелер
        for (b in allButtons) {
            if (hypot(x - b.cx, y - b.cy) <= b.r * 1.25f) out.add(b.keyCode)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // D-pad
        fillPaint.alpha = 50
        strokePaint.alpha = 120
        canvas.drawCircle(dpadCx, dpadCy, dpadR, fillPaint)
        canvas.drawCircle(dpadCx, dpadCy, dpadR, strokePaint)

        val a = dpadR * 0.62f
        val s = dpadR * 0.26f
        drawArrow(canvas, dpadCx, dpadCy - a, s, KeyEvent.KEYCODE_DPAD_UP)
        drawArrow(canvas, dpadCx, dpadCy + a, s, KeyEvent.KEYCODE_DPAD_DOWN)
        drawArrow(canvas, dpadCx - a, dpadCy, s, KeyEvent.KEYCODE_DPAD_LEFT)
        drawArrow(canvas, dpadCx + a, dpadCy, s, KeyEvent.KEYCODE_DPAD_RIGHT)

        // Түймелер
        for (b in allButtons) {
            val on = b.keyCode in pressed
            fillPaint.alpha = if (on) 140 else 50
            strokePaint.alpha = 140
            canvas.drawCircle(b.cx, b.cy, b.r, fillPaint)
            canvas.drawCircle(b.cx, b.cy, b.r, strokePaint)

            textPaint.alpha = 200
            if (b.label.length > 1) {
                val old = textPaint.textSize
                textPaint.textSize = b.r * 0.38f
                canvas.drawText(b.label, b.cx, b.cy + textPaint.textSize / 3f, textPaint)
                textPaint.textSize = old
            } else {
                canvas.drawText(b.label, b.cx, b.cy + textPaint.textSize / 3f, textPaint)
            }
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, key: Int) {
        val on = key in pressed
        fillPaint.alpha = if (on) 170 else 90
        canvas.drawCircle(x, y, size, fillPaint)
    }
}
