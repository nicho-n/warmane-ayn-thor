package app.gamenative.externaldisplay

import android.os.Handler
import android.os.Looper
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import java.nio.ByteOrder

/**
 * wow-mobile: unit info from the WoWMobileLink addon, for the bottom-screen Target page.
 * The addon draws a row of coloured cells in the hidden rows below the visible picture
 * (see ViewTransformation.cropBottom); [WowMobileLinkReader] reads it from the game
 * window's image buffer. Format: see WoWMobileLink.lua.
 */
/**
 * One buff or debuff from the addon (protocol 4): [icon] is the hash of the icon's file name
 * (see IconStore), [kind] its category, dispel type and whether it's a debuff.
 */
data class LinkAura(val icon: Int, val time: Int, val kind: Int, val receivedAt: Long) {
    /** 1 immunity, 2 crowd control, 3 silence, 4 disarm, 5 root, 6 defensive cooldown, 7 your own debuff on an enemy, 0 dispellable. */
    val category get() = kind and 7
    /** 0 none, 1 magic, 2 curse, 3 poison, 4 disease. */
    val dispelType get() = (kind shr 3) and 7
    val isDebuff get() = kind and 64 != 0
    val hasDuration get() = time != 255
    /** Seconds left now, counted down since the packet arrived; -1 without a duration. */
    fun secondsLeft(now: Long): Int {
        if (time == 255) return -1
        val sent = if (time <= 180) time else (time - 180) * 60
        return (sent - ((now - receivedAt) / 1000).toInt()).coerceAtLeast(0)
    }
}

data class LinkUnit(val slot: Int, val classId: Int, val hp: Int, val flags: Int, val name: String, val seenAt: Long, val plateX: Int = 0, val plateY: Int = 0, val num: Int = 0,
                    /** Current health, -1 unknown (protocol 4). */
                    val hpAmount: Int = -1, val auras: List<LinkAura> = emptyList()) {
    /** Name as shown (its number is drawn apart, in the box's tab). */
    val label get() = when {
        slot == FOCUS_SLOT -> "Focus: $name"
        else -> name
    }
    /** Its plate number (0 none). */
    val number get() = if (slot == FOCUS_SLOT || slot in RAID_SLOTS) 0 else num and 127
    /** Its number's colour, an index into the addon's list (one per number, round again after 8), as on its plate. */
    val numberColor get() = (number - 1).coerceAtLeast(0) % 8
    /** Raid slots (100-124, raid1-25): the raid group, 1-8. */
    val raidGroup get() = if (slot in RAID_SLOTS) num and 127 else 0
    /** The addon says a tap must click the plate (name shared with another plate or with an off-screen target). */
    val mustClick get() = num and 128 != 0
    val dead get() = flags and 1 != 0
    val notVisible get() = flags and 2 != 0
    val isTarget get() = flags and 4 != 0
    /** No target key yet (appeared during combat): tapping types /target instead. */
    val noKey get() = flags and 128 != 0
    val hpKnown get() = hp in 0..100
}

/** Slots of raid1-25 (see WoWMobileLink.lua). */
val RAID_SLOTS = 100..124
/** The focus unit's slot. */
const val FOCUS_SLOT = 125
/** Your current target (only to know there is one). */
const val TARGET_SLOT = 129

object LinkState {
    const val MODE_WORLD = 0
    const val MODE_DUNGEON = 1
    const val MODE_RAID = 2
    const val MODE_BATTLEGROUND = 3
    const val MODE_ARENA = 4

    /**
     * Records rotate through packets, as many as fit in each (fewer with buffs/debuffs). A slot
     * not refreshed for [staleMs] is gone: at least this long, or longer when a full round of
     * all the records takes longer (big raids and battlegrounds), so boxes don't flicker.
     */
    private const val MIN_STALE_MS = 1500L
    private const val MAX_STALE_MS = 6000L
    private const val PACKET_MS = 100L
    @Volatile private var staleMs = MIN_STALE_MS
    /** Records per packet, smoothed. */
    private var perPacket = 4f

    @Volatile var mode = MODE_WORLD
        private set
    @Volatile var lastPacketAt = 0L
        private set
    /** Whether the cursor was over a UI frame (not the game world) in the last packet. */
    @Volatile var cursorOverUi = false
        private set
    /** Whether a spell was waiting to be placed on the ground in the last packet. */
    @Volatile var spellTargeting = false
        private set

    /** A spell waits to be placed on the ground (from a packet in the last second). */
    fun placingSpell() = spellTargeting && System.currentTimeMillis() - lastPacketAt < 1000
    /** What the addon is sending: state only (F10 open key), units (Target page) or the quest log. */
    const val KIND_STATE = 0
    const val KIND_UNITS = 1
    const val KIND_QUESTS = 2
    @Volatile var kind = KIND_STATE
        private set
    /** Since when packets of [kind] have been coming in. */
    @Volatile var kindSince = 0L
        private set

    private fun setKind(k: Int) {
        if (k != kind) { kind = k; kindSince = System.currentTimeMillis(); android.util.Log.i("WowMobileLink", "packets now kind $k") }
    }

    /** Packets received so far (to wait for ones drawn after a cursor move). */
    @Volatile var packets = 0L
        private set
    private val units = HashMap<Int, LinkUnit>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Main thread only. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun unit(slot: Int): LinkUnit? {
        val now = System.currentTimeMillis()
        val u = units[slot]?.takeIf { now - it.seenAt < staleMs } ?: return null
        // A numbered mob that moved to another box: its old box lingers until it goes stale,
        // and showed it twice. Numbers are unique, so the newer box with its name and number wins.
        if (u.number > 0 && units.values.any { it !== u && it.number == u.number && it.name == u.name && it.seenAt > u.seenAt && now - it.seenAt < staleMs }) return null
        return u
    }

    /** A quest log packet came in: the link is up, though there are no units in it. */
    internal fun touch() { lastPacketAt = System.currentTimeMillis(); packets++; setKind(KIND_QUESTS) }

    internal fun apply(rawMode: Int, records: List<LinkUnit>, rawTotal: Int = records.size) {
        // slotCount 255: a state-only packet (WoWMobileLink 1.40+); older ones send 0 there.
        setKind(if (rawTotal == 255) KIND_STATE else KIND_UNITS)
        val total = if (rawTotal == 255) 0 else rawTotal
        // How long a full round takes: packets per round, twice over for a missed packet or two.
        if (records.isNotEmpty()) perPacket = perPacket * 0.8f + records.size * 0.2f
        val rounds = kotlin.math.ceil(total / perPacket.coerceAtLeast(1f)).toLong()
        staleMs = (rounds * PACKET_MS * 2 + 500).coerceIn(MIN_STALE_MS, MAX_STALE_MS)
        val newMode = rawMode and 63
        cursorOverUi = rawMode and 128 != 0
        spellTargeting = rawMode and 64 != 0
        packets++
        if (newMode != mode) units.clear()
        mode = newMode
        lastPacketAt = System.currentTimeMillis()
        records.forEach { units[it.slot] = it }
        val now = System.currentTimeMillis()
        units.values.removeAll { now - it.seenAt >= staleMs }
        listeners.toList().forEach { it() }
    }

    fun clear() {
        units.clear()
        mode = MODE_WORLD
        spellTargeting = false
        lastPacketAt = 0
        listeners.toList().forEach { it() }
    }
}

class WowMobileLinkReader(private val xServer: XServer) {
    companion object {
        private const val CELLS = 320
        private const val INTERVAL_MS = 100L
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                try {
                    readOnce()?.let { (header, records) ->
                        // Mode -1: a quest log packet, already handed to QuestState.
                        main.post { if (header.first >= 0) LinkState.apply(header.first, records, header.second) else LinkState.touch() }
                    }
                } catch (_: Exception) {
                }
                try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }, "WowMobileLinkReader").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        main.post { LinkState.clear() }
    }

    /**
     * The window holding the game's picture. Wine draws Vulkan output into a child window, and the
     * wow.exe top-level window itself stays black, so try every large mapped window and keep the
     * first whose middle row has any non-black pixel. Re-checked whenever reading fails.
     */
    @Volatile private var gameWindow: Window? = null
    private var treeLogged = false

    private fun candidates(): List<Window> {
        val out = ArrayList<Window>()
        fun visit(w: Window, depth: Int, sb: StringBuilder?) {
            for (child in w.children) {
                sb?.append("  ".repeat(depth))?.append("'${child.className}' ${child.width}x${child.height} mapped=${child.attributes.isMapped}\n")
                if (child.attributes.isMapped && child.content != null && child.width >= 640 && child.height >= 360) out += child
                visit(child, depth + 1, sb)
            }
        }
        val sb = if (!treeLogged) StringBuilder("window tree:\n") else null
        visit(xServer.windowManager.rootWindow, 0, sb)
        if (sb != null) { treeLogged = true; android.util.Log.i("WowMobileLink", sb.toString()) }
        // Largest first; for equal sizes, deeper windows (children, visited later) first.
        return out.withIndex().sortedWith(compareByDescending<IndexedValue<Window>> { it.value.width.toInt() * it.value.height.toInt() }.thenByDescending { it.index }).map { it.value }
    }

    private fun hasPicture(window: Window): Boolean {
        val d = window.content ?: return false
        val bytes = readGpuRow(d, d.height / 2) ?: return false
        return bytes.indices.step(4 * 16).any { i -> (bytes[i].toInt() and 0xFF) > 8 || (bytes[i + 1].toInt() and 0xFF) > 8 || (bytes[i + 2].toInt() and 0xFF) > 8 }
    }

    /** Forget the window picked so far, so the next look searches again (the game may not have been up yet). */
    internal fun forgetGameWindow() { gameWindow = null }

    internal fun findGameWindow(): Window? {
        gameWindow?.let { w -> if (w.attributes.isMapped && w.content != null) return w }
        val list = candidates()
        // Only a window that shows a picture is kept. While the game is still black (loading),
        // the largest window is used for this look only: keeping it locked onto the empty
        // wow.exe top-level window, and the login screen was then never seen.
        val withPicture = list.firstOrNull { hasPicture(it) }
        gameWindow = withPicture
        withPicture?.let { android.util.Log.i("WowMobileLink", "using window '${it.className}' ${it.width}x${it.height} id ${it.id}") }
        return withPicture ?: list.firstOrNull()
    }

    /** Which GPU row (flipped or not) and colour order held the data last time. */
    @Volatile private var way: Pair<Boolean, Boolean>? = null

    private var lastLog = 0L
    private fun log(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLog < 3000) return
        lastLog = now
        android.util.Log.i("WowMobileLink", msg)
    }

    /** (mode, total records), and this packet's records. */
    private fun readOnce(): Pair<Pair<Int, Int>, List<LinkUnit>>? {
        val window = findGameWindow()
        if (window == null) { log("no game window"); return null }
        val drawable = window.content ?: run { log("window has no content"); return null }
        val w = drawable.width.toInt()
        val h = drawable.height.toInt()
        if (w < CELLS || h < 8) return null
        // The band is the bottom 0.4% of the window; sample its middle row.
        val y = h - 1 - (h * 0.002f).toInt().coerceAtLeast(1)
        // On the Vulkan path the CPU-side copy isn't kept up to date, so read the GPU texture the
        // screen shows. Each GPU read stalls the GPU for a moment, so once a row and colour
        // order have worked, only that one is read; the CPU copy is a fallback.
        val known = way
        if (known != null) {
            val (flipped, redFirst) = known
            val px = readGpuRow(drawable, if (flipped) h - 1 - y else y)
            val result = px?.let { decode(it, w, redFirst) }
            if (result != null) return result
            way = null
        }
        for (flipped in booleanArrayOf(false, true)) {
            val px = readGpuRow(drawable, if (flipped) h - 1 - y else y) ?: continue
            for (redFirst in booleanArrayOf(true, false)) {
                val result = decode(px, w, redFirst)
                if (result != null) {
                    way = flipped to redFirst
                    return result
                }
            }
        }
        val cpu = drawable.getImage(0, y.toShort(), w.toShort(), 1).order(ByteOrder.LITTLE_ENDIAN)
        val px = ByteArray(w * 4)
        cpu.get(px)
        val result = decode(px, w, redFirst = false) ?: decode(px, w, redFirst = true)
        if (result == null) {
            gameWindow = null
            log("no data row in window '${window.className}' ${w}x$h")
        }
        return result
    }

    internal fun readGpuRow(drawable: com.winlator.xserver.Drawable, row: Int): ByteArray? {
        val renderer = xServer.renderer ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        renderer.readTextureRow(drawable, row) { bytes -> out = bytes; latch.countDown() }
        latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    private fun decode(px: ByteArray, w: Int, redFirst: Boolean): Pair<Pair<Int, Int>, List<LinkUnit>>? {
        val bits = BooleanArray(CELLS * 3)
        for (i in 0 until CELLS) {
            val x = ((i + 0.5f) * w / CELLS).toInt().coerceIn(0, w - 1)
            val b0 = px[x * 4].toInt() and 0xFF
            val b1 = px[x * 4 + 1].toInt() and 0xFF
            val b2 = px[x * 4 + 2].toInt() and 0xFF
            val r = if (redFirst) b0 else b2
            val g = b1
            val b = if (redFirst) b2 else b0
            val v = (if (r > 127) 1 else 0) + (if (g > 127) 2 else 0) + (if (b > 127) 4 else 0)
            bits[i * 3] = v and 4 != 0
            bits[i * 3 + 1] = v and 2 != 0
            bits[i * 3 + 2] = v and 1 != 0
        }
        val bytes = IntArray(bits.size / 8) { n -> (0 until 8).fold(0) { acc, k -> acc * 2 + if (bits[n * 8 + k]) 1 else 0 } }
        if (bytes.size >= 8 && bytes[0] == 'W'.code && bytes[1] == 'Q'.code && bytes[2] in 1..2) return decodeQuests(bytes)
        if (bytes.size >= 10 && bytes[0] == 'W'.code && bytes[1] == 'D'.code && bytes[2] == 1) return decodeQuestDetail(bytes)
        if (bytes.size < 9 || bytes[0] != 'W'.code || bytes[1] != 'M'.code || bytes[2] !in 3..4) return null
        val version = bytes[2]
        val mode = bytes[4]
        val total = bytes[5]
        val count = bytes[6]
        var p = 7
        val now = System.currentTimeMillis()
        val records = ArrayList<LinkUnit>(count)
        repeat(count) {
            if (p + 8 > bytes.size) return null
            val slot = bytes[p]; val cls = bytes[p + 1]; val hp = bytes[p + 2]; val flags = bytes[p + 3]
            val px = bytes[p + 4]; val py = bytes[p + 5]; val num = bytes[p + 6]; val len = bytes[p + 7]
            p += 8
            if (p + len > bytes.size) return null
            val name = String(ByteArray(len) { bytes[p + it].toByte() }, Charsets.UTF_8)
            p += len
            var amount = -1
            val auras = ArrayList<LinkAura>()
            if (version >= 4) {
                if (p + 4 > bytes.size) return null
                val raw = (bytes[p] shl 16) or (bytes[p + 1] shl 8) or bytes[p + 2]
                amount = if (raw == 0xFFFFFF) -1 else raw
                val auraCount = bytes[p + 3]
                p += 4
                if (p + auraCount * 5 > bytes.size) return null
                repeat(auraCount) {
                    auras += LinkAura((bytes[p] shl 16) or (bytes[p + 1] shl 8) or bytes[p + 2], bytes[p + 3], bytes[p + 4], now)
                    p += 5
                }
            }
            records += LinkUnit(slot, cls, hp, flags, name, now, px, py, num, amount, auras)
        }
        if (p + 2 > bytes.size) return null
        val crc = (bytes[p] shl 8) or bytes[p + 1]
        if (crc != crc16(bytes, p)) return null
        return (mode to total) to records
    }

    /** The quest log strip (see buildQuests in WoWMobileLink.lua): handed to QuestState. */
    private fun decodeQuests(bytes: IntArray): Pair<Pair<Int, Int>, List<LinkUnit>>? {
        val version = bytes[2]
        val total = bytes[4]
        val count = bytes[5]
        var p = 6
        val lines = ArrayList<Pair<Int, QuestLine>>(count)
        repeat(count) {
            val head = if (version >= 2) 5 else 4
            if (p + head > bytes.size) return null
            val index = bytes[p]; val type = bytes[p + 1]; val flags = bytes[p + 2]
            val logIndex = if (version >= 2) bytes[p + 3] else 0
            val len = bytes[p + head - 1]
            p += head
            if (p + len > bytes.size) return null
            lines += index to QuestLine(type, flags, String(ByteArray(len) { bytes[p + it].toByte() }, Charsets.UTF_8), logIndex)
            p += len
        }
        if (p + 2 > bytes.size) return null
        if ((bytes[p] shl 8) or bytes[p + 1] != crc16(bytes, p)) return null
        main.post { QuestState.apply(total, lines) }
        return (-1 to 0) to emptyList()
    }

    /** A piece of one quest's text (see buildDetail in WoWMobileLink.lua): handed to QuestDetails. */
    private fun decodeQuestDetail(bytes: IntArray): Pair<Pair<Int, Int>, List<LinkUnit>>? {
        val logIndex = bytes[4]; val pieces = bytes[5]; val piece = bytes[6]; val len = bytes[7]
        var p = 8
        if (p + len + 2 > bytes.size) return null
        val text = String(ByteArray(len) { bytes[p + it].toByte() }, Charsets.UTF_8)
        p += len
        if ((bytes[p] shl 8) or bytes[p + 1] != crc16(bytes, p)) return null
        main.post { QuestDetails.apply(logIndex, pieces, piece, text) }
        return (-1 to 0) to emptyList()
    }

    private fun crc16(bytes: IntArray, n: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until n) {
            crc = crc xor (bytes[i] shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}
