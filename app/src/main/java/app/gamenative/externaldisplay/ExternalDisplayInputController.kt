package app.gamenative.externaldisplay

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.winlator.R
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer


class ExternalDisplayInputController(
    private val context: Context,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) {
    enum class Mode { OFF, TOUCHPAD, KEYBOARD, HYBRID, BUTTONS }

    companion object {
        fun fromConfig(value: String?): Mode = when (value?.lowercase()) {
            "touchpad" -> Mode.TOUCHPAD
            "keyboard" -> Mode.KEYBOARD
            "hybrid" -> Mode.HYBRID
            "buttons" -> Mode.BUTTONS
            else -> Mode.OFF
        }
    }

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: ExternalInputPresentation? = null
    private var mode: Mode = Mode.OFF

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updatePresentation()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                updatePresentation()
            }
        }
    }

    private var started = false

    fun start() {
        if (!started) {
            started = true
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }
        updatePresentation()
    }

    fun stop() {
        started = false
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        updatePresentation()
    }

    private fun updatePresentation() {
        if (mode == Mode.OFF) {
            dismissPresentation()
            return
        }

        val targetDisplay = findPresentationDisplay() ?: run {
            dismissPresentation()
            return
        }

        val needsNewPresentation = presentation?.display?.displayId != targetDisplay.displayId
        if (presentation == null || needsNewPresentation) {
            dismissPresentation()
            presentation = ExternalInputPresentation(
                context = context,
                display = targetDisplay,
                mode = mode,
                xServer = xServer,
                touchpadViewProvider = touchpadViewProvider,
                bringGameToFront = { bringGameToFront() },
            )
            presentation?.show()
        } else {
            presentation?.updateMode(mode)
        }
    }

    /** Gives the game back the controller (Android sends it to the screen that was used last). */
    private fun bringGameToFront() {
        val activity = context as? Activity ?: return
        try {
            context.getSystemService(ActivityManager::class.java)?.moveTaskToFront(activity.taskId, 0)
        } catch (_: Exception) {
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = context.display ?: return null
        // Required detection logic for external presentation displays
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }
}

private class ExternalInputPresentation(
    context: Context,
    display: Display,
    private var mode: ExternalDisplayInputController.Mode,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
    private val bringGameToFront: () -> Unit,
) : Presentation(context, display) {

    private var pad: ExternalActionPad? = null
    private var browserOpen = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        renderContent()
    }

    fun updateMode(newMode: ExternalDisplayInputController.Mode) {
        if (mode != newMode) {
            mode = newMode
            renderContent()
        }
    }

    /**
     * wow-mobile: opens the web browser on the bottom screen. The pad is drawn above every app on this screen,
     * so while the browser is open it shrinks to a small "Back to pad" button in the bottom-left corner.
     */
    private fun openBrowser() {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
        val browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = listOf(browser, fallback).any { intent ->
            try {
                context.startActivity(intent, options)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (started) showBackButton()
    }

    private fun showBackButton() {
        browserOpen = true
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val theme = PadTheme.DEFAULT
        val button = TextView(context).apply {
            text = "◀ Pad"
            setTextColor(theme.text)
            typeface = theme.typeface
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.argb(220, 24, 21, 18))
                cornerRadius = 12 * density
                setStroke(dp(2), theme.border)
            }
            setOnClickListener { showPad() }
        }
        setContentView(button)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.START or Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    /** Back from the browser: the full pad again (the browser keeps running underneath) and the game gets the controller. */
    private fun showPad() {
        browserOpen = false
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        }
        val p = pad
        if (p != null && mode == ExternalDisplayInputController.Mode.BUTTONS) setContentView(p) else renderContent()
        bringGameToFront()
    }

    private fun renderContent() {
        when (mode) {
            ExternalDisplayInputController.Mode.TOUCHPAD -> {
                val pad = TouchpadView(context, xServer, false).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
                }
                setContentView(pad)
            }
            ExternalDisplayInputController.Mode.KEYBOARD -> {
                val root = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
                }

                val hintIcon = ImageView(context).apply {
                    val density = resources.displayMetrics.density
                    val sizePx = (128 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.icon_keyboard)
                    setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }
                }

                root.addView(hintIcon)
                root.addView(keyboardView)
                setContentView(root)
            }
            ExternalDisplayInputController.Mode.HYBRID -> {
                val hybrid = HybridInputLayout(
                    context = context,
                    xServer = xServer,
                    touchpadViewProvider = touchpadViewProvider,
                )
                setContentView(hybrid)
            }
            ExternalDisplayInputController.Mode.BUTTONS -> {
                val p = ExternalActionPad(context, xServer, PadTheme.DEFAULT, touchpadViewProvider)
                p.onOpenBrowser = { openBrowser() }
                pad = p
                if (browserOpen) showPad() else setContentView(p)
            }
            else -> {
                setContentView(FrameLayout(context))
            }
        }
    }
}

private class HybridInputLayout(
    context: Context,
    xServer: XServer,
    touchpadViewProvider: () -> TouchpadView?,
) : FrameLayout(context) {

    private val touchpad = TouchpadView(context, xServer, false).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
    }
    private val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        visibility = View.GONE
    }

    private val keyboardToggleButton = ImageButton(context).apply {
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val marginPx = (16 * density).toInt()
        layoutParams = LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.external_display_key_background))
        }
        setImageResource(R.drawable.icon_keyboard)
        setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
        setOnClickListener { toggleKeyboard() }
    }

    init {
        addView(touchpad)
        addView(keyboardView)
        addView(keyboardToggleButton)

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToggleButtonPosition()
        }
    }

    private fun toggleKeyboard() {
        val shouldShow = keyboardView.visibility != View.VISIBLE
        keyboardView.visibility = if (shouldShow) View.VISIBLE else View.GONE
        post { updateToggleButtonPosition() }
    }
    private fun updateToggleButtonPosition() {
        keyboardToggleButton.translationY = if (keyboardView.visibility == View.VISIBLE) {
            -keyboardView.height.toFloat()
        } else {
            0f
        }
    }
}
