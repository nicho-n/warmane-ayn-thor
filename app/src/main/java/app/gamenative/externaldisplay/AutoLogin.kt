package app.gamenative.externaldisplay

import android.util.Log
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * wow-mobile: types the saved password on WoW's login screen and presses Enter, once per launch.
 * The account name is remembered by WoW itself, so the cursor already sits in the Password box.
 * [GlueScreenWatcher] tells when the login screen is up.
 */
class AutoLogin(
    private val xServer: XServer,
    private val screens: GlueScreenWatcher,
    private val password: String,
) {
    companion object {
        private const val TAG = "AutoLogin"
        private const val POLL_MS = 250L
        private const val GIVE_UP_MS = 5 * 60 * 1000L
        private const val KEY_MS = 15L
    }

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            val until = System.currentTimeMillis() + GIVE_UP_MS
            while (running && System.currentTimeMillis() < until) {
                // Seen on two checks in a row (a second apart), so the screen has finished fading in.
                if (screens.current == GlueScreenWatcher.Screen.LOGIN && screens.streak >= 2) {
                    sleep(800)
                    if (running) typePassword()
                    break
                }
                sleep(POLL_MS)
            }
            running = false
        }, "AutoLogin").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun typePassword() {
        Log.i(TAG, "typing password")
        for (c in password) {
            val (key, shift) = keyFor(c) ?: continue
            if (shift) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L)
            xServer.injectKeyPress(key)
            xServer.injectKeyRelease(key)
            if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L)
            sleep(KEY_MS)
        }
        sleep(100)
        xServer.injectKeyPress(XKeycode.KEY_ENTER)
        xServer.injectKeyRelease(XKeycode.KEY_ENTER)
    }

    /** US keyboard: the key for a character and whether Shift is needed. */
    private fun keyFor(c: Char): Pair<XKeycode, Boolean>? {
        if (c in 'a'..'z') return XKeycode.valueOf("KEY_" + c.uppercaseChar()) to false
        if (c in 'A'..'Z') return XKeycode.valueOf("KEY_$c") to true
        if (c in '0'..'9') return XKeycode.valueOf("KEY_$c") to false
        val shifted = ")!@#$%^&*("
        shifted.indexOf(c).takeIf { it >= 0 }?.let { return XKeycode.valueOf("KEY_$it") to true }
        return when (c) {
            ' ' -> XKeycode.KEY_SPACE to false
            '-' -> XKeycode.KEY_MINUS to false; '_' -> XKeycode.KEY_MINUS to true
            '=' -> XKeycode.KEY_EQUAL to false; '+' -> XKeycode.KEY_EQUAL to true
            '[' -> XKeycode.KEY_BRACKET_LEFT to false; '{' -> XKeycode.KEY_BRACKET_LEFT to true
            ']' -> XKeycode.KEY_BRACKET_RIGHT to false; '}' -> XKeycode.KEY_BRACKET_RIGHT to true
            '\\' -> XKeycode.KEY_BACKSLASH to false; '|' -> XKeycode.KEY_BACKSLASH to true
            ';' -> XKeycode.KEY_SEMICOLON to false; ':' -> XKeycode.KEY_SEMICOLON to true
            '\'' -> XKeycode.KEY_APOSTROPHE to false; '"' -> XKeycode.KEY_APOSTROPHE to true
            ',' -> XKeycode.KEY_COMMA to false; '<' -> XKeycode.KEY_COMMA to true
            '.' -> XKeycode.KEY_PERIOD to false; '>' -> XKeycode.KEY_PERIOD to true
            '/' -> XKeycode.KEY_SLASH to false; '?' -> XKeycode.KEY_SLASH to true
            '`' -> XKeycode.KEY_GRAVE to false; '~' -> XKeycode.KEY_GRAVE to true
            else -> null
        }
    }
}
