package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One line of the quest log from the WoWMobileLink addon (see buildQuests in WoWMobileLink.lua). */
data class QuestLine(val type: Int, val flags: Int, val text: String,
                     /** A quest's index in WoW's quest log (for /wml q, track, abandon); 0 for other lines. */
                     val logIndex: Int = 0) {
    val isZone get() = type == 1
    val isQuest get() = type == 2
    val isObjective get() = type == 3
    /** Zone: the one you're in. Quest: complete. Objective: done. */
    val flagged get() = flags and 1 != 0
    val failed get() = isQuest && flags and 2 != 0
    val tracked get() = isQuest && flags and 8 != 0
}

/** The text of the quest last asked for with "/wml q N", in pieces as they come in. Main thread only. */
object QuestDetails {
    var logIndex = 0
        private set
    private var pieces = arrayOfNulls<String>(0)
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Forget what came for another quest; [index] is the one being asked for. */
    fun request(index: Int) {
        logIndex = index
        pieces = arrayOfNulls(0)
    }

    /** The whole text once every piece is in, else null. */
    fun text(index: Int): String? =
        if (index != logIndex || pieces.isEmpty() || pieces.any { it == null }) null else pieces.joinToString("")

    internal fun apply(index: Int, count: Int, piece: Int, text: String) {
        if (index != logIndex || count <= 0 || piece !in 1..count) return
        if (pieces.size != count) pieces = arrayOfNulls(count)
        if (pieces[piece - 1] == text) return
        pieces[piece - 1] = text
        listeners.toList().forEach { it() }
    }
}

/** The quest log as the addon last sent it: lines in log order. Main thread only. */
object QuestState {
    private val lines = arrayOfNulls<QuestLine>(256)
    var total = 0
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun lines(): List<QuestLine> = (1..total).mapNotNull { lines[it] }

    internal fun apply(newTotal: Int, received: List<Pair<Int, QuestLine>>) {
        var changed = newTotal != total
        total = newTotal
        for (i in total + 1 until lines.size) lines[i] = null
        for ((index, line) in received) {
            if (index in 1..total && lines[index] != line) { lines[index] = line; changed = true }
        }
        if (changed) listeners.toList().forEach { it() }
    }
}

/**
 * wow-mobile: the Quests page. The quest log like Questie's tracker: zones, each quest's title
 * and its objectives below, in two columns, a page at a time. Tap a zone's name to fold the
 * whole zone (folded zones go to the end), a quest's title or an objective to open the quest's
 * page. The bottom strip shows how many quests are in the log and turns the pages.
 */
class QuestLogView(
    context: Context,
    private val theme: PadTheme,
    /** Types a chat command in the game. */
    private val slash: (String) -> Unit,
) : LinearLayout(context) {
    companion object {
        /** Kept while the app runs: the page and the folded zones. */
        private var page = 0
        private val foldedZones = HashSet<String>()
        private val GOLD = Color.rgb(255, 209, 0)
        private val GREEN = Color.rgb(64, 255, 64)
        private val RED = Color.rgb(255, 64, 64)
        private const val TITLE_SP = 13f
        private const val OBJECTIVE_SP = 13f
        private const val ZONE_SP = 13f
        private val QUEST_REGULAR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        private val QUEST_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        /** Every line (zone, title, objective) the same size and spacing, so the columns' lines line up. */
        private const val LINE_TOP = 2
        /** Boxes and panels: a black edge, so they don't look like buttons (those have visible borders). */
        private val BOX_EDGE = Color.BLACK
        private val PLAIN_EDGE = Color.rgb(110, 110, 110)
        private val RED_EDGE = Color.rgb(200, 60, 60)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val pageText = TextView(context)
    private val countText = TextView(context)
    /** The quest box: the two columns, and the count and page buttons along its bottom. */
    private val area = LinearLayout(context).apply { orientation = VERTICAL }
    private val columnRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val columns = List(2) { LinearLayout(context).apply { orientation = VERTICAL } }
    /** The open quest's page, over the quests: title, text, Close / Track / Abandon. */
    private val details = LinearLayout(context).apply { orientation = VERTICAL }
    private var detailQuest: QuestLine? = null
    private var confirmAbandon = false
    private val update: () -> Unit = { render(); if (detailQuest != null) renderDetails() }

    init {
        orientation = VERTICAL
        val prev = TextView(context).apply {
            styleButton(this, Look.PLAIN); text = "◀"; small(this)
            setOnClickListener { PadSettings.haptic(this); page--; render() }
        }
        val next = TextView(context).apply {
            styleButton(this, Look.PLAIN); text = "▶"; small(this)
            setOnClickListener { PadSettings.haptic(this); page++; render() }
        }
        pageText.apply {
            setTextColor(theme.text); typeface = theme.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minWidth = dp(46)
        }
        countText.apply {
            setTextColor(theme.text); typeface = theme.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.8f
            gravity = Gravity.BOTTOM or Gravity.START
        }

        area.background = questBox()
        area.setPadding(dp(6), dp(4), dp(6), dp(2))
        columns.forEach { col ->
            col.setPadding(dp(4), 0, dp(4), 0)
            columnRow.addView(col, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        area.addView(columnRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pager = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(2), 0, 0)
            addView(countText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(prev)
            addView(pageText)
            addView(next)
        }
        area.addView(pager, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        details.apply {
            background = solidPanel()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = GONE
            isClickable = true
        }
        val stack = FrameLayout(context).apply {
            addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(details, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        addView(stack, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** An opaque panel with a black edge, so the quests behind don't show through. */
    private fun solidPanel() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.rgb(24, 21, 18))
        cornerRadius = 12 * density
        setStroke(dp(2), BOX_EDGE)
    }

    /** The quest box: set apart by a slightly lighter fill and a black edge, so it stays in the background. */
    private fun questBox() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.rgb(30, 27, 24))
        cornerRadius = 12 * density
        setStroke(dp(1), BOX_EDGE)
    }

    /**
     * Border colours with one job each: gold only for what's selected or confirms; grey for
     * ordinary buttons; red for destructive ones (Abandon).
     */
    private enum class Look { SELECTED, PLAIN, DANGER, CONFIRM_DANGER }

    /** The page buttons: smaller than the other buttons. */
    private fun small(t: TextView) = t.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun styleButton(t: TextView, look: Look) = t.apply {
        setTextColor(if (look == Look.SELECTED || look == Look.CONFIRM_DANGER) theme.textPressed else theme.text)
        typeface = theme.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = when (look) {
            Look.SELECTED -> theme.buttonBackground(density, 10f, true)
            Look.PLAIN -> theme.buttonBackground(density, 10f, false, accent = PLAIN_EDGE)
            Look.DANGER -> theme.buttonBackground(density, 10f, false, accent = RED_EDGE)
            Look.CONFIRM_DANGER -> theme.buttonBackground(density, 10f, true, accent = RED_EDGE)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        QuestState.addListener(update)
        QuestDetails.addListener(update)
        render()
    }

    override fun onDetachedFromWindow() {
        QuestState.removeListener(update)
        QuestDetails.removeListener(update)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { render() }
    }

    /** One quest as shown: its title line and objectives. */
    private class Quest(val title: QuestLine, val objectives: List<QuestLine>)

    /** The log's zones in order, each with its quests. */
    private fun zones(lines: List<QuestLine>): List<Pair<String, List<Quest>>> {
        val out = ArrayList<Pair<String, ArrayList<Quest>>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isZone) { out += line.text to ArrayList(); i++; continue }
            if (!line.isQuest) { i++; continue }
            val objectives = ArrayList<QuestLine>()
            var j = i + 1
            while (j < lines.size && lines[j].isObjective) { objectives += lines[j]; j++ }
            if (out.isEmpty()) out += "" to ArrayList()
            out.last().second += Quest(line, objectives)
            i = j
        }
        return out
    }

    private fun render() {
        val lines = QuestState.lines()
        countText.text = "${lines.count { it.isQuest }} / 25 quests"
        columns.forEach { it.removeAllViews() }
        val colWidth = columns[0].width - columns[0].paddingLeft - columns[0].paddingRight
        val colHeight = columnRow.height
        if (colWidth <= 0 || colHeight <= 0) { pageText.text = ""; return }
        if (lines.isEmpty()) { columns[0].addView(note("Waiting for the quest log…")); pageText.text = ""; return }

        // Pages of two columns. A quest stays in one piece, and so does a zone's name with its
        // first quest. The right column carries on under the left one's zone; a page that starts
        // in the middle of a zone names it again at its top.
        fun measure(v: View): Int {
            v.measure(MeasureSpec.makeMeasureSpec(colWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return v.measuredHeight
        }
        val pages = ArrayList<List<List<View>>>()
        var cols = ArrayList<List<View>>()
        var col = ArrayList<View>()
        var used = 0
        var lastZone: String? = null
        fun newColumn() {
            cols.add(col); col = ArrayList(); used = 0
            if (cols.size == 2) { pages.add(cols); cols = ArrayList(); lastZone = null }
        }
        fun place(block: List<View>, zone: String) {
            var zoneView: View? = if (zone != lastZone && zone.isNotEmpty()) zoneRow(zone) else null
            var h = block.sumOf { measure(it) } + (zoneView?.let { measure(it) } ?: 0)
            if (used > 0 && used + h > colHeight) {
                newColumn()
                zoneView = if (zone != lastZone && zone.isNotEmpty()) zoneRow(zone) else null
                h = block.sumOf { measure(it) } + (zoneView?.let { measure(it) } ?: 0)
            }
            // The right column carrying on a zone starts a line down, level with the left column's
            // first quest (the left one has the zone's name on that first line).
            if (zoneView == null && col.isEmpty() && cols.size == 1) {
                val spacer = row(" ", theme.text, ZONE_SP, false, 0, LINE_TOP)
                col.add(spacer)
                h += measure(spacer)
            }
            zoneView?.let { col.add(it) }
            lastZone = zone
            col.addAll(block)
            used += h
        }
        // Folded zones go at the end of the list, so the zones you're working on come first.
        val (open, closed) = zones(lines).partition { it.first !in foldedZones }
        for ((zone, quests) in open + closed) {
            // A folded zone is just its name.
            if (zone in foldedZones || quests.isEmpty()) { lastZone = null; place(emptyList(), zone); continue }
            for (q in quests) {
                val block = ArrayList<View>()
                block += questRow(q)
                q.objectives.forEach { block += objectiveRow(it, q.title) }
                place(block, zone)
            }
        }
        if (col.isNotEmpty()) newColumn()
        if (cols.isNotEmpty()) pages.add(cols)
        if (pages.isEmpty()) { pageText.text = ""; return }
        page = page.coerceIn(0, pages.size - 1)
        pages[page].forEachIndexed { i, views -> views.forEach { columns[i].addView(it) } }
        // Both columns start at the same height: no space above either one's first line.
        columns.forEach { c -> c.getChildAt(0)?.let { v -> v.setPadding(v.paddingLeft, 0, v.paddingRight, v.paddingBottom) } }
        pageText.text = "${page + 1}/${pages.size}"
    }

    private fun note(text: String) = row(text, theme.text, 15f, false, 6, 10)

    private fun row(text: String, color: Int, sp: Float, bold: Boolean, indent: Int, top: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        // Quest text in a narrow sans-serif like Questie's tracker (buttons keep the pad's serif).
        typeface = if (bold) QUEST_BOLD else QUEST_REGULAR
        setShadowLayer(2f, 0f, 1f, Color.BLACK)
        setPadding(dp(indent), dp(top), dp(2), dp(1))
    }

    /** A zone's name; a tap folds or unfolds the whole zone. */
    private fun zoneRow(zone: String) = row((if (zone in foldedZones) "▸ " else "▾ ") + zone, theme.text, ZONE_SP, false, 0, LINE_TOP).apply {
        alpha = 0.75f
        setOnClickListener {
            PadSettings.haptic(this)
            if (!foldedZones.remove(zone)) foldedZones.add(zone)
            render()
        }
    }

    private fun titleText(t: QuestLine) = when {
        t.flagged -> "${t.text}  (Complete)"
        t.failed -> "${t.text}  (Failed)"
        else -> t.text
    }

    private fun titleColor(t: QuestLine) = if (t.flagged) GREEN else if (t.failed) RED else GOLD

    /** The quest's title; a tap opens the quest's page. */
    private fun questRow(q: Quest): View {
        val t = q.title
        return row(titleText(t), titleColor(t), TITLE_SP, true, 0, LINE_TOP).apply {
            setOnClickListener { PadSettings.haptic(this); openDetails(t) }
        }
    }

    // ------------------------------------------------------------- the quest's page

    private fun openDetails(t: QuestLine) {
        if (t.logIndex <= 0) return
        detailQuest = t
        confirmAbandon = false
        QuestDetails.request(t.logIndex)
        slash("/wml q ${t.logIndex}")
        details.visibility = VISIBLE
        renderDetails()
    }

    private fun closeDetails() {
        detailQuest = null
        details.visibility = GONE
    }

    /** Letters, digits and spaces of the title (lower case), for the addon to check it's still the same quest. */
    private fun titleCheck(t: QuestLine): String =
        t.text.substringAfter("] ").lowercase().filter { (it.isLetterOrDigit() || it == ' ') && SlashTyper.canType(it) }.trim().take(24)

    private fun renderDetails() {
        val shown = detailQuest ?: return
        // The quest as the log has it now (complete, tracked), by its log index.
        val lines = QuestState.lines()
        val at = lines.indexOfFirst { it.isQuest && it.logIndex == shown.logIndex && it.text == shown.text }
        val t = if (at >= 0) lines[at] else shown
        details.removeAllViews()
        details.addView(row(titleText(t), titleColor(t), 17f, true, 0, 0))
        if (at >= 0) lines.drop(at + 1).takeWhile { it.isObjective }.forEach {
            details.addView(objectiveRow(it, null, indent = 10))
        }
        val body = QuestDetails.text(t.logIndex)
        // The description in a regular (not bold) serif, a little smaller, with some line spacing.
        val bodyView = row(body ?: "Loading…", theme.text, 14f, false, 0, 8).apply {
            typeface = QUEST_REGULAR
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        details.addView(ScrollView(context).apply { addView(bodyView) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun button(label: String, look: Look = Look.PLAIN, onClick: () -> Unit) = TextView(context).apply {
            styleButton(this, look)
            text = label
            setOnClickListener { PadSettings.haptic(this); onClick() }
        }
        fun lp() = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        val check = titleCheck(t)
        val suffix = if (check.isNotEmpty()) " $check" else ""
        if (confirmAbandon) {
            buttons.addView(row("Abandon this quest?", RED, 15f, true, 4, 8), lp())
            buttons.addView(button("Abandon", Look.CONFIRM_DANGER) { slash("/wml abandon ${t.logIndex}$suffix"); closeDetails() }, lp())
            buttons.addView(button("Keep") { confirmAbandon = false; renderDetails() }, lp())
        } else {
            buttons.addView(button("Close") { closeDetails() }, lp())
            buttons.addView(button(if (t.tracked) "Untrack" else "Track") { slash("/wml track ${t.logIndex}$suffix") }, lp())
            buttons.addView(button("Abandon", Look.DANGER) { confirmAbandon = true; renderDetails() }, lp())
        }
        details.addView(buttons, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** An objective in a regular (not bold) serif; in the list a tap opens its [quest]. */
    private fun objectiveRow(o: QuestLine, quest: QuestLine?, indent: Int = 10) =
        (if (o.flagged) row("✓ ${o.text}", Color.rgb(150, 150, 150), OBJECTIVE_SP, false, indent, LINE_TOP)
        else row(o.text, Color.WHITE, OBJECTIVE_SP, false, indent, LINE_TOP)).apply {
            typeface = QUEST_REGULAR
            if (quest != null) setOnClickListener { PadSettings.haptic(this); openDetails(quest) }
        }
}
