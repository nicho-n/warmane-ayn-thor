package app.gamenative.externaldisplay

/**
 * A small link from the controller code to the second-screen pad. Armed modifier buttons live on the pad,
 * but the controller's A / B clicks in cursor mode are handled elsewhere; this lets a finished click let go
 * of them, the same as a click from the pad's own buttons.
 */
object PadBridge {
    /** Set by the pad while it is on screen. Always called on the main thread. */
    @Volatile
    var releaseModifiers: (() -> Unit)? = null

    /** Modifiers held by the L2 / R2 triggers (when they act as modifier keys). Set by the controller code. */
    @Volatile
    var triggerModifiers: Set<com.winlator.xserver.XKeycode> = emptySet()
        private set

    /** Set by the pad: lights the modifier buttons and the glow when [triggerModifiers] changes. */
    @Volatile
    var triggerModifiersChanged: (() -> Unit)? = null

    fun setTriggerModifiers(keys: Set<com.winlator.xserver.XKeycode>) {
        if (keys == triggerModifiers) return
        triggerModifiers = keys.toSet()
        triggerModifiersChanged?.invoke()
    }

    /** True while the right stick is the mouse pointer (R3 cursor mode). Read by the renderer to draw the ring. */
    @Volatile
    private var cursorMode = false

    fun isCursorRingShown(): Boolean = cursorMode && PadSettings.bool(PadSettings.CURSOR_RING)

    fun ringColor(): Int = PadSettings.RING_COLORS[PadSettings.int(PadSettings.RING_COLOR).coerceIn(0, PadSettings.RING_COLORS.size - 1)].second

    fun setCursorMode(on: Boolean) {
        if (cursorMode == on) return
        cursorMode = on
    }

    /** wow-mobile: told when a finger is on the pad's trackpad, so the controller camera can pause. */
    @Volatile
    var trackpadTouchListener: ((Boolean) -> Unit)? = null

    fun trackpadTouch(active: Boolean) {
        trackpadTouchListener?.invoke(active)
    }

    /** wow-mobile: true = let go of the controller's trigger modifiers for a moment, false = press back those still held. */
    @Volatile
    var controllerModifierHold: ((Boolean) -> Unit)? = null

    /**
     * wow-mobile: told when the Target page appears or goes away (the app then turns the link
     * on/off). Told the current state as soon as it's set: the pad opens on the Target page,
     * before the game screen starts listening.
     */
    @Volatile
    var targetPageListener: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(targetPageShown)
        }
    @Volatile
    private var targetPageShown = false

    /** wow-mobile: true while the Quests page is showing (the link then carries the quest log). */
    @Volatile
    var questPageShown = false

    fun setTargetPageVisible(visible: Boolean) {
        if (visible == targetPageShown) return
        targetPageShown = visible
        targetPageListener?.invoke(visible)
    }

    fun clickFinished() {
        releaseModifiers?.invoke()
    }
}
