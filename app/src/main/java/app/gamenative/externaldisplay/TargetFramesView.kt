package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * wow-mobile: the bottom pad's Target page. Shows party / battleground / arena units from
 * the WoWMobileLink addon (see [LinkState]) as boxes with class colour, health bar and name.
 * Tapping a box targets that unit by typing /target.
 *
 * Mode follows where you are (Auto), or a tab picks it by hand.
 */
@SuppressLint("ViewConstructor")
class TargetFramesView(
    context: Context,
    private val xServer: XServer,
    private val theme: PadTheme,
    private val onSuspendModifiers: () -> List<XKeycode>,
    private val onResumeModifiers: (List<XKeycode>) -> Unit,
) : LinearLayout(context) {

    // Saved by ordinal, so new modes go at the end; TAB_ORDER sets the order on screen.
    private enum class Mode(val label: String) { AUTO("Auto"), NEARBY("Nearby"), DUNGEON("Party"), BATTLEGROUND("BG All"), ARENA("Arena"), FRIENDLY("NPC"), BG_TEAM("BG Team") }
    // AUTO is no longer offered (kept so saved ordinals still line up); a saved AUTO opens Nearby.
    private val TAB_ORDER = listOf(Mode.NEARBY, Mode.FRIENDLY, Mode.DUNGEON, Mode.BG_TEAM, Mode.BATTLEGROUND, Mode.ARENA)

    private var chosen = Mode.values().getOrElse(PadSettings.int(KEY_MODE)) { Mode.NEARBY }.let { if (it == Mode.AUTO) Mode.NEARBY else it }
    private val tabs = mutableMapOf<Mode, TextView>()
    private val status: TextView
    private val body = LinearLayout(context).apply {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }
    private var builtFor: String? = null
    private val boxes = mutableListOf<UnitBox>()
    private val columnBoxes = mutableListOf<MutableList<UnitBox>>()
    private val listener: () -> Unit = { refresh() }
    private val tick = object : Runnable {
        override fun run() { refresh(); postDelayed(this, 500) }
    }

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
        }
        TAB_ORDER.forEach { m ->
            val t = pill(m.label) { chosen = m; PadSettings.set(KEY_MODE, m.ordinal); refresh() }
            tabs[m] = t
            tabRow.addView(t)
        }
        addView(tabRow)
        status = TextView(context).apply {
            setTextColor(theme.text)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(status)
        addView(body)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LinkState.addListener(listener)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        LinkState.removeListener(listener)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val mode = chosen
        tabs.forEach { (m, t) -> stylePill(t, m == chosen, false) }
        val receiving = System.currentTimeMillis() - LinkState.lastPacketAt < 2000
        status.text = if (receiving) "" else "Waiting for the WoW Mobile Link addon…"
        status.visibility = if (receiving) GONE else VISIBLE

        // Columns of slots for this mode.
        val columns: List<List<Int>> = when (mode) {
            // Party left, nearby enemies right (grows past 5 rows when needed, see below).
            Mode.DUNGEON, Mode.AUTO -> listOf((0..4).toList(), (40..49).toList())
            Mode.NEARBY -> listOf((40..44).toList(), (45..49).toList())
            Mode.FRIENDLY -> listOf((50..54).toList(), (55..59).toList())
            Mode.ARENA -> listOf((0..4).toList(), (30..34).toList())
            Mode.BG_TEAM -> listOf((10..14).toList(), (15..19).toList())
            Mode.BATTLEGROUND -> listOf((10..19).toList(), (20..29).toList())
        }
        val key = mode.name
        if (builtFor != key) {
            builtFor = key
            body.removeAllViews()
            boxes.clear()
            columnBoxes.clear()
            columns.forEach { slots ->
                val list = mutableListOf<UnitBox>()
                columnBoxes += list
                body.addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    isMotionEventSplittingEnabled = true
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    slots.forEach { s -> UnitBox(s).also { boxes += it; list += it; addView(it) } }
                })
            }
        }
        if (mode == Mode.NEARBY) placeNearby()
        boxes.forEach { it.bind(LinkState.unit(it.slot)) }
        // A column longer than 5 shows 5 boxes, and more (smaller) ones only down to the last
        // occupied slot. Units keep their slot, so boxes above don't move, they just shrink.
        columnBoxes.filter { it.size > 5 }.forEach { list ->
            val last = list.indexOfLast { LinkState.unit(it.slot) != null }
            val show = maxOf(5, last + 1)
            list.forEachIndexed { i, b ->
                val v = if (i < show) VISIBLE else GONE
                if (b.visibility != v) b.visibility = v
            }
        }
    }

    /**
     * Nearby: 10 boxes, two columns of five. Enemies and NPCs (slots 40-49) fill from the top
     * left going down, friendly players (60-69) from the bottom right going up; each unit keeps its box, since the
     * addon keeps it in its slot. When both want a box, the one nearer its own start wins.
     */
    private fun placeNearby() {
        columnBoxes.forEachIndexed { col, list ->
            list.forEachIndexed { row, box ->
                val npc = col * 5 + row
                val player = (1 - col) * 5 + (4 - row)
                val hasNpc = LinkState.unit(40 + npc) != null
                val hasPlayer = LinkState.unit(60 + player) != null
                box.slot = when {
                    hasNpc && hasPlayer -> if (npc <= player) 40 + npc else 60 + player
                    hasPlayer -> 60 + player
                    else -> 40 + npc
                }
            }
        }
    }

    /**
     * Nearby units: click the unit's nameplate, which targets exactly that unit even when
     * several share a name. The camera pauses for the click, like for any touch.
     */
    private fun clickPlate(u: LinkUnit) {
        val w = xServer.screenInfo.width.toInt()
        val h = xServer.screenInfo.height.toInt()
        PadBridge.trackpadTouch(true)
        xServer.injectPointerMove(u.plateX * w / 255, u.plateY * h / 255)
        // Let WoW draw a few frames with the cursor over the plate before clicking: a click in the
        // same frame as the move hits whatever was under the old position (clears the target).
        postDelayed({
            xServer.injectPointerButtonPress(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
            postDelayed({
                xServer.injectPointerButtonRelease(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
                // WoW reads the cursor position once per frame: move it only after a few frames,
                // or the release is seen at the new position and the click misses the plate.
                if (PadSettings.bool(PadSettings.TARGET_CURSOR_CENTER)) {
                    postDelayed({
                        xServer.injectPointerMove(w / 2, h / 2)
                        PadBridge.trackpadTouch(false)
                    }, PLATE_HOVER_MS)
                } else PadBridge.trackpadTouch(false)
            }, 40)
        }, PLATE_HOVER_MS)
    }

    /** The WoWMobileLink addon's key for a slot (see KEYS in WoWMobileLink.lua): key and whether Shift is part of it. */
    private fun targetKey(slot: Int): Pair<XKeycode, Boolean>? {
        val digits = "1234567890"
        fun k(c: Char) = XKeycode.valueOf("KEY_$c")
        return when (slot) {
            in 0..4 -> k(digits[slot]) to true
            in 30..34 -> k(digits[slot - 25]) to true
            in 10..19 -> k("QWERTYUIOP"[slot - 10]) to true
            in 20..29 -> k("ASDFGHJZXC"[slot - 20]) to true
            in 40..49 -> k(digits[slot - 40]) to false
            in 50..59 -> k("QWERTYUIOP"[slot - 50]) to false
            in 60..69 -> k("ASDFGHJZXC"[slot - 60]) to false
            else -> null
        }
    }

    private fun commandFor(slot: Int, unit: LinkUnit): String? = when (slot) {
        0 -> "/target player"
        in 1..4 -> "/target party$slot"
        in 30..34 -> "/target arena${slot - 29}"
        // Names are typed letter by letter; names with letters the pad can't type are skipped.
        else -> unit.name.takeIf { n -> n.isNotEmpty() && n.all { SlashTyper.canType(it) } }?.let { "/target $it" }
    }

    private inner class UnitBox(var slot: Int) : View(context) {
        private var unit: LinkUnit? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(theme.typeface, Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        private val rect = RectF()

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }

        fun bind(u: LinkUnit?) {
            if (u != unit) { unit = u; invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            val u = unit
            val r = dp(8).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(255, 22, 20, 18)
            canvas.drawRoundRect(rect, r, r, fill)
            if (u == null) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = dp(1).toFloat(); fill.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(rect, r, r, fill)
                return
            }
            // Bar colour: players in their class colour (red/green by reaction when it isn't
            // known); NPCs in their nameplate's colour (NotPlater's threat colours), sent as
            // 128 + 2 bits each of red, green, blue; else by reaction.
            val isPlayer = u.flags and 64 != 0
            val enemy = u.flags and 8 != 0
            val cls = when {
                isPlayer && u.classId in 1..10 -> CLASS_COLORS[u.classId]
                isPlayer -> if (enemy) HOSTILE else FRIENDLY
                u.classId >= 128 -> (u.classId - 128).let { c -> Color.rgb((c shr 4 and 3) * 85, (c shr 2 and 3) * 85, (c and 3) * 85) }
                enemy -> HOSTILE
                u.flags and 16 != 0 -> NEUTRAL
                u.flags and 32 != 0 -> FRIENDLY
                else -> Color.GRAY
            }
            // Health bar in the class colour (dimmed when unknown or dead).
            val frac = if (u.hpKnown && !u.dead) u.hp / 100f else if (u.hpKnown) 0f else 1f
            fill.color = if (!u.hpKnown || u.notVisible) Color.argb(70, Color.red(cls), Color.green(cls), Color.blue(cls)) else cls
            canvas.save()
            canvas.clipRect(0f, 0f, width * frac, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
            canvas.restore()
            // Border: gold for your target; players red (enemy) or green (friendly).
            fill.style = Paint.Style.STROKE
            val playerEdge = isPlayer && u.flags and (8 or 32) != 0
            fill.strokeWidth = dp(if (u.isTarget || playerEdge) 3 else 1).toFloat()
            fill.color = when {
                u.isTarget -> Color.rgb(255, 209, 0)
                playerEdge -> if (enemy) Color.rgb(230, 30, 30) else Color.rgb(40, 220, 40)
                else -> Color.argb(90, 255, 255, 255)
            }
            canvas.drawRoundRect(rect, r, r, fill)
            // Health right, name left in the space that's left: shrinks to fit, then "…".
            val hpText = when { u.dead -> "Dead"; u.hpKnown -> "${u.hp}%"; else -> "" }
            val pad = dp(12).toFloat()
            var size = (height * 0.40f).coerceAtMost(dp(21).toFloat())
            text.textSize = size
            val hpWidth = text.measureText(hpText)
            val nameRoom = width - 2 * pad - (if (hpText.isEmpty()) 0f else hpWidth + dp(10))
            val minSize = dp(13).toFloat()
            while (size > minSize && text.measureText(u.label) > nameRoom) { size -= 1f; text.textSize = size }
            var name = u.label
            if (text.measureText(name) > nameRoom) {
                while (name.isNotEmpty() && text.measureText("$name…") > nameRoom) name = name.dropLast(1)
                name = "$name…"
            }
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(name, pad, baseline, text)
            text.textSize = (height * 0.40f).coerceAtMost(dp(21).toFloat())
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(hpText, width - pad, height / 2f - (text.descent() + text.ascent()) / 2, text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { alpha = 0.6f; PadSettings.haptic(this) }
                MotionEvent.ACTION_UP -> {
                    alpha = 1f
                    val u = unit ?: return true
                    val key = targetKey(slot)
                    if (u.plateX > 0 && u.plateY > 0) clickPlate(u)
                    else if (key != null && !u.noKey) SlashTyper.chord(xServer, key.first, key.second, onSuspendModifiers, onResumeModifiers)
                    else commandFor(slot, u)?.let { SlashTyper.type(xServer, it, onSuspendModifiers, onResumeModifiers) }
                }
                MotionEvent.ACTION_CANCEL -> alpha = 1f
            }
            return true
        }
    }

    private fun pill(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = theme.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { PadSettings.haptic(this); onTap() }
    }

    private fun stylePill(t: TextView, on: Boolean, auto: Boolean) {
        t.background = theme.buttonBackground(resources.displayMetrics.density, 10f, on, emphasized = on)
        t.setTextColor(if (on) theme.textPressed else theme.text)
        t.alpha = if (auto || on) 1f else 0.85f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_MODE = "target_mode"
        private const val PLATE_HOVER_MS = 80L

        private val HOSTILE = Color.rgb(204, 38, 38)
        private val NEUTRAL = Color.rgb(230, 204, 26)
        private val FRIENDLY = Color.rgb(51, 184, 51)

        /** WoW's class colours, by the addon's class id (1 warrior ... 10 druid). */
        private val CLASS_COLORS = intArrayOf(
            Color.GRAY,
            Color.rgb(199, 156, 110), Color.rgb(245, 140, 186), Color.rgb(171, 212, 115), Color.rgb(255, 245, 105),
            Color.rgb(255, 255, 255), Color.rgb(196, 31, 59), Color.rgb(0, 112, 222), Color.rgb(105, 204, 240),
            Color.rgb(148, 130, 201), Color.rgb(255, 125, 10),
        )
    }
}
