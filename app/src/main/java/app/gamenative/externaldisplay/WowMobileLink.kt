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
data class LinkUnit(val slot: Int, val classId: Int, val hp: Int, val flags: Int, val name: String, val seenAt: Long, val plateX: Int = 0, val plateY: Int = 0, val num: Int = 0) {
    /** Name as shown: "(2) Wild Boar" when several nearby units share it (same number as on its plate). */
    val label get() = when {
        slot == FOCUS_SLOT -> "Focus: $name"
        num and 127 > 0 && slot !in RAID_SLOTS -> "(${num and 127}) $name"
        else -> name
    }
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

    /** Records rotate through packets; a slot not refreshed for this long is gone. */
    private const val STALE_MS = 1500L

    @Volatile var mode = MODE_WORLD
        private set
    @Volatile var lastPacketAt = 0L
        private set
    /** Whether the cursor was over a UI frame (not the game world) in the last packet. */
    @Volatile var cursorOverUi = false
        private set
    /** Packets received so far (to wait for ones drawn after a cursor move). */
    @Volatile var packets = 0L
        private set
    private val units = HashMap<Int, LinkUnit>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Main thread only. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun unit(slot: Int): LinkUnit? = units[slot]?.takeIf { System.currentTimeMillis() - it.seenAt < STALE_MS }

    internal fun apply(rawMode: Int, records: List<LinkUnit>) {
        val newMode = rawMode and 127
        cursorOverUi = rawMode and 128 != 0
        packets++
        if (newMode != mode) units.clear()
        mode = newMode
        lastPacketAt = System.currentTimeMillis()
        records.forEach { units[it.slot] = it }
        val now = System.currentTimeMillis()
        units.values.removeAll { now - it.seenAt >= STALE_MS }
        listeners.toList().forEach { it() }
    }

    fun clear() {
        units.clear()
        mode = MODE_WORLD
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
                    readOnce()?.let { (mode, records) -> main.post { LinkState.apply(mode, records) } }
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

    private fun readOnce(): Pair<Int, List<LinkUnit>>? {
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

    private fun decode(px: ByteArray, w: Int, redFirst: Boolean): Pair<Int, List<LinkUnit>>? {
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
        if (bytes.size < 9 || bytes[0] != 'W'.code || bytes[1] != 'M'.code || bytes[2] != 3) return null
        val mode = bytes[4]
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
            records += LinkUnit(slot, cls, hp, flags, name, now, px, py, num)
        }
        if (p + 2 > bytes.size) return null
        val crc = (bytes[p] shl 8) or bytes[p + 1]
        if (crc != crc16(bytes, p)) return null
        return mode to records
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
