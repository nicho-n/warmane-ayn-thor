package app.gamenative.externaldisplay

import android.os.Handler
import android.os.Looper
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * wow-mobile: sends a chat slash command (e.g. "/ty", "/target party1") the way a player types it:
 * Enter opens the chat box, the letters are typed, Enter sends. Shift/Ctrl/Alt held on the pad or by
 * the controller triggers are let go for the duration, so "/" isn't typed as "?".
 */
internal object SlashTyper {
    private const val STEP_MS = 12L
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
        val steps = mutableListOf<() -> Unit>()
        steps += { tap(xServer, XKeycode.KEY_ENTER) }
        keys.forEach { k -> steps += { tap(xServer, k) } }
        steps += { tap(xServer, XKeycode.KEY_ENTER) }
        steps += {
            PadBridge.controllerModifierHold?.invoke(false)
            onResumeModifiers(padMods)
            busy = false
        }
        steps.forEachIndexed { i, step -> handler.postDelayed(step, STEP_MS * (i + 1)) }
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
    ) {
        if (busy) return
        val padMods = onSuspendModifiers()
        PadBridge.controllerModifierHold?.invoke(true)
        xServer.injectKeyPress(XKeycode.KEY_CTRL_R)
        xServer.injectKeyPress(XKeycode.KEY_ALT_R)
        if (shift) xServer.injectKeyPress(XKeycode.KEY_SHIFT_R)
        xServer.injectKeyPress(key)
        xServer.injectKeyRelease(key)
        if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_R)
        xServer.injectKeyRelease(XKeycode.KEY_ALT_R)
        xServer.injectKeyRelease(XKeycode.KEY_CTRL_R)
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
