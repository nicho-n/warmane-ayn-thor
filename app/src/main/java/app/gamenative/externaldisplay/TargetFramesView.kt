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
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * wow-mobile: the bottom pad's Target page. Shows party / battleground / arena units from
 * the WoWMobileLink addon (see [LinkState]) as boxes with class colour, health bar and name.
 * Tapping a box targets that unit by typing /target.
 *
 * The top row: Mode (opens a list of the targeting modes), then Invite, Thank, Sorry, Bags
 * and Map.
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
    private enum class Mode(val label: String) { AUTO("Auto"), NEARBY("Nearby"), DUNGEON("Party"), BATTLEGROUND("BG All"), ARENA("Arena"), FRIENDLY("Friendly NPC"), BG_TEAM("BG Team"), RAID_HEAL("Raid Heal") }
    // AUTO is no longer offered (kept so saved ordinals still line up); a saved AUTO opens Nearby.
    private val TAB_ORDER = listOf(Mode.NEARBY, Mode.DUNGEON, Mode.RAID_HEAL, Mode.BG_TEAM, Mode.BATTLEGROUND, Mode.ARENA, Mode.FRIENDLY)

    // Nearby each time the game starts; a mode picked in the list lasts until then (the view
    // is rebuilt whenever the page is shown).
    private var chosen = sessionMode ?: Mode.NEARBY
    private val tabs = mutableMapOf<Mode, TextView>()
    private lateinit var modeButton: TextView
    private val actionPills = mutableListOf<TextView>()
    /**
     * The focus row, in every mode: Clear Focus on the left; on the right Set Focus, or your
     * focus's box once you have one (targets it even without its nameplate).
     */
    private lateinit var focusBox: UnitBox
    private lateinit var setFocus: TextView
    private lateinit var clearFocus: TextView
    private lateinit var focusRow: LinearLayout
    /** The list of modes, shown over the boxes under the Mode button. */
    private val modeMenu = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
        isClickable = true
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = GradientDrawable().apply {
            setColor(theme.background)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), theme.border)
        }
    }
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
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(39))
        }
        modeButton = pill("Mode") {
            modeMenu.visibility = if (modeMenu.visibility == VISIBLE) GONE else VISIBLE
            refresh()
        }.apply { (layoutParams as LayoutParams).weight = 1.6f }
        tabRow.addView(modeButton)
        listOf<Pair<String, () -> Unit>>(
            "Invite" to { slash("/invite") },
            "Thank" to { slash("/thank") },
            "Sorry" to { slash("/sorry") },
            "Map" to { key(XKeycode.KEY_M) },
            "Bags" to { key(XKeycode.KEY_B) },
        ).forEach { (label, action) ->
            val t = pill(label, action).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
            actionPills += t
            tabRow.addView(t)
        }
        addView(tabRow)
        TAB_ORDER.forEach { m ->
            val t = pill(m.label) {
                chosen = m
                sessionMode = m
                modeMenu.visibility = GONE
                refresh()
            }.apply {
                // Equal shares of the page's height, so every mode fits.
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            tabs[m] = t
            modeMenu.addView(t)
        }
        status = TextView(context).apply {
            setTextColor(theme.text)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(status)
        focusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        }
        clearFocus = pill("Clear Focus") { if (LinkState.unit(FOCUS_SLOT) != null) chordCtrlAlt(XKeycode.KEY_F4) }
            .apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        setFocus = pill("Set Focus") { if (LinkState.unit(TARGET_SLOT) != null) chordCtrlAlt(XKeycode.KEY_F3) }
            .apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        focusBox = UnitBox(FOCUS_SLOT).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            visibility = GONE
        }
        focusRow.addView(clearFocus)
        focusRow.addView(setFocus)
        focusRow.addView(focusBox)
        addView(focusRow)
        val area = FrameLayout(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f) }
        body.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        area.addView(body)
        area.addView(modeMenu, FrameLayout.LayoutParams(dp(240), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(3), 0, 0, 0) })
        addView(area)
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
        // In the mode list only the chosen mode has the gold trim; the rest dark grey.
        tabs.forEach { (m, t) ->
            if (m == chosen) stylePill(t, true, false)
            else {
                t.background = theme.buttonBackground(resources.displayMetrics.density, 10f, false, accent = MENU_GREY)
                t.setTextColor(theme.text)
                t.alpha = 1f
            }
        }
        modeButton.text = mode.label
        stylePill(modeButton, modeMenu.visibility == VISIBLE, true)
        actionPills.forEach { stylePill(it, false, true) }
        val receiving = System.currentTimeMillis() - LinkState.lastPacketAt < 2000
        status.text = if (receiving) "" else "Waiting for the WoW Mobile Link addon…"
        status.visibility = if (receiving) GONE else VISIBLE

        // Columns of slots for this mode.
        val columns: List<List<Int>> = when (mode) {
            // Party left, nearby enemies right (up to the Party mode maximum; grows past 5 rows
            // when needed, see below), or in two columns with the extra column on.
            Mode.DUNGEON, Mode.AUTO -> partyColumns()
            // Placeholder slots: placeNearby() puts units in the boxes.
            Mode.NEARBY -> nearbyRows().let { r -> listOf(List(r) { -1 }, List(r) { -1 }) }
            Mode.FRIENDLY -> listOf((50..54).toList(), (55..59).toList())
            // One column of 5 per raid group (2 to 5 columns); placeRaid() puts units in the boxes.
            Mode.RAID_HEAL -> List(raidColumns()) { List(5) { -1 } }
            // 2v2, 3v3 or 5v5 by how many are in the match (2v2 until more show up).
            Mode.ARENA -> arenaSize().let { n -> listOf((0 until n).toList(), (30 until 30 + n).toList()) }
            // 15 per side: team 10-19 then 90-94, enemies 20-29 then 95-99.
            Mode.BG_TEAM -> listOf((10..14) + (90..92), (15..19) + (93..94))
            Mode.BATTLEGROUND -> listOf((10..19) + (90..94), (20..29) + (95..99))
        }
        val key = when (mode) {
            Mode.NEARBY -> "NEARBY-${nearbyRows()}"
            Mode.ARENA -> "ARENA-${arenaSize()}"
            Mode.RAID_HEAL -> "RAID-${raidColumns()}"
            Mode.DUNGEON, Mode.AUTO -> "PARTY-${partyEnemies()}-${PadSettings.bool(PadSettings.PARTY_EXTRA_COLUMN)}"
            else -> mode.name
        }
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
        val focus = LinkState.unit(FOCUS_SLOT)
        focusBox.bind(focus)
        (if (focus != null) VISIBLE else GONE).let { if (focusBox.visibility != it) focusBox.visibility = it }
        (if (focus != null) GONE else VISIBLE).let { if (setFocus.visibility != it) setFocus.visibility = it }
        (if (PadSettings.bool(PadSettings.FOCUS_BAR)) VISIBLE else GONE).let { if (focusRow.visibility != it) focusRow.visibility = it }
        clearFocus.background = theme.buttonBackground(resources.displayMetrics.density, 10f, false, accent = MENU_GREY)
        clearFocus.setTextColor(theme.text)
        clearFocus.alpha = if (focus != null) 1f else 0.6f
        // Gold only with a target to focus; greyed out like Clear Focus otherwise.
        if (LinkState.unit(TARGET_SLOT) != null) {
            stylePill(setFocus, false, true)
        } else {
            setFocus.background = theme.buttonBackground(resources.displayMetrics.density, 10f, false, accent = MENU_GREY)
            setFocus.setTextColor(theme.text)
            setFocus.alpha = 0.6f
        }
        if (mode == Mode.NEARBY) placeNearby()
        if (mode == Mode.RAID_HEAL) placeRaid()
        // Your focus is on the focus row: leave its box here empty rather than show it twice.
        // Players by name (unique); an NPC only when no other listed unit has its name.
        val focusName = focus?.takeIf { PadSettings.bool(PadSettings.FOCUS_BAR) }?.name
        val listed = boxes.mapNotNull { LinkState.unit(it.slot) }
        fun isFocus(u: LinkUnit) = focusName != null && u.name == focusName &&
            (u.flags and 64 != 0 || listed.count { it.name == focusName } == 1)
        boxes.forEach { b -> b.bind(LinkState.unit(b.slot)?.takeUnless { isFocus(it) }) }
        // A column longer than 5 shows 5 boxes, and more (smaller) ones only down to the last
        // occupied slot. Units keep their slot, so boxes above don't move, they just shrink.
        // (Not Nearby: its grid size comes from the settings, and units fill from the corners.)
        // (Nor Party mode with the extra column: a fixed grid.)
        val fixedGrid = mode == Mode.NEARBY || mode == Mode.RAID_HEAL || ((mode == Mode.DUNGEON || mode == Mode.AUTO) && PadSettings.bool(PadSettings.PARTY_EXTRA_COLUMN))
        if (!fixedGrid) columnBoxes.filter { it.size > 5 }.forEach { list ->
            val last = list.indexOfLast { LinkState.unit(it.slot) != null }
            val show = maxOf(5, last + 1)
            list.forEachIndexed { i, b ->
                val v = if (i < show) VISIBLE else GONE
                if (b.visibility != v) b.visibility = v
            }
        }
    }

    /**
     * Nearby: 10 boxes, two columns of five. Enemies (slots 40-49: hostile and neutral) fill
     * from one corner and friendlies (60-69) from the other: by default enemies from the
     * bottom right going up, friendlies from the top left going down (Settings → Targeting
     * swaps them). Each unit keeps its box, since the addon keeps it in its slot. When both
     * want a box, the one nearer its own start wins. Units past the set maximum aren't shown.
     */
    private fun placeNearby() {
        // Unit k (0-19) of a side: enemies in slots 40-49 then 70-79, friendlies 60-69 then 80-89.
        fun enemySlot(k: Int) = if (k < 10) 40 + k else 60 + k
        fun friendSlot(k: Int) = if (k < 10) 60 + k else 70 + k
        fun allowed(slotOf: (Int) -> Int, max: Int): Set<Int> =
            (0..19).filter { LinkState.unit(slotOf(it)) != null }.take(max).toSet()
        val enemies = allowed(::enemySlot, PadSettings.int(PadSettings.NEARBY_MAX_ENEMIES))
        val friends = allowed(::friendSlot, PadSettings.int(PadSettings.NEARBY_MAX_FRIENDS))
        val enemiesTopLeft = PadSettings.int(PadSettings.NEARBY_ENEMIES_TOP_LEFT) == 1
        val rows = columnBoxes.firstOrNull()?.size ?: return
        columnBoxes.forEachIndexed { col, list ->
            list.forEachIndexed { row, box ->
                val topLeft = col * rows + row
                val bottomRight = 2 * rows - 1 - topLeft
                val e = if (enemiesTopLeft) topLeft else bottomRight
                val f = if (enemiesTopLeft) bottomRight else topLeft
                val hasEnemy = e in enemies
                val hasFriend = f in friends
                box.slot = when {
                    hasEnemy && hasFriend -> if (e <= f) enemySlot(e) else friendSlot(f)
                    hasFriend -> friendSlot(f)
                    hasEnemy -> enemySlot(e)
                    else -> -1
                }
            }
        }
    }

    /** Raid members (slots 100-124) the addon is sending now. */
    private fun raidUnits(): List<LinkUnit> = RAID_SLOTS.mapNotNull { LinkState.unit(it) }

    /** Raid Heal columns: one per raid group up to the highest in use, 2 (10-man) to 5 (25-man). */
    private fun raidColumns(): Int = (raidUnits().maxOfOrNull { it.raidGroup } ?: 0).coerceIn(2, 5)

    /**
     * Raid Heal: raid group N in column N, its members top to bottom in raid order. Members of
     * groups past the last column fill any free boxes. Not in a raid: you and your party in
     * the first column.
     */
    private fun placeRaid() {
        val all = columnBoxes.flatten()
        all.forEach { it.slot = -1 }
        val raid = raidUnits()
        if (raid.isEmpty()) {
            (0..4).forEachIndexed { i, s -> columnBoxes.firstOrNull()?.getOrNull(i)?.slot = s }
            return
        }
        val overflow = mutableListOf<LinkUnit>()
        raid.groupBy { it.raidGroup }.forEach { (group, members) ->
            val column = columnBoxes.getOrNull(group - 1)
            members.sortedBy { it.slot }.forEachIndexed { i, u ->
                val box = column?.getOrNull(i)
                if (box != null) box.slot = u.slot else overflow += u
            }
        }
        overflow.forEach { u -> all.firstOrNull { it.slot == -1 }?.slot = u.slot }
    }

    /** Party mode's nearby-enemy slots: the first 10 nearby enemies (40-49), then 70-74. */
    private fun partyEnemies(): Int = PadSettings.int(PadSettings.PARTY_MAX_ENEMIES).coerceIn(1, 15)

    private fun partyColumns(): List<List<Int>> {
        val enemies = ((40..49) + (70..74)).take(partyEnemies())
        fun pad(list: List<Int>, rows: Int) = list + List(rows - list.size) { -1 }
        if (!PadSettings.bool(PadSettings.PARTY_EXTRA_COLUMN)) return listOf((0..4).toList(), pad(enemies, maxOf(5, enemies.size)))
        // Extra column: enemies fill the middle column, then the right one; all columns the
        // same height, so the boxes line up.
        val rows = maxOf(5, (enemies.size + 1) / 2)
        return listOf(pad((0..4).toList(), rows), pad(enemies.take(rows), rows), pad(enemies.drop(rows), rows))
    }

    /** Most players seen on one side during this arena match, so the layout doesn't shrink back. */
    private var arenaMost = 0

    /** Arena bracket from the players seen on either side (you and party1-4, arena1-5): 2, 3 or 5. */
    private fun arenaSize(): Int {
        var most = maxOf((0..4).count { LinkState.unit(it) != null }, (30..34).count { LinkState.unit(it) != null })
        if (LinkState.mode == LinkState.MODE_ARENA) { arenaMost = maxOf(arenaMost, most); most = arenaMost } else arenaMost = 0
        return when { most <= 2 -> 2; most == 3 -> 3; else -> 5 }
    }

    /** Nearby rows per column: enough for the larger of the two maximums, 5 to 10. */
    private fun nearbyRows(): Int {
        val most = maxOf(PadSettings.int(PadSettings.NEARBY_MAX_ENEMIES), PadSettings.int(PadSettings.NEARBY_MAX_FRIENDS))
        return ((most + 1) / 2).coerceIn(5, 10)
    }

    /**
     * Nearby units: click the unit's nameplate, which targets exactly that unit even when
     * several share a name. The camera pauses for the click, like for any touch.
     */
    private fun clickPlate(slot: Int, u: LinkUnit) {
        val w = xServer.screenInfo.width.toInt()
        val h = xServer.screenInfo.height.toInt()
        PadBridge.trackpadTouch(true)
        xServer.injectPointerMove(u.plateX * w / 255, u.plateY * h / 255)
        // Let WoW draw a few frames with the cursor over the plate before clicking (a click in
        // the same frame as the move hits whatever was under the old position), and wait for
        // two packets drawn since the move: they say whether the cursor is over a UI frame
        // (quest tracker, chat...) that would take the click instead of the plate.
        val since = LinkState.packets
        val movedAt = System.currentTimeMillis()
        fun finish() {
            if (PadSettings.bool(PadSettings.TARGET_CURSOR_CENTER)) {
                postDelayed({
                    xServer.injectPointerMove(w / 2, h / 2)
                    PadBridge.trackpadTouch(false)
                }, PLATE_HOVER_MS)
            } else PadBridge.trackpadTouch(false)
        }
        val decide = object : Runnable {
            override fun run() {
                val fresh = LinkState.packets - since >= 2
                if (!fresh && System.currentTimeMillis() - movedAt < PLATE_WAIT_MAX_MS) { postDelayed(this, 20); return }
                if (fresh && LinkState.cursorOverUi) {
                    finish()
                    targetByKey(slot, u)
                    return
                }
                // Re-aim at the newest position (the plate moves while you walk), then click.
                val latest = LinkState.unit(slot)?.takeIf { it.name == u.name && it.plateX > 0 && it.plateY > 0 }
                val aimDelay = if (latest != null && (latest.plateX != u.plateX || latest.plateY != u.plateY)) {
                    xServer.injectPointerMove(latest.plateX * w / 255, latest.plateY * h / 255)
                    50L
                } else 0L
                postDelayed({
                    xServer.injectPointerButtonPress(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
                    postDelayed({
                        xServer.injectPointerButtonRelease(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
                        // WoW reads the cursor position once per frame: move it back only after a
                        // few frames, or the release is seen at the new position and misses the plate.
                        finish()
                    }, 40)
                }, aimDelay)
            }
        }
        postDelayed(decide, PLATE_HOVER_MS)
    }

    private fun sharedName(slot: Int, u: LinkUnit): Boolean =
        u.mustClick || NEARBY_SLOTS.any { it != slot && LinkState.unit(it)?.name == u.name }

    /** WoWMobileLink's clear-target key (Alt+Ctrl+B). */
    private fun clearTarget() = chordCtrlAlt(XKeycode.KEY_F2)

    /** Focus your target (Alt+Ctrl+F3). */
    private fun focusTarget() = chordCtrlAlt(XKeycode.KEY_F3)

    /** Ctrl+Alt+[k]: WoWMobileLink's keys F1 target focus, F2 clear target, F3 set focus, F4 clear focus. */
    private fun chordCtrlAlt(k: XKeycode) = SlashTyper.chord(xServer, k, false, onSuspendModifiers, onResumeModifiers)

    /** Target without clicking: the slot's hidden key, else type /target. */
    private fun targetByKey(slot: Int, u: LinkUnit) {
        val key = targetKey(slot)
        if (slot in 90..99 && !u.noKey) SlashTyper.chord(xServer, DIGIT_KEYS[slot - 90], true, onSuspendModifiers, onResumeModifiers, ctrl = false)
        else if (slot in RAID_SLOTS && !u.noKey) SlashTyper.chord(xServer, RAID_KEYS[slot - 100], true, onSuspendModifiers, onResumeModifiers, ctrl = false)
        else if (key != null && !u.noKey) SlashTyper.chord(xServer, key.first, key.second, onSuspendModifiers, onResumeModifiers)
        else commandFor(slot, u)?.let { SlashTyper.type(xServer, it, onSuspendModifiers, onResumeModifiers) }
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
            in 70..79 -> PUNCT_KEYS[slot - 70] to false
            in 80..89 -> PUNCT_KEYS[slot - 80] to true
            FOCUS_SLOT -> XKeycode.KEY_F1 to false
            else -> null
        }
    }

    private fun commandFor(slot: Int, unit: LinkUnit): String? = when (slot) {
        0 -> "/target player"
        in 1..4 -> "/target party$slot"
        in 30..34 -> "/target arena${slot - 29}"
        in RAID_SLOTS -> "/target raid${slot - 99}"
        FOCUS_SLOT -> "/target focus"
        // Names are typed letter by letter; names with letters the pad can't type are skipped.
        else -> unit.name.takeIf { n -> n.isNotEmpty() && n.all { SlashTyper.canType(it) } }?.let { "/targetexact $it" }
    }

    private inner class UnitBox(var slot: Int) : View(context) {
        private var unit: LinkUnit? = null
        /** Double tap: when the last tap was, and a clear-target waiting to see if a second tap comes. */
        private var lastTapAt = 0L
        private var lastTapSlot = -1
        private val pendingClear = Runnable { clearTarget() }
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
            // Border: yellow for your target, black for the rest.
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = dp(if (u.isTarget) 5 else 2).toFloat()
            fill.color = if (u.isTarget) Color.rgb(255, 209, 0) else Color.BLACK
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
                    val now = System.currentTimeMillis()
                    val second = now - lastTapAt < DOUBLE_TAP_MS && lastTapSlot == slot && slot != FOCUS_SLOT
                    lastTapAt = if (second) 0L else now
                    lastTapSlot = slot
                    // Double tap: the first tap targeted it (or was about to clear it): focus it.
                    if (second) { removeCallbacks(pendingClear); focusTarget(); return true }
                    // Already your target: tap again to clear the target (a focus stays focused),
                    // once it's clear this isn't the start of a double tap.
                    if (u.isTarget) {
                        if (slot == FOCUS_SLOT) clearTarget() else postDelayed(pendingClear, DOUBLE_TAP_MS)
                        return true
                    }
                    // Click the plate only when its name is shared by another nearby unit: the
                    // key targets by name, which works while moving, where a plate's position
                    // is already out of date by the time the click lands.
                    if (u.plateX > 0 && u.plateY > 0 && sharedName(slot, u)) clickPlate(slot, u) else targetByKey(slot, u)
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

    private fun slash(command: String) = SlashTyper.type(xServer, command, onSuspendModifiers, onResumeModifiers)

    /** Taps a key (WoW's default B = bags, M = map) with any held modifiers let go for the moment. */
    private fun key(k: XKeycode) {
        val mods = onSuspendModifiers()
        PadBridge.controllerModifierHold?.invoke(true)
        xServer.injectKeyPress(k)
        xServer.injectKeyRelease(k)
        PadBridge.controllerModifierHold?.invoke(false)
        onResumeModifiers(mods)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The mode picked this game session; null = Nearby. */
        private var sessionMode: Mode? = null

        /** Back to Nearby: called when the game starts. */
        @JvmStatic fun resetMode() { sessionMode = null }
        private const val MENU_GREY = 0xFF4A4A4A.toInt()
        /** Two taps on a box within this time: target it and set it as your focus. */
        private const val DOUBLE_TAP_MS = 350L
        private const val PLATE_HOVER_MS = 80L
        /** Nearby slots (50-59 are the NPC tab, which repeats friendly NPCs). */
        private val NEARBY_SLOTS = (40..49) + (60..89)
        /** Alt+Shift+1-0: battleground slots 90-99 (see KEYS in WoWMobileLink.lua). */
        private val DIGIT_KEYS = "1234567890".map { XKeycode.valueOf("KEY_" + it) }
        /** Alt+Shift+letter (no L): raid slots 100-124 (see KEYS in WoWMobileLink.lua). */
        private val RAID_KEYS = "ABCDEFGHIJKMNOPQRSTUVWXYZ".map { XKeycode.valueOf("KEY_" + it) }
        /** Keys for slots 70-79 (with Ctrl+Alt) and 80-89 (Ctrl+Alt+Shift); see KEYS in WoWMobileLink.lua. */
        private val PUNCT_KEYS = listOf(
            XKeycode.KEY_MINUS, XKeycode.KEY_EQUAL, XKeycode.KEY_BRACKET_LEFT, XKeycode.KEY_BRACKET_RIGHT, XKeycode.KEY_SEMICOLON,
            XKeycode.KEY_APOSTROPHE, XKeycode.KEY_COMMA, XKeycode.KEY_PERIOD, XKeycode.KEY_SLASH, XKeycode.KEY_GRAVE,
        )
        /** Longest wait for the addon to say what's under the cursor; then click anyway. */
        private const val PLATE_WAIT_MAX_MS = 400L

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
