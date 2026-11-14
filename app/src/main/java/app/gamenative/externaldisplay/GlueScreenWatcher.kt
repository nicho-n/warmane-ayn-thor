package app.gamenative.externaldisplay

import android.util.Log

/**
 * wow-mobile: tells which of WoW's pre-game screens is showing (login, character select), so the
 * app can auto-login and the controller can work there (A = Enter, D-pad = arrows).
 *
 * Each screen is recognised by buttons that sit in fixed places on it: blue ones on the login
 * screen, red ones on character select. WoW lays its UI out in units of the screen height, so each
 * spot is a distance from the centre / left / right edge on a 1080-high screen, scaled; that holds
 * for any resolution or aspect ratio. Checked once a second (a handful of pixels).
 */
class GlueScreenWatcher(private val reader: WowMobileLinkReader) {
    enum class Screen { NONE, LOGIN, CHARACTER_SELECT }

    private enum class Anchor { LEFT, CENTER, RIGHT }
    private class Spot(val anchor: Anchor, val dx: Int, val y: Int)
    /** [probeY]: the row with most of the screen's buttons, checked first. */
    private class Look(val screen: Screen, val spots: List<Spot>, val needed: Int, val probeY: Int, val matches: (Int, Int, Int) -> Boolean)

    companion object {
        private const val TAG = "GlueScreen"
        private const val POLL_MS = 1000L
        private const val SLOW_POLL_MS = 4000L
        /** Looks in a row without a login / character screen before slowing down (~20 s). */
        private const val IN_WORLD_AFTER = 20
        private val ALL_WAYS = listOf(false to true, false to false, true to true, true to false)

        // Measured on the 3.3.5 screens at 1920x1080.
        private val LOOKS = listOf(
            // Login: blue buttons, fill about rgb 40, 84, 139.
            Look(Screen.LOGIN, listOf(
                Spot(Anchor.CENTER, -115, 810), Spot(Anchor.CENTER, 115, 810), // Login
                Spot(Anchor.LEFT, 40, 811),                                     // Manage Account
                Spot(Anchor.RIGHT, -190, 695), Spot(Anchor.RIGHT, -35, 695),   // Options
                Spot(Anchor.RIGHT, -190, 746),                                  // Cinematics
                Spot(Anchor.RIGHT, -190, 1014), Spot(Anchor.RIGHT, -35, 1014), // Quit
            ), 6, 810) { r, g, b -> r in 15..75 && g in 55..115 && b in 105..175 && b > g && g > r },
            // Character select: red buttons, fill about rgb 100, 17, 9 on screen (about 108, 2, 1
            // in the game's own picture, which is what is read).
            Look(Screen.CHARACTER_SELECT, listOf(
                Spot(Anchor.CENTER, -100, 997), Spot(Anchor.CENTER, 98, 997),  // Enter World
                Spot(Anchor.LEFT, 75, 1022), Spot(Anchor.LEFT, 220, 1022),     // AddOns
                Spot(Anchor.RIGHT, -385, 1022), Spot(Anchor.RIGHT, -215, 1022), // Delete Character
                Spot(Anchor.RIGHT, -160, 1022), Spot(Anchor.RIGHT, -65, 1022), // Back
                Spot(Anchor.RIGHT, -288, 872), Spot(Anchor.RIGHT, -95, 872),   // Create New Character
                Spot(Anchor.RIGHT, -265, 74), Spot(Anchor.RIGHT, -115, 74),    // Change Realm
            ), 9, 1022) { r, g, b -> r in 75..190 && g in 0..50 && b in 0..35 && r > 2 * g && g >= b },
        )
    }

    /** The screen seen at the last check. */
    @Volatile var current = Screen.NONE
        private set
    /** How many checks in a row have seen [current]. */
    @Volatile var streak = 0
        private set

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running) {
                val seen = try { look() } catch (e: Exception) { Screen.NONE }
                if (seen != current) {
                    Log.i(TAG, "screen: $seen")
                    current = seen
                    streak = 1
                } else streak++
                // In the world (no login / character screen for a while), look less often:
                // every look reads pixels back from the GPU, which stalls its work for a moment.
                val wait = if (current == Screen.NONE && streak > IN_WORLD_AFTER) SLOW_POLL_MS else POLL_MS
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
        }, "GlueScreenWatcher").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    private fun look(): Screen {
        // At first only the desktop (explorer.exe) exists: look again until the game's window is up.
        var window = reader.findGameWindow()
        if (window == null || window.className == "explorer.exe") {
            reader.forgetGameWindow()
            window = reader.findGameWindow() ?: return Screen.NONE
        }
        val drawable = window.content ?: return Screen.NONE
        val w = drawable.width.toInt()
        val h = drawable.height.toInt()
        if (w < 320 || h < 240) return Screen.NONE
        val scale = h / 1080f
        // The texture may be stored upside down, and red/blue may be swapped. Once a screen
        // has been recognised the way is known, and only that way is tried from then on.
        val ways = orientation?.let { listOf(it) } ?: ALL_WAYS
        for ((flipped, redFirst) in ways) {
            val rows = HashMap<Int, ByteArray?>()
            fun hits(look: Look, spots: List<Spot>): Int = spots.count { s ->
                val x = when (s.anchor) {
                    Anchor.LEFT -> s.dx * scale
                    Anchor.CENTER -> w / 2f + s.dx * scale
                    Anchor.RIGHT -> w + s.dx * scale
                }.toInt().coerceIn(0, w - 1)
                val y = (s.y * scale).toInt().coerceIn(0, h - 1)
                val row = if (flipped) h - 1 - y else y
                val bytes = rows.getOrPut(row) { reader.readGpuRow(drawable, row) } ?: return@count false
                if (x * 4 + 2 >= bytes.size) return@count false
                val r = (if (redFirst) bytes[x * 4] else bytes[x * 4 + 2]).toInt() and 0xFF
                val g = bytes[x * 4 + 1].toInt() and 0xFF
                val b = (if (redFirst) bytes[x * 4 + 2] else bytes[x * 4]).toInt() and 0xFF
                look.matches(r, g, b)
            }
            for (look in LOOKS) {
                // One row first (a single GPU read); the other rows only if that one matches.
                val probe = look.spots.filter { it.y == look.probeY }
                if (hits(look, probe) < probe.size - 1) continue
                if (hits(look, look.spots) >= look.needed) {
                    orientation = flipped to redFirst
                    return look.screen
                }
            }
        }
        return Screen.NONE
    }

    /** How the game's picture is stored (flipped, red first), once seen. */
    @Volatile private var orientation: Pair<Boolean, Boolean>? = null
}
