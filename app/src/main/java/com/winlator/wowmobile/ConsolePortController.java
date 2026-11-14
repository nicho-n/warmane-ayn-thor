package com.winlator.wowmobile;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

/**
 * Turns a physical controller into the keys ConsolePort is calibrated to (by the
 * ThorControls addon and Provisioner; WoW Imito's ConsolePort uses the same map):
 *
 *   D-pad up/right/down/left -> F1/F2/F3/F4    Select -> F5   Start -> F6
 *   L1 -> F7   R1 -> F8   Y -> F9   B -> F10   A -> F11   X -> F12
 *   L2 -> held left Shift   R2 -> held left Ctrl
 *   Left stick -> held arrow keys (move / strafe)
 *   Right stick -> camera (NUMPADDIVIDE or right mouse held + mouse motion, see
 *   setCameraKey); R3 toggles pointer mode, where the right stick moves the cursor
 *   and L3 left-clicks.
 *
 * Touch never touches the keyboard state here, so a tap cannot release held
 * movement or modifier keys. Touch and the camera share the one mouse pointer,
 * so only the camera pauses while a finger is down (see {@link #setTouchActive}).
 */
public class ConsolePortController {
    private static final float STICK_PRESS = 0.5f;
    private static final float STICK_RELEASE = 0.35f;
    private static final float TRIGGER_PRESS = 0.5f;
    private static final float TRIGGER_RELEASE = 0.3f;
    private static final float RIGHT_DEAD_ZONE = 0.15f;
    private static final int TICK_MS = 8;
    private static final float CAMERA_SPEED = 22.0f;   // pixels per tick at full tilt
    private static final float POINTER_SPEED = 14.0f;
    private static final int CAMERA_RELEASE_DELAY_MS = 120;

    private final XServer xServer;
    private final WinHandler winHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Left stick arrow state: up, right, down, left
    private final boolean[] arrows = new boolean[4];
    private static final XKeycode[] ARROW_KEYS = {XKeycode.KEY_UP, XKeycode.KEY_RIGHT, XKeycode.KEY_DOWN, XKeycode.KEY_LEFT};
    // D-pad state (from hat axes): up, right, down, left
    private final boolean[] dpad = new boolean[4];
    private static final XKeycode[] DPAD_KEYS = {XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4};

    private boolean shiftHeld, ctrlHeld;
    private boolean triggersAreButtons; // some devices send L2/R2 as key events only

    private float rightX, rightY;
    private boolean pointerMode;
    private boolean cameraHeld;
    private boolean touchActive;
    private long rightIdleSince;
    private float accumX, accumY;
    private boolean ticking;

    public ConsolePortController(XServer xServer, WinHandler winHandler) {
        this.xServer = xServer;
        this.winHandler = winHandler;
    }

    private static boolean isController(InputDevice device) {
        return ExternalController.isGameController(device);
    }

    // ---------------------------------------------------------------- buttons

    private static XKeycode buttonKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:      return XKeycode.KEY_F1;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return XKeycode.KEY_F2;
            case KeyEvent.KEYCODE_DPAD_DOWN:    return XKeycode.KEY_F3;
            case KeyEvent.KEYCODE_DPAD_LEFT:    return XKeycode.KEY_F4;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return XKeycode.KEY_F5;
            case KeyEvent.KEYCODE_BUTTON_START: return XKeycode.KEY_F6;
            case KeyEvent.KEYCODE_BUTTON_L1:    return XKeycode.KEY_F7;
            case KeyEvent.KEYCODE_BUTTON_R1:    return XKeycode.KEY_F8;
            case KeyEvent.KEYCODE_BUTTON_Y:     return XKeycode.KEY_F9;
            case KeyEvent.KEYCODE_BUTTON_B:     return XKeycode.KEY_F10;
            case KeyEvent.KEYCODE_BUTTON_A:     return XKeycode.KEY_F11;
            case KeyEvent.KEYCODE_BUTTON_X:     return XKeycode.KEY_F12;
            default: return null;
        }
    }

    /** Returns true if the event came from a controller and was consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        if (!isController(event.getDevice())) return false;
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        boolean down = action == KeyEvent.ACTION_DOWN;

        XKeycode key = buttonKey(keyCode);
        if (key != null) {
            if (event.getRepeatCount() == 0) {
                if (down) {
                    // Remembered so the release goes to the same key even if the screen changed meanwhile.
                    XKeycode sent = glueKey(keyCode, key);
                    pressedKeys.put(keyCode, sent);
                    xServer.injectKeyPress(sent);
                }
                else {
                    XKeycode sent = pressedKeys.remove(keyCode);
                    xServer.injectKeyRelease(sent != null ? sent : key);
                }
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_L2:
                triggersAreButtons = true;
                setShift(down);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                triggersAreButtons = true;
                setCtrl(down);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                if (down && event.getRepeatCount() == 0) setPointerMode(!pointerMode);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                if (event.getRepeatCount() == 0 && pointerMode) {
                    if (down) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return true; // Guide/Home is unused by ConsolePort
        }
        return false;
    }

    private void setShift(boolean held) {
        if (held == shiftHeld) return;
        shiftHeld = held;
        if (modifiersSuspended) return;
        if (held) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L); else xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
    }

    private void setCtrl(boolean held) {
        if (held == ctrlHeld) return;
        ctrlHeld = held;
        if (modifiersSuspended) return;
        if (held) xServer.injectKeyPress(XKeycode.KEY_CTRL_L); else xServer.injectKeyRelease(XKeycode.KEY_CTRL_L);
    }

    private boolean modifiersSuspended;

    /**
     * Lets go of the trigger modifiers while the bottom pad types a chat command, so "/" isn't
     * typed as "?". Trigger changes meanwhile are remembered; resuming presses back what is still held.
     */
    public void setModifiersSuspended(boolean suspended) {
        if (suspended == modifiersSuspended) return;
        modifiersSuspended = suspended;
        if (shiftHeld) {
            if (suspended) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L); else xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
        }
        if (ctrlHeld) {
            if (suspended) xServer.injectKeyRelease(XKeycode.KEY_CTRL_L); else xServer.injectKeyPress(XKeycode.KEY_CTRL_L);
        }
    }

    // ----------------------------------------------------------- sticks, hat

    /** Returns true if the event came from a controller and was consumed. */
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isController(event.getDevice()) || !ExternalController.isJoystickDevice(event)) return false;

        // Left stick -> arrows, with hysteresis so it doesn't flicker at the edge.
        float lx = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_X, -1);
        float ly = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_Y, -1);
        setArrow(0, ly, -1);
        setArrow(2, ly, 1);
        setArrow(1, lx, 1);
        setArrow(3, lx, -1);

        // D-pad reported as hat axes -> F1..F4
        float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        setDpad(0, hy < -0.5f);
        setDpad(1, hx > 0.5f);
        setDpad(2, hy > 0.5f);
        setDpad(3, hx < -0.5f);

        // Analog triggers -> held Shift / Ctrl
        if (!triggersAreButtons) {
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
            setShift(shiftHeld ? lt > TRIGGER_RELEASE : lt > TRIGGER_PRESS);
            setCtrl(ctrlHeld ? rt > TRIGGER_RELEASE : rt > TRIGGER_PRESS);
        }

        // Right stick -> camera / pointer, driven by a timer for smooth motion.
        rightX = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_Z, -1);
        rightY = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_RZ, -1);
        if (rightActive() && !ticking) startTicking();
        return true;
    }

    private void setArrow(int index, float value, int sign) {
        float v = value * sign;
        boolean pressed = arrows[index] ? v > STICK_RELEASE : v > STICK_PRESS;
        if (pressed == arrows[index]) return;
        arrows[index] = pressed;
        if (pressed) xServer.injectKeyPress(ARROW_KEYS[index]); else xServer.injectKeyRelease(ARROW_KEYS[index]);
    }

    private void setDpad(int index, boolean pressed) {
        if (pressed == dpad[index]) return;
        dpad[index] = pressed;
        if (pressed) {
            dpadSent[index] = glueKey(DPAD_KEYCODES[index], DPAD_KEYS[index]);
            xServer.injectKeyPress(dpadSent[index]);
        }
        else xServer.injectKeyRelease(dpadSent[index] != null ? dpadSent[index] : DPAD_KEYS[index]);
    }

    // ------------------------------------------------- login / character select

    private app.gamenative.externaldisplay.GlueScreenWatcher glueScreens;
    private final java.util.HashMap<Integer, XKeycode> pressedKeys = new java.util.HashMap<>();
    private final XKeycode[] dpadSent = new XKeycode[4];
    private static final int[] DPAD_KEYCODES = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT};

    /** Tells which pre-game screen is up, so buttons can work there (see glueKey). */
    public void setGlueScreens(app.gamenative.externaldisplay.GlueScreenWatcher watcher) {
        glueScreens = watcher;
    }

    /**
     * ConsolePort isn't loaded on the login and character screens, so there A presses Enter
     * (log in / Enter World) and, on character select, D-pad up/down pick the character.
     */
    private XKeycode glueKey(int keyCode, XKeycode normal) {
        if (glueScreens == null) return normal;
        app.gamenative.externaldisplay.GlueScreenWatcher.Screen screen = glueScreens.getCurrent();
        if (screen == app.gamenative.externaldisplay.GlueScreenWatcher.Screen.NONE) return normal;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) return XKeycode.KEY_ENTER;
        if (screen == app.gamenative.externaldisplay.GlueScreenWatcher.Screen.CHARACTER_SELECT) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return XKeycode.KEY_UP;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return XKeycode.KEY_DOWN;
        }
        return normal;
    }

    private boolean rightActive() {
        return Math.hypot(rightX, rightY) > RIGHT_DEAD_ZONE;
    }

    // ------------------------------------------------------- camera / pointer

    public interface PointerModeListener { void onPointerModeChanged(boolean pointerMode); }
    private PointerModeListener pointerModeListener;

    public void setPointerModeListener(PointerModeListener listener) {
        pointerModeListener = listener;
    }

    private void setPointerMode(boolean on) {
        pointerMode = on;
        if (on) releaseCamera();
        if (pointerModeListener != null) pointerModeListener.onPointerModeChanged(on);
    }

    /**
     * Touch and the camera share the single mouse pointer. While a finger is
     * down the camera lets go of the right mouse button, so a tap can't jump
     * the camera or turn into a both-buttons "run forward". Keys are untouched.
     * The camera resumes by itself on the next tick if the stick is still held.
     */
    public void setTouchActive(boolean active) {
        touchActive = active;
        if (active) releaseCamera();
        // TouchpadView lets go of its left click 30 ms after the finger lifts.
        // Wait past that so the camera's right button never overlaps it.
        else touchEndedAt = System.currentTimeMillis();
    }

    private static final int TOUCH_SETTLE_MS = 80;
    private long touchEndedAt;

    private boolean touchBlocksCamera() {
        return touchActive || System.currentTimeMillis() - touchEndedAt < TOUCH_SETTLE_MS;
    }

    private void startTicking() {
        ticking = true;
        handler.postDelayed(tick, TICK_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean active = rightActive();
            if (active) rightIdleSince = 0;
            else if (rightIdleSince == 0) rightIdleSince = now;

            if (active && !touchBlocksCamera()) {
                float speed = pointerMode ? POINTER_SPEED : CAMERA_SPEED;
                if (!pointerMode && !cameraHeld) {
                    cameraKeyHeld = cameraKey;
                    if (cameraKeyHeld) xServer.injectKeyPress(XKeycode.KEY_KP_DIVIDE);
                    else xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    cameraHeld = true;
                }
                accumX += curve(rightX) * speed;
                accumY += curve(rightY) * speed;
                int dx = (int)accumX, dy = (int)accumY;
                accumX -= dx;
                accumY -= dy;
                if (dx != 0 || dy != 0) moveMouse(dx, dy);
            }

            // Keep the right button held briefly after the stick centres, so
            // small corrections don't produce a click.
            if (!active && cameraHeld && now - rightIdleSince >= CAMERA_RELEASE_DELAY_MS) releaseCamera();

            if (active || cameraHeld) handler.postDelayed(this, TICK_MS);
            else {
                ticking = false;
                accumX = accumY = 0;
            }
        }
    };

    private static float curve(float v) {
        float a = Math.abs(v);
        a = (a - RIGHT_DEAD_ZONE) / (1.0f - RIGHT_DEAD_ZONE);
        if (a <= 0) return 0;
        return Math.signum(v) * a * a;
    }

    private void moveMouse(int dx, int dy) {
        if (xServer.isRelativeMouseMovement()) winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
        else xServer.injectPointerMoveDelta(dx, dy);
    }

    private void releaseCamera() {
        if (!cameraHeld) return;
        cameraHeld = false;
        if (cameraKeyHeld) xServer.injectKeyRelease(XKeycode.KEY_KP_DIVIDE);
        else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }

    /**
     * Camera key: with the ThorControls addon, the right stick holds NUMPADDIVIDE (bound
     * there to look-around without clicking), so right-click stays a normal click.
     * Without it, NUMPADDIVIDE is WoW's walk/run toggle, so hold the right mouse button.
     */
    private boolean cameraKey;
    private boolean cameraKeyHeld;

    public void setCameraKey(boolean useKey) {
        if (cameraHeld) releaseCamera();
        cameraKey = useKey;
    }
}
