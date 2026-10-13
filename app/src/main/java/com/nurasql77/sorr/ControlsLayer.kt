package com.nurasql77.sorr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.launch

/** Блок ішіндегі басқа көрінетін батырмаларды сүйреп жылжытуға мүмкіндік беретін контейнер. */
class DraggableFrame(context: Context) : FrameLayout(context) {
    var editMode = false
    var onSelect: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f

    // Өзгерту режимінде батырмалар басылмайды, тек сүйреледі
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editMode

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                onSelect?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                onDrag?.invoke(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke()
        }
        return true
    }
}

/**
 * Экрандағы сенсорлық басқару: үш блок (сол жақ D-pad, оң жақ A/B/X/Y, қосымша L1/R1/L2).
 * Әр блоктың орнын, өлшемін және мөлдірлігін өзгертуге болады, баптаулар сақталады.
 */
class ControlsLayer(
    private val activity: ComponentActivity,
    private val prefs: SharedPreferences,
    private val sink: (action: Int, keyCode: Int) -> Unit
) {
    companion object {
        private const val ID_DPAD = 0
        private const val MIN_SCALE = 0.15f
        private const val MAX_SCALE = 2.0f
        private const val MIN_ALPHA = 0.2f
    }

    private class Group(
        val key: String,
        val title: String,
        val defCx: Float,
        val defCy: Float,
        val defScale: Float,
        val holder: DraggableFrame
    ) {
        var cx = defCx      // орталығының x орны (экран енінің үлесі)
        var cy = defCy      // орталығының y орны (экран биіктігінің үлесі)
        var scale = defScale
        var alpha = 1f
    }

    private val groups = mutableListOf<Group>()
    private var selected: Group? = null
    private var editMode = false

    private val heldDpad = mutableSetOf<Int>()

    private var screenW = activity.resources.displayMetrics.widthPixels
    private var screenH = activity.resources.displayMetrics.heightPixels

    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var alphaBar: SeekBar

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun baseSize(): Float = minOf(screenH * 0.62f, screenW * 0.42f)

    // ---------- Құру ----------

    fun addTo(container: FrameLayout) {
        val left = createGroup("left", "Сол жақ: D-pad", 0.17f, 0.72f, 1f, buildLeftConfig())
        val right = createGroup("right", "Оң жақ: A B X Y", 0.83f, 0.72f, 1f, buildRightConfig())
        val l1 = createGroup("l1", "L1", 0.12f, 0.15f, 0.3f, buildSingleConfig(KeyEvent.KEYCODE_BUTTON_L1, "L1"))
        val l2 = createGroup("l2", "L2", 0.24f, 0.15f, 0.3f, buildSingleConfig(KeyEvent.KEYCODE_BUTTON_L2, "L2"))
        val r2 = createGroup("r2", "R2", 0.76f, 0.15f, 0.3f, buildSingleConfig(KeyEvent.KEYCODE_BUTTON_R2, "R2"))
        val r1 = createGroup("r1", "R1", 0.88f, 0.15f, 0.3f, buildSingleConfig(KeyEvent.KEYCODE_BUTTON_R1, "R1"))

        for (g in listOf(left, right, l1, l2, r2, r1)) {
            container.addView(g.holder)
            applyLayout(g)
            watchEvents(g)
        }

        buildPanel()
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                (screenW * 0.40f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(8) }
        )

        val gear = Button(activity).apply {
            text = "⚙"
            alpha = 0.55f
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (editMode) exitEdit() else enterEdit() }
        }
        container.addView(
            gear,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(8)
                topMargin = dp(8)
            }
        )

        // Экран өлшемі өзгерсе (мысалы бұрылса), орындарды қайта есептейміз
        container.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != screenW || h != screenH)) {
                screenW = w
                screenH = h
                groups.forEach { applyLayout(it) }
            }
        }
    }

    private fun createGroup(
        key: String,
        title: String,
        cx: Float,
        cy: Float,
        scale: Float,
        config: RadialGamePadConfig
    ): Group {
        val pad = RadialGamePad(config, 8f, activity)
        val holder = DraggableFrame(activity)
        holder.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        padViews[key] = pad

        val g = Group(key, title, cx, cy, scale, holder)
        load(g)
        groups.add(g)

        holder.onSelect = { select(g) }
        holder.onDrag = { dx, dy ->
            g.cx = (g.cx + dx / screenW).coerceIn(0.02f, 0.98f)
            g.cy = (g.cy + dy / screenH).coerceIn(0.02f, 0.98f)
            applyLayout(g)
        }
        holder.onRelease = { save(g) }
        return g
    }

    private val padViews = mutableMapOf<String, RadialGamePad>()

    private fun watchEvents(g: Group) {
        val pad = padViews[g.key] ?: return
        activity.lifecycleScope.launch {
            pad.events()
                .flowWithLifecycle(activity.lifecycle, Lifecycle.State.RESUMED)
                .collect { handleEvent(it) }
        }
    }

    // ---------- Орналастыру және сақтау ----------

    private fun applyLayout(g: Group) {
        val size = (baseSize() * g.scale).toInt().coerceAtLeast(dp(56))
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        lp.leftMargin = (g.cx * screenW - size / 2f).toInt()
        lp.topMargin = (g.cy * screenH - size / 2f).toInt()
        g.holder.layoutParams = lp
        g.holder.alpha = g.alpha
    }

    private fun save(g: Group) {
        prefs.edit()
            .putFloat("ctl_${g.key}_cx", g.cx)
            .putFloat("ctl_${g.key}_cy", g.cy)
            .putFloat("ctl_${g.key}_scale", g.scale)
            .putFloat("ctl_${g.key}_alpha", g.alpha)
            .apply()
    }

    private fun load(g: Group) {
        g.cx = prefs.getFloat("ctl_${g.key}_cx", g.defCx).coerceIn(0.02f, 0.98f)
        g.cy = prefs.getFloat("ctl_${g.key}_cy", g.defCy).coerceIn(0.02f, 0.98f)
        g.scale = prefs.getFloat("ctl_${g.key}_scale", g.defScale).coerceIn(MIN_SCALE, MAX_SCALE)
        g.alpha = prefs.getFloat("ctl_${g.key}_alpha", 1f).coerceIn(MIN_ALPHA, 1f)
    }

    // ---------- Өзгерту режимі ----------

    private fun buildPanel() {
        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }

        panelTitle = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "Блокты сүйреп жылжытыңыз"
        }
        panel.addView(panelTitle)

        panel.addView(label("Өлшемі"))
        sizeBar = SeekBar(activity).apply {
            max = ((MAX_SCALE - MIN_SCALE) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val g = selected ?: return
                    g.scale = MIN_SCALE + progress / 100f
                    applyLayout(g)
                    save(g)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(sizeBar)

        panel.addView(label("Мөлдірлігі"))
        alphaBar = SeekBar(activity).apply {
            max = ((1f - MIN_ALPHA) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val g = selected ?: return
                    g.alpha = MIN_ALPHA + progress / 100f
                    applyLayout(g)
                    save(g)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(alphaBar)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val reset = Button(activity).apply {
            text = "Бастапқы"
            setOnClickListener { resetSelected() }
        }
        val done = Button(activity).apply {
            text = "Дайын"
            setOnClickListener { exitEdit() }
        }
        row.addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        textSize = 12f
    }

    private fun enterEdit() {
        editMode = true
        panel.visibility = View.VISIBLE
        for (g in groups) {
            g.holder.visibility = View.VISIBLE
            g.holder.editMode = true
        }
        select(groups.firstOrNull())
    }

    private fun exitEdit() {
        editMode = false
        panel.visibility = View.GONE
        for (g in groups) {
            g.holder.editMode = false
            g.holder.foreground = null
            save(g)
        }
        selected = null
    }

    private fun select(g: Group?) {
        selected = g
        for (x in groups) {
            x.holder.foreground = if (editMode) border(if (x === g) Color.YELLOW else Color.WHITE) else null
        }
        refreshPanel()
    }

    private fun border(color: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), color)
        cornerRadius = dp(12).toFloat()
    }

    private fun refreshPanel() {
        val g = selected ?: return
        panelTitle.text = g.title
        sizeBar.progress = ((g.scale - MIN_SCALE) * 100).toInt()
        alphaBar.progress = ((g.alpha - MIN_ALPHA) * 100).toInt()
    }

    private fun resetSelected() {
        val g = selected ?: return
        g.cx = g.defCx
        g.cy = g.defCy
        g.scale = g.defScale
        g.alpha = 1f
        applyLayout(g)
        save(g)
        refreshPanel()
    }

    // ---------- Сыртқы басқару ----------

    /** Артқа түймесі: өзгерту режимінде болса, соны жабады. */
    fun handleBack(): Boolean {
        if (editMode) {
            exitEdit()
            return true
        }
        return false
    }

    /** Нақты геймпад қосылса, экрандағы түймелерді жасырамыз. */
    fun onPhysicalGamepad() {
        if (editMode) return
        groups.forEach { it.holder.visibility = View.GONE }
    }

    // ---------- Конфигурациялар ----------

    // Сол жақ: D-pad (диагональмен) + SELECT
    private fun buildLeftConfig() = RadialGamePadConfig(
        sockets = 6,
        primaryDial = PrimaryDialConfig.Cross(
            CrossConfig(
                id = ID_DPAD,
                useDiagonals = true
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                index = 1,
                scale = 1f,
                distance = 0f,
                ButtonConfig(
                    id = KeyEvent.KEYCODE_BUTTON_SELECT,
                    label = "SELECT"
                )
            )
        )
    )

    // Оң жақ: A, B, X, Y + START
    private fun buildRightConfig() = RadialGamePadConfig(
        sockets = 6,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "X"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "Y")
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                index = 2,
                scale = 1f,
                distance = 0f,
                ButtonConfig(
                    id = KeyEvent.KEYCODE_BUTTON_START,
                    label = "START"
                )
            )
        )
    )

    // Жеке бір түйме (L1, L2, R1, R2)
    private fun buildSingleConfig(keyCode: Int, label: String) = RadialGamePadConfig(
        sockets = 6,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(ButtonConfig(id = keyCode, label = label))
        ),
        secondaryDials = emptyList()
    )

    // ---------- Оқиғалар ----------

    private fun handleEvent(event: Event) {
        when (event) {
            is Event.Button -> sink(event.action, event.id)
            is Event.Direction -> if (event.id == ID_DPAD) updateDpad(event.xAxis, event.yAxis)
            else -> Unit
        }
    }

    private fun updateDpad(x: Float, y: Float) {
        val wanted = mutableSetOf<Int>()
        if (x < -0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_LEFT)
        if (x > 0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (y < -0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_UP)
        if (y > 0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_DOWN)

        for (key in heldDpad.toList()) {
            if (key !in wanted) {
                sink(KeyEvent.ACTION_UP, key)
                heldDpad.remove(key)
            }
        }
        for (key in wanted) {
            if (key !in heldDpad) {
                sink(KeyEvent.ACTION_DOWN, key)
                heldDpad.add(key)
            }
        }
    }
}
