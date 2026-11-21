package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.winlator.xserver.XServer

// wow-mobile: the touch-and-drag trackpad over the bottom pad's pages (Settings > Targeting).

/**
 * A page area (the Target boxes, a page of buttons, the quest log) that turns into [trackpad]
 * when a finger slides past [PadTrackpad.START_DP] instead of tapping. The view the finger
 * started on gets a cancel, so nothing is pressed. Add [trackpad] as the last child.
 * [blocked]: something else is open over the area (the Target page's mode list).
 */
@SuppressLint("ViewConstructor")
class TrackpadArea(context: Context, private val trackpad: PadTrackpad, private val blocked: () -> Boolean = { false }) : FrameLayout(context) {
    private var downId = -1
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (trackpad.visibility == VISIBLE || blocked() || !PadSettings.bool(PadSettings.TARGET_TRACKPAD)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downId = ev.getPointerId(0); downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(downId)
                if (i >= 0 && Math.hypot((ev.getX(i) - downX).toDouble(), (ev.getY(i) - downY).toDouble()) > PadTrackpad.START_DP * resources.displayMetrics.density) {
                    trackpad.begin(downId, ev.getX(i), ev.getY(i))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downId = -1
        }
        return false
    }

    // After the drag was taken, the rest of the gesture comes here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = trackpad.handle(event)

    override fun onDetachedFromWindow() {
        trackpad.close()
        super.onDetachedFromWindow()
    }
}

/**
 * The trackpad over a page: a finger moves the pointer like the pad's own trackpad and a tap
 * left-clicks. Left click and Right click buttons fill the bottom-left and bottom-right
 * boxes (or buttons) and work like the trackpad page's: held while touched (hold one and slide
 * another finger to drag), a double tap keeps the button down until it's tapped again. The X
 * in the right half of the top-right box closes the trackpad. Closing puts the cursor back in
 * the middle of the screen. It fades back to the boxes after
 * [IDLE_MS] without a touch (not while a button is held). The controller camera
 * pauses while a finger is on it.
 */
@SuppressLint("ViewConstructor")
class PadTrackpad(context: Context, private val xServer: XServer, private val theme: PadTheme) : View(context) {
    /** The page's columns of boxes or buttons, top to bottom; the click buttons and the X go on them. */
    var columns: () -> List<List<View>> = { emptyList() }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** One of the two click buttons: held while touched, locked down by a double tap. */
    private inner class ClickButton(val button: com.winlator.xserver.Pointer.Button, val label: String) {
        val rect = RectF()
        var pressed = false
        var locked = false
        private var lockOnRelease = false
        private var ignoreUp = false
        private var lastDownAt = 0L
        val down get() = pressed || locked

        fun touchDown() {
            PadSettings.haptic(this@PadTrackpad)
            val now = android.os.SystemClock.uptimeMillis()
            if (locked) {
                // A tap on a locked button lets it go.
                send(false)
                locked = false
                ignoreUp = true
            } else {
                lockOnRelease = now - lastDownAt <= PadSettings.doubleTapMs
                lastDownAt = now
                pressed = true
                send(true)
            }
            invalidate()
        }

        fun touchUp() {
            when {
                ignoreUp -> ignoreUp = false
                lockOnRelease && pressed -> { locked = true; lockOnRelease = false }
                pressed -> { send(false); PadBridge.clickFinished() }
            }
            pressed = false
            invalidate()
        }

        fun reset() {
            if (pressed || locked) send(false)
            pressed = false; locked = false; lockOnRelease = false; ignoreUp = false
        }

        private fun send(down: Boolean) {
            if (down) {
                if (xServer.pointer.isButtonPressed(button)) xServer.injectPointerButtonRelease(button)
                xServer.injectPointerButtonPress(button)
            } else xServer.injectPointerButtonRelease(button)
        }
    }

    private val leftButton = ClickButton(com.winlator.xserver.Pointer.Button.BUTTON_LEFT, "Left click")
    private val rightButton = ClickButton(com.winlator.xserver.Pointer.Button.BUTTON_RIGHT, "Right click")
    private val closeRect = RectF()

    /** What each finger on the trackpad is doing. */
    private val fingers = HashMap<Int, TrackpadRole>()
    /** The finger moving the pointer. */
    private var moveId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var restX = 0f
    private var restY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text
        typeface = theme.typeface
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val fade = Runnable {
        animate().alpha(0f).setDuration(200).withEndAction {
            // A button got locked meanwhile: stay up (seen), so it can be let go.
            if (fingers.isEmpty() && !buttonsDown()) { visibility = GONE; alpha = 1f; centerCursor() } else alpha = 1f
        }.start()
    }

    init { visibility = GONE }

    private fun buttonsDown() = leftButton.down || rightButton.down

    /** The drag that opened it: that finger moves the pointer from here on. */
    fun begin(pointerId: Int, x: Float, y: Float) {
        show()
        if (fingers.isEmpty()) PadBridge.trackpadTouch(true)
        fingers[pointerId] = TrackpadRole.MOVE
        moveId = pointerId
        lastX = x; lastY = y
        moved = true
    }

    private fun show() {
        main.removeCallbacks(fade)
        animate().cancel()
        alpha = 1f
        if (visibility != VISIBLE) { visibility = VISIBLE; restX = 0f; restY = 0f }
        invalidate()
    }

    /** The cursor back to the middle of the game screen, where the controller camera works from. */
    private fun centerCursor() {
        xServer.injectPointerMove(xServer.screenInfo.width / 2, xServer.screenInfo.height / 2)
    }

    /** Straight back to the boxes (the X, or the page going away); lets go of any held button. */
    fun close(center: Boolean = true) {
        main.removeCallbacks(fade)
        animate().cancel()
        if (fingers.isNotEmpty()) PadBridge.trackpadTouch(false)
        fingers.clear()
        moveId = -1
        leftButton.reset()
        rightButton.reset()
        val wasShown = visibility == VISIBLE
        visibility = GONE
        alpha = 1f
        if (center && wasShown) centerCursor()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = handle(event)

    fun handle(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                show()
                if (fingers.isEmpty()) PadBridge.trackpadTouch(true)
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                val role = when {
                    leftButton.rect.contains(x, y) -> TrackpadRole.LEFT
                    rightButton.rect.contains(x, y) -> TrackpadRole.RIGHT
                    closeRect.contains(x, y) -> TrackpadRole.CLOSE
                    moveId == -1 -> TrackpadRole.MOVE
                    else -> TrackpadRole.IGNORE
                }
                fingers[id] = role
                when (role) {
                    TrackpadRole.LEFT -> leftButton.touchDown()
                    TrackpadRole.RIGHT -> rightButton.touchDown()
                    TrackpadRole.CLOSE -> PadSettings.haptic(this)
                    TrackpadRole.MOVE -> {
                        moveId = id
                        lastX = x; lastY = y; downX = x; downY = y
                        downAt = System.currentTimeMillis()
                        moved = false
                    }
                    TrackpadRole.IGNORE -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(moveId)
                if (i >= 0) {
                    val x = ev.getX(i)
                    val y = ev.getY(i)
                    if (Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > DRAG_START_DP * resources.displayMetrics.density) moved = true
                    movePointer(x - lastX, y - lastY)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                when (fingers.remove(id)) {
                    TrackpadRole.LEFT -> leftButton.touchUp()
                    TrackpadRole.RIGHT -> rightButton.touchUp()
                    TrackpadRole.CLOSE -> if (closeRect.contains(ev.getX(i), ev.getY(i))) { close(); return true }
                    TrackpadRole.MOVE -> {
                        moveId = -1
                        if (!moved && System.currentTimeMillis() - downAt < TAP_MS) {
                            PadSettings.haptic(this)
                            tap()
                        }
                    }
                    else -> {}
                }
                if (fingers.isEmpty()) allUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                fingers.values.forEach { r -> if (r == TrackpadRole.LEFT) leftButton.touchUp() else if (r == TrackpadRole.RIGHT) rightButton.touchUp() }
                fingers.clear()
                moveId = -1
                allUp()
            }
        }
        return true
    }

    private fun allUp() {
        PadBridge.trackpadTouch(false)
        main.removeCallbacks(fade)
        // A locked button keeps the trackpad up, so it can be seen and let go.
        if (visibility == VISIBLE && !buttonsDown()) main.postDelayed(fade, IDLE_MS)
    }

    /** Same feel as the pad's trackpad: its speed setting, and a speed-up on quick moves. */
    private fun movePointer(dx: Float, dy: Float) {
        val toScreen = maxOf(xServer.screenInfo.width.toFloat() / width.coerceAtLeast(1), xServer.screenInfo.height.toFloat() / height.coerceAtLeast(1))
        fun scale(d: Float): Float {
            var v = d * toScreen * PadSettings.trackpadSensitivity
            if (Math.abs(v) > com.winlator.widget.TouchpadView.CURSOR_ACCELERATION_THRESHOLD) v *= com.winlator.widget.TouchpadView.CURSOR_ACCELERATION
            return v
        }
        restX += scale(dx)
        restY += scale(dy)
        val ix = restX.toInt()
        val iy = restY.toInt()
        restX -= ix
        restY -= iy
        if (ix != 0 || iy != 0) xServer.injectPointerMoveDelta(ix, iy)
    }

    /** A tap on the trackpad: a left click (none while a button is held, which would let it go). */
    private fun tap() {
        if (leftButton.down || rightButton.down) return
        val b = com.winlator.xserver.Pointer.Button.BUTTON_LEFT
        xServer.injectPointerButtonPress(b)
        // On a handler, so the release comes even if the page closes meanwhile.
        main.postDelayed({
            xServer.injectPointerButtonRelease(b)
            PadBridge.clickFinished()
        }, 30)
    }

    /** Where a box is inside the area (the trackpad covers the area exactly). */
    private fun boxRect(box: View?, out: RectF): Boolean {
        if (box == null || box.visibility != VISIBLE || box.width == 0) return false
        var x = 0f
        var y = 0f
        var v: View? = box
        while (v != null && v !== parent) {
            x += v.left; y += v.top
            v = v.parent as? View
        }
        if (v == null) return false
        out.set(x, y, x + box.width, y + box.height)
        return true
    }

    private fun placeButtons() {
        val w = width.toFloat()
        val h = height.toFloat()
        val box = RectF()
        fun bottomBox(column: List<View>?, out: RectF, fallbackLeft: Float, fallbackRight: Float) {
            if (boxRect(column?.lastOrNull { it.visibility == VISIBLE }, box)) out.set(box)
            else out.set(fallbackLeft, h * 0.8f, fallbackRight, h)
        }
        val columnBoxes = columns()
        bottomBox(columnBoxes.firstOrNull(), leftButton.rect, 0f, w * 0.4f)
        bottomBox(columnBoxes.lastOrNull(), rightButton.rect, w * 0.6f, w)
        if (boxRect(columnBoxes.lastOrNull()?.firstOrNull { it.visibility == VISIBLE }, box)) closeRect.set(box.right - box.width() / 2, box.top, box.right, box.bottom)
        else closeRect.set(w * 0.8f, 0f, w, h * 0.25f)
    }

    override fun onDraw(canvas: Canvas) {
        placeButtons()
        val d = resources.displayMetrics.density
        val r = 8 * d
        // A dark veil over the boxes with a gold edge, so it's clear the page is a trackpad now.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 12, 10, 8)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = theme.border
        canvas.drawRoundRect(RectF(d, d, width - d, height - d), r, r, paint)
        label.textSize = 15 * d
        label.alpha = 150
        canvas.drawText("Trackpad: tap to left-click", width / 2f, height / 2f, label)
        label.alpha = 255
        fun button(rect: RectF, text: String, size: Float, on: Boolean = false) {
            val inset = 3 * d
            val b = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            paint.style = Paint.Style.FILL
            // Held or locked: filled gold, like a pressed pad button.
            paint.color = if (on) theme.border else Color.argb(255, 40, 33, 26)
            canvas.drawRoundRect(b, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.color = theme.border
            canvas.drawRoundRect(b, r, r, paint)
            label.textSize = size
            label.color = if (on) theme.textPressed else theme.text
            canvas.drawText(text, b.centerX(), b.centerY() - (label.descent() + label.ascent()) / 2, label)
            label.color = theme.text
        }
        button(leftButton.rect, leftButton.label, 15 * d, leftButton.down)
        button(rightButton.rect, rightButton.label, 15 * d, rightButton.down)
        button(closeRect, "\u2715", 20 * d)
    }

    companion object {
        /** How far a finger slides on a page before it turns into a trackpad (dp). */
        const val START_DP = 50
        /** How far a finger slides on the trackpad before a touch is no longer a tap (dp). */
        private const val DRAG_START_DP = 14
        /** The trackpad goes back to the page after this long without a touch. */
        private const val IDLE_MS = 650L
        /** A trackpad touch shorter than this, without sliding, is a left click. */
        private const val TAP_MS = 300L
    }
}

/** What a finger on the trackpad is doing. */
private enum class TrackpadRole { MOVE, LEFT, RIGHT, CLOSE, IGNORE }
