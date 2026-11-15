package app.gamenative.externaldisplay

import android.os.Handler
import android.os.Looper
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * wow-mobile: sends a chat slash command (e.g. "/ty", "/target party1") the way a player types it.
 * A command opens the chat box with WoW's "/" key (the box opens with the "/" already in it, so it
 * can't be lost); other text opens it with Enter. Then a pause while the box opens (typed straight
 * away, the first key was sometimes lost, and a command went out as /say), the letters, and Enter.
 * Shift/Ctrl/Alt held on the pad or by the controller triggers are let go for the duration, so "/"
 * isn't typed as "?".
 */
internal object SlashTyper {
    private const val STEP_MS = 12L
    /** How long the chat box gets to open before the letters are typed. */
    private const val OPEN_MS = 150L
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    fun type(
        xServer: XServer,
        command: String,
        onSuspendModifiers: () -> List<XKeycode>,
        onResumeModifiers: (List<XKeycode>) -> Unit,
    ) {
        if (busy) return
        val keys = command.lowercase().map { keyFor(it) ?: return }
        busy = true
        val padMods = onSuspendModifiers()
        PadBridge.controllerModifierHold?.invoke(true)
        // Release any modifier still down (e.g. one the user holds elsewhere) so letters type plainly.
        val isCommand = command.startsWith("/")
        handler.postDelayed({ tap(xServer, if (isCommand) XKeycode.KEY_SLASH else XKeycode.KEY_ENTER) }, STEP_MS)
        val steps = mutableListOf<() -> Unit>()
        (if (isCommand) keys.drop(1) else keys).forEach { k -> steps += { tap(xServer, k) } }
        steps += { tap(xServer, XKeycode.KEY_ENTER) }
        steps += {
            PadBridge.controllerModifierHold?.invoke(false)
            onResumeModifiers(padMods)
            busy = false
        }
        steps.forEachIndexed { i, step -> handler.postDelayed(step, STEP_MS + OPEN_MS + STEP_MS * i) }
    }

    /**
     * Presses Ctrl+Alt(+Shift)+[key] with the right-hand modifiers, with any trigger/pad
     * modifiers let go for the moment. Used for the WoWMobileLink addon's target keys.
     */
    fun chord(
        xServer: XServer,
        key: XKeycode,
        shift: Boolean,
        onSuspendModifiers: () -> List<XKeycode>,
        onResumeModifiers: (List<XKeycode>) -> Unit,
        /** Ctrl+Alt(+Shift) normally; false for Alt+Shift chords. */
        ctrl: Boolean = true,
    ) {
        if (busy) return
        val padMods = onSuspendModifiers()
        PadBridge.controllerModifierHold?.invoke(true)
        if (ctrl) xServer.injectKeyPress(XKeycode.KEY_CTRL_R)
        xServer.injectKeyPress(XKeycode.KEY_ALT_R)
        if (shift) xServer.injectKeyPress(XKeycode.KEY_SHIFT_R)
        xServer.injectKeyPress(key)
        xServer.injectKeyRelease(key)
        if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_R)
        xServer.injectKeyRelease(XKeycode.KEY_ALT_R)
        if (ctrl) xServer.injectKeyRelease(XKeycode.KEY_CTRL_R)
        PadBridge.controllerModifierHold?.invoke(false)
        onResumeModifiers(padMods)
    }

    private fun tap(xServer: XServer, key: XKeycode) {
        xServer.injectKeyPress(key)
        xServer.injectKeyRelease(key)
    }

    fun canType(c: Char) = keyFor(c.lowercaseChar()) != null

    private fun keyFor(c: Char): XKeycode? = when (c) {
        in 'a'..'z' -> XKeycode.valueOf("KEY_" + c.uppercaseChar())
        in '0'..'9' -> XKeycode.valueOf("KEY_$c")
        '/' -> XKeycode.KEY_SLASH
        ' ' -> XKeycode.KEY_SPACE
        '-' -> XKeycode.KEY_MINUS
        '\'' -> XKeycode.KEY_APOSTROPHE
        else -> null
    }
}
