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
    private class Look(val screen: Screen, val spots: List<Spot>, val needed: Int, val matches: (Int, Int, Int) -> Boolean)

    companion object {
        private const val TAG = "GlueScreen"
        private const val POLL_MS = 1000L

        // Measured on the 3.3.5 screens at 1920x1080.
        private val LOOKS = listOf(
            // Login: blue buttons, fill about rgb 40, 84, 139.
            Look(Screen.LOGIN, listOf(
                Spot(Anchor.CENTER, -115, 810), Spot(Anchor.CENTER, 115, 810), // Login
                Spot(Anchor.LEFT, 40, 811),                                     // Manage Account
                Spot(Anchor.RIGHT, -190, 695), Spot(Anchor.RIGHT, -35, 695),   // Options
                Spot(Anchor.RIGHT, -190, 746),                                  // Cinematics
                Spot(Anchor.RIGHT, -190, 1014), Spot(Anchor.RIGHT, -35, 1014), // Quit
            ), 6) { r, g, b -> r in 15..75 && g in 55..115 && b in 105..175 && b > g && g > r },
            // Character select: red buttons, fill about rgb 100, 17, 9 on screen (about 108, 2, 1
            // in the game's own picture, which is what is read).
            Look(Screen.CHARACTER_SELECT, listOf(
                Spot(Anchor.CENTER, -100, 997), Spot(Anchor.CENTER, 98, 997),  // Enter World
                Spot(Anchor.LEFT, 75, 1022), Spot(Anchor.LEFT, 220, 1022),     // AddOns
                Spot(Anchor.RIGHT, -385, 1022), Spot(Anchor.RIGHT, -215, 1022), // Delete Character
                Spot(Anchor.RIGHT, -160, 1022), Spot(Anchor.RIGHT, -65, 1022), // Back
                Spot(Anchor.RIGHT, -288, 872), Spot(Anchor.RIGHT, -95, 872),   // Create New Character
                Spot(Anchor.RIGHT, -265, 74), Spot(Anchor.RIGHT, -115, 74),    // Change Realm
            ), 9) { r, g, b -> r in 75..190 && g in 0..50 && b in 0..35 && r > 2 * g && g >= b },
        )
    }

    /** The screen seen at the last check. */
    @Volatile var current = Screen.NONE
        private set
    /** How many checks in a row have seen [current]. */
    @Volatile var streak = 0
        private set

    @Volatile private var running = false
    private var debugPolls = 0

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
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
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
        // The texture may be stored upside down, and red/blue may be swapped: try each way.
        for (flipped in listOf(false, true)) {
            val rows = HashMap<Int, ByteArray?>()
            fun pixel(x: Int, y: Int): ByteArray? {
                val row = if (flipped) h - 1 - y else y
                val bytes = rows.getOrPut(row) { reader.readGpuRow(drawable, row) } ?: return null
                return if (x * 4 + 2 < bytes.size) bytes.copyOfRange(x * 4, x * 4 + 3) else null
            }
            for (redFirst in listOf(true, false)) {
                for (look in LOOKS) {
                    val hits = look.spots.count { s ->
                        val x = when (s.anchor) {
                            Anchor.LEFT -> s.dx * scale
                            Anchor.CENTER -> w / 2f + s.dx * scale
                            Anchor.RIGHT -> w + s.dx * scale
                        }.toInt().coerceIn(0, w - 1)
                        val p = pixel(x, (s.y * scale).toInt().coerceIn(0, h - 1)) ?: return@count false
                        val r = (if (redFirst) p[0] else p[2]).toInt() and 0xFF
                        val g = p[1].toInt() and 0xFF
                        val b = (if (redFirst) p[2] else p[0]).toInt() and 0xFF
                        look.matches(r, g, b)
                    }
                    if (hits >= look.needed) return look.screen
                    if (debugPolls % 60 == 0 && !flipped && redFirst) {
                        Log.i(TAG, "${look.screen}: $hits/${look.spots.size} hits in ${w}x$h; " + look.spots.joinToString(" ") { s ->
                            val x = when (s.anchor) {
                                Anchor.LEFT -> s.dx * scale
                                Anchor.CENTER -> w / 2f + s.dx * scale
                                Anchor.RIGHT -> w + s.dx * scale
                            }.toInt().coerceIn(0, w - 1)
                            val y = (s.y * scale).toInt().coerceIn(0, h - 1)
                            "($x,$y)=" + (pixel(x, y)?.joinToString(",") { (it.toInt() and 0xFF).toString() } ?: "null")
                        })
                    }
                }
            }
        }
        debugPolls++
        return Screen.NONE
    }
}
