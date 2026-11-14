package app.gamenative.externaldisplay

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * wow-mobile: hands the XP bar settings (Settings > XP bar) to the ThorControls addon.
 *
 * - On game start, and on every change, they're written to ThorControls/AppSettings.lua,
 *   which the addon reads when WoW loads its UI (login or /reload).
 * - On a change while in the world, the app also types "/thor xpbar ..." so it shows right
 *   away. That waits until the slider has been still for a moment, and is skipped outside the
 *   world (the WoWMobileLink addon only sends from there): on the login or character screen,
 *   Enter would log in or enter the world.
 */
object XpBarSync {
    private const val TAG = "XpBarSync"
    private const val QUIET_MS = 700L
    /** WoWMobileLink packets this recent mean the player is in the world. */
    private const val IN_WORLD_MS = 2000L

    /** The game's Interface/AddOns, set when the game starts. */
    @Volatile var addOnsDir: File? = null

    /** Types a chat command into the game; set by the pad while it's on screen. */
    var typer: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val send = Runnable { sendNow() }

    fun changed() {
        write()
        handler.removeCallbacks(send)
        handler.postDelayed(send, QUIET_MS)
    }

    fun write() {
        val dir = addOnsDir?.let { File(it, "ThorControls") } ?: return
        if (!dir.isDirectory) return
        try {
            File(dir, "AppSettings.lua").writeText(
                "-- XP bar settings from the Warmane Ayn Thor app (Settings > XP bar). The app rewrites this\n" +
                    "-- file when it starts the game and when the settings change.\n" +
                    "ThorControlsApp = { xpShow = ${PadSettings.bool(PadSettings.XP_SHOW)}, " +
                    "xpText = ${PadSettings.bool(PadSettings.XP_TEXT)}, " +
                    "xpWidth = ${PadSettings.int(PadSettings.XP_WIDTH)}, " +
                    "xpHeight = ${PadSettings.int(PadSettings.XP_HEIGHT)}, " +
                    "xpX = ${PadSettings.int(PadSettings.XP_X)}, " +
                    "xpY = ${PadSettings.int(PadSettings.XP_Y)} }\n",
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't write AppSettings.lua: $e")
        }
    }

    private fun command(): String {
        fun flag(key: String) = if (PadSettings.bool(key)) 1 else 0
        return "/thor xpbar ${flag(PadSettings.XP_SHOW)} ${flag(PadSettings.XP_TEXT)} " +
            "${PadSettings.int(PadSettings.XP_WIDTH)} ${PadSettings.int(PadSettings.XP_HEIGHT)} " +
            "${PadSettings.int(PadSettings.XP_X)} ${PadSettings.int(PadSettings.XP_Y)}"
    }

    private fun sendNow() {
        if (System.currentTimeMillis() - LinkState.lastPacketAt > IN_WORLD_MS) return
        typer?.invoke(command())
    }
}
