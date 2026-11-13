package com.az.doom;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import org.libsdl.app.SDLActivity;

/**
 * On-screen controls drawn with the artwork in res/drawable-nodpi/ctl_*.png.
 *
 * - In the game: stick, fire, use, jump, squat, weapon prev/next, map, esc, keyboard, settings.
 * - In menus: arrows + enter (+ esc, keyboard, settings). Everything else is a normal tap (mouse click).
 * - "settings" opens the layout editor: drag, resize, delete/restore buttons, "throw off" resets all.
 *   The layout is stored in SharedPreferences and survives restarts.
 *
 * Everything is translated into the key / mouse events SDL already understands.
 */
public class TouchControlsView extends View {

    private static final int GAME = 1, MENU = 2, BOTH = 3;

    private static final int ROLE_NONE = 0, ROLE_STICK = 1, ROLE_LOOK = 2, ROLE_BUTTON = 3, ROLE_EDIT = 4;

    // pseudo key codes
    private static final int K_STICK = -2000, K_KEYBOARD = -1000, K_SETTINGS = -3000;

    private static final class Spec {
        final String id;
        final int res;
        final int key;
        final int sets;
        final float defX, defY;   // centre, fraction of the screen
        final float size;         // diameter, fraction of the short screen side
        final boolean repeat;     // auto-repeat while held (menu arrows)
        final boolean deletable;
        final boolean backdrop;   // dark artwork: draw a light disc behind it

        Spec(String id, int res, int key, int sets, float defX, float defY, float size,
             boolean repeat, boolean deletable, boolean backdrop) {
            this.id = id; this.res = res; this.key = key; this.sets = sets;
            this.defX = defX; this.defY = defY; this.size = size;
            this.repeat = repeat; this.deletable = deletable; this.backdrop = backdrop;
        }
    }

    // Default layout (centre as a fraction of the screen, size as a fraction of the screen height)
    private static final Spec[] SPECS = new Spec[] {
        // ----- game
        new Spec("joystick", R.drawable.ctl_joystick, K_STICK,                    GAME, 0.085f, 0.820f, 0.273f, false, true,  false),
        new Spec("fire",     R.drawable.ctl_fire,     KeyEvent.KEYCODE_CTRL_LEFT, GAME, 0.930f, 0.770f, 0.196f, false, true,  false),
        new Spec("use",      R.drawable.ctl_use,      KeyEvent.KEYCODE_E,         GAME, 0.818f, 0.796f, 0.197f, false, true,  false),
        new Spec("jump",     R.drawable.ctl_jump,     KeyEvent.KEYCODE_SPACE,     GAME, 0.930f, 0.534f, 0.185f, false, true,  false),
        new Spec("squats",   R.drawable.ctl_squats,   KeyEvent.KEYCODE_X,         GAME, 0.825f, 0.568f, 0.162f, false, true,  false),
        new Spec("prew",     R.drawable.ctl_prew,     KeyEvent.KEYCODE_Q,         GAME, 0.860f, 0.120f, 0.130f, false, true,  false),
        new Spec("nextw",    R.drawable.ctl_nextw,    KeyEvent.KEYCODE_R,         GAME, 0.940f, 0.120f, 0.130f, false, true,  false),
        new Spec("map",      R.drawable.ctl_map,      KeyEvent.KEYCODE_TAB,       GAME, 0.148f, 0.079f, 0.130f, false, true,  false),
        // ----- menu
        new Spec("up",       R.drawable.ctl_up,       KeyEvent.KEYCODE_DPAD_UP,    MENU, 0.140f, 0.560f, 0.150f, true,  true,  false),
        new Spec("down",     R.drawable.ctl_down,     KeyEvent.KEYCODE_DPAD_DOWN,  MENU, 0.140f, 0.870f, 0.150f, true,  true,  false),
        new Spec("left",     R.drawable.ctl_left,     KeyEvent.KEYCODE_DPAD_LEFT,  MENU, 0.070f, 0.715f, 0.150f, true,  true,  false),
        new Spec("right",    R.drawable.ctl_right,    KeyEvent.KEYCODE_DPAD_RIGHT, MENU, 0.210f, 0.715f, 0.150f, true,  true,  false),
        new Spec("enter",    R.drawable.ctl_enter,    KeyEvent.KEYCODE_ENTER,      MENU, 0.930f, 0.770f, 0.210f, false, true,  false),
        // ----- both
        new Spec("esc",      R.drawable.ctl_esc,      KeyEvent.KEYCODE_ESCAPE,    BOTH, 0.056f, 0.088f, 0.135f, false, true,  false),
        new Spec("keyboard", R.drawable.ctl_keyboard, K_KEYBOARD,                 BOTH, 0.705f, 0.105f, 0.112f, false, true,  false),
        new Spec("settings", R.drawable.ctl_settings, K_SETTINGS,                 BOTH, 0.778f, 0.106f, 0.125f, false, false, true),
    };

    private static final class Btn {
        final Spec spec;
        Bitmap bmp;
        float x, y, scale = 1f;
        boolean hidden;
        boolean pressed;
        int pointerId = -1;
        long downTime, nextRepeat;

        Btn(Spec s) { spec = s; x = s.defX; y = s.defY; }
    }

    private final Btn[] btns = new Btn[SPECS.length];

    private final float density;
    private final float lookSens;
    private final boolean haptics;
    private final boolean enabled;
    private final SharedPreferences prefs;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    // pointer bookkeeping
    private final int[] role = new int[16];
    private final float[] lastX = new float[16];
    private final float[] lastY = new float[16];
    private final int[] buttonOf = new int[16];

    // stick
    private int stickPointer = -1;
    private float stickX, stickY;              // finger position (clamped to the ring)
    private boolean kFwd, kBack, kLeft, kRight, kRun;

    private float remX, remY;                  // sub-pixel remainder for look

    private boolean guiMode;
    private boolean kbVisible;

    // layout editor
    private boolean editMode;
    private int editSet = GAME;
    private int selected = -1;
    private float dragDX, dragDY;
    private int panelPressed = -1;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean gui;
            try {
                gui = (GZDoomActivity.nativeUiState() & 1) != 0;
            } catch (Throwable t) {
                gui = false;     // native library not ready yet
            }
            if (gui != guiMode) {
                guiMode = gui;
                releaseAll();
                invalidate();
            }
            postDelayed(this, 100);
        }
    };

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            boolean any = false;
            for (Btn b : btns) {
                if (b.pressed && b.spec.repeat) {
                    any = true;
                    if (now >= b.nextRepeat) {
                        SDLActivity.onNativeKeyUp(b.spec.key);
                        SDLActivity.onNativeKeyDown(b.spec.key);
                        b.nextRepeat = now + 90;
                    }
                }
            }
            if (any) postDelayed(this, 30);
        }
    };

    public TouchControlsView(Context ctx, float lookSens, boolean haptics, boolean enabled) {
        super(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.lookSens = lookSens;
        this.haptics = haptics;
        this.enabled = enabled;
        this.prefs = ctx.getSharedPreferences("touch_layout", Context.MODE_PRIVATE);
        setWillNotDraw(false);
        setFocusable(false);

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        for (int i = 0; i < SPECS.length; i++) {
            Btn b = new Btn(SPECS[i]);
            b.bmp = BitmapFactory.decodeResource(ctx.getResources(), SPECS[i].res, o);
            btns[i] = b;
        }
        loadLayout();

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * density);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        java.util.Arrays.fill(buttonOf, -1);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(poll);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(poll);
        removeCallbacks(repeater);
        releaseAll();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ persistence

    private static final int LAYOUT_VERSION = 2;

    private void loadLayout() {
        if (prefs.getInt("layout_version", 0) != LAYOUT_VERSION) {
            prefs.edit().clear().putInt("layout_version", LAYOUT_VERSION).apply();
        }
        for (Btn b : btns) {
            String id = b.spec.id;
            b.x = clamp(prefs.getFloat(id + ".x", b.spec.defX), 0.02f, 0.98f);
            b.y = clamp(prefs.getFloat(id + ".y", b.spec.defY), 0.02f, 0.98f);
            b.scale = clamp(prefs.getFloat(id + ".s", 1f), 0.4f, 3f);
            b.hidden = b.spec.deletable && prefs.getBoolean(id + ".h", false);
        }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = prefs.edit();
        for (Btn b : btns) {
            String id = b.spec.id;
            e.putFloat(id + ".x", b.x);
            e.putFloat(id + ".y", b.y);
            e.putFloat(id + ".s", b.scale);
            e.putBoolean(id + ".h", b.hidden);
        }
        e.apply();
    }

    /** "Throw off": everything goes back to the factory layout. */
    private void resetLayout() {
        prefs.edit().clear().putInt("layout_version", LAYOUT_VERSION).apply();
        for (Btn b : btns) {
            b.x = b.spec.defX;
            b.y = b.spec.defY;
            b.scale = 1f;
            b.hidden = false;
        }
        selected = -1;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------ geometry

    private float cx(Btn b) { return b.x * getWidth(); }
    private float cy(Btn b) { return b.y * getHeight(); }
    private float diameter(Btn b) { return b.spec.size * Math.min(getWidth(), getHeight()) * b.scale; }

    private int currentSet() { return guiMode ? MENU : GAME; }

    private boolean shownInPlay(Btn b) {
        return !b.hidden && (b.spec.sets & currentSet()) != 0;
    }

    private int hitPlayButton(float x, float y) {
        for (int i = btns.length - 1; i >= 0; i--) {
            Btn b = btns[i];
            if (!shownInPlay(b) || b.spec.key == K_STICK) continue;
            float dx = x - cx(b), dy = y - cy(b);
            float r = diameter(b) / 2f + 6f * density;
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private Btn stickBtn() {
        for (Btn b : btns) if (b.spec.key == K_STICK) return b;
        return null;
    }

    private boolean inStickZone(float x, float y) {
        Btn s = stickBtn();
        if (s == null || !shownInPlay(s)) return false;
        float dx = x - cx(s), dy = y - cy(s);
        float r = diameter(s);                     // 2x the radius: forgiving
        return dx * dx + dy * dy <= r * r;
    }

    private float stickRadius() {
        Btn s = stickBtn();
        return s == null ? 60f * density : diameter(s) / 2f;
    }

    // ------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;
        int action = e.getActionMasked();

        if (editMode) {
            handleEditTouch(e, action);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN && guiMode) {
            // In menus only the small buttons react; everything else must reach SDL (mouse emulation)
            if (hitPlayButton(e.getX(), e.getY()) < 0) return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                onDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    onMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                onUp(e.getPointerId(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void onDown(int id, float x, float y) {
        if (id < 0 || id >= role.length) return;
        int bi = hitPlayButton(x, y);
        if (bi >= 0) {
            Btn b = btns[bi];
            role[id] = ROLE_BUTTON;
            buttonOf[id] = bi;
            lastX[id] = x;
            lastY[id] = y;
            b.pressed = true;
            b.pointerId = id;
            b.downTime = SystemClock.uptimeMillis();
            if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            switch (b.spec.key) {
                case K_KEYBOARD:
                    toggleKeyboard();
                    break;
                case K_SETTINGS:
                    enterEditMode();
                    break;
                default:
                    SDLActivity.onNativeKeyDown(b.spec.key);
                    if (b.spec.repeat) {
                        b.nextRepeat = b.downTime + 400;
                        removeCallbacks(repeater);
                        postDelayed(repeater, 30);
                    }
                    break;
            }
            return;
        }
        if (guiMode) return;
        if (stickPointer < 0 && inStickZone(x, y)) {
            role[id] = ROLE_STICK;
            stickPointer = id;
            stickX = x;
            stickY = y;
            updateStick();
            return;
        }
        role[id] = ROLE_LOOK;
        lastX[id] = x;
        lastY[id] = y;
    }

    private void onMove(int id, float x, float y) {
        if (id < 0 || id >= role.length) return;
        switch (role[id]) {
            case ROLE_STICK:
                stickX = x;
                stickY = y;
                updateStick();
                break;
            case ROLE_LOOK:
                look(id, x, y);
                break;
            case ROLE_BUTTON: {
                // dragging the finger that holds FIRE turns the view (the button stays pressed)
                int bi = buttonOf[id];
                if (bi >= 0 && btns[bi].spec.id.equals("fire")) look(id, x, y);
                break;
            }
            default:
                break;
        }
    }

    private void look(int id, float x, float y) {
        float dx = (x - lastX[id]) * lookSens * 0.6f + remX;
        float dy = (y - lastY[id]) * lookSens * 0.6f + remY;
        lastX[id] = x;
        lastY[id] = y;
        int ix = (int) dx, iy = (int) dy;
        remX = dx - ix;
        remY = dy - iy;
        if (ix != 0 || iy != 0) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, ix, iy, true);
        }
    }

    private void onUp(int id) {
        if (id < 0 || id >= role.length) return;
        switch (role[id]) {
            case ROLE_STICK:
                stickPointer = -1;
                setMoveKeys(false, false, false, false, false);
                break;
            case ROLE_BUTTON: {
                int bi = buttonOf[id];
                if (bi >= 0) {
                    Btn b = btns[bi];
                    boolean wasPressed = b.pressed;
                    b.pressed = false;
                    b.pointerId = -1;
                    if (wasPressed && b.spec.key >= 0) SDLActivity.onNativeKeyUp(b.spec.key);
                }
                break;
            }
            default:
                break;
        }
        role[id] = ROLE_NONE;
        buttonOf[id] = -1;
    }

    private void releaseAll() {
        for (Btn b : btns) {
            if (b.pressed && b.spec.key >= 0) SDLActivity.onNativeKeyUp(b.spec.key);
            b.pressed = false;
            b.pointerId = -1;
        }
        setMoveKeys(false, false, false, false, false);
        stickPointer = -1;
        java.util.Arrays.fill(role, ROLE_NONE);
        java.util.Arrays.fill(buttonOf, -1);
    }

    // ------------------------------------------------------------ stick -> keys

    private void updateStick() {
        Btn s = stickBtn();
        if (s == null) return;
        float r = stickRadius();
        float dx = stickX - cx(s), dy = stickY - cy(s);
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > r) {
            dx = dx * r / len;
            dy = dy * r / len;
            len = r;
        }
        stickX = cx(s) + dx;
        stickY = cy(s) + dy;
        float nx = dx / r, ny = dy / r;
        setMoveKeys(ny < -0.25f, ny > 0.25f, nx < -0.35f, nx > 0.35f, len / r > 0.65f);
    }

    private void setMoveKeys(boolean fwd, boolean back, boolean left, boolean right, boolean run) {
        if (fwd != kFwd)     { kFwd = fwd;     key(KeyEvent.KEYCODE_W, fwd); }
        if (back != kBack)   { kBack = back;   key(KeyEvent.KEYCODE_S, back); }
        if (left != kLeft)   { kLeft = left;   key(KeyEvent.KEYCODE_A, left); }
        if (right != kRight) { kRight = right; key(KeyEvent.KEYCODE_D, right); }
        if (run != kRun)     { kRun = run;     key(KeyEvent.KEYCODE_SHIFT_LEFT, run); }
    }

    private static void key(int code, boolean down) {
        if (down) SDLActivity.onNativeKeyDown(code);
        else SDLActivity.onNativeKeyUp(code);
    }

    // ------------------------------------------------------------ soft keyboard

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (!kbVisible) {
            SDLActivity.showTextInput(0, 0, 16, 16);
            kbVisible = true;
        } else {
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            kbVisible = false;
        }
    }

    // ------------------------------------------------------------ layout editor

    private static final String[] PANEL_IDLE = { "GAME", "SIZE -", "SIZE +", "DELETE", "THROW OFF", "DONE" };

    private void enterEditMode() {
        releaseAll();
        editMode = true;
        editSet = currentSet();
        selected = -1;
        invalidate();
    }

    private void leaveEditMode() {
        editMode = false;
        selected = -1;
        panelPressed = -1;
        saveLayout();
        invalidate();
    }

    /** Called by the activity for the Android back button. Returns true if it was handled. */
    boolean onBack() {
        if (editMode) {
            leaveEditMode();
            return true;
        }
        return false;
    }

    private RectF panelRect() {
        float pw = Math.min(getWidth() * 0.46f, 360f * density);
        float ph = 124f * density;
        float l = (getWidth() - pw) / 2f, t = (getHeight() - ph) / 2f;
        return new RectF(l, t, l + pw, t + ph);
    }

    private RectF panelButton(int i) {
        RectF p = panelRect();
        float pad = 8f * density, title = 26f * density;
        float bw = (p.width() - pad * 4f) / 3f;
        float bh = (p.height() - title - pad * 3f) / 2f;
        int col = i % 3, row = i / 3;
        float l = p.left + pad + col * (bw + pad);
        float t = p.top + title + pad + row * (bh + pad);
        return new RectF(l, t, l + bw, t + bh);
    }

    private int hitPanel(float x, float y) {
        for (int i = 0; i < 6; i++) {
            if (panelButton(i).contains(x, y)) return i;
        }
        return panelRect().contains(x, y) ? 99 : -1;
    }

    private boolean shownInEdit(Btn b) {
        return (b.spec.sets & editSet) != 0;
    }

    private int hitEditButton(float x, float y) {
        for (int i = btns.length - 1; i >= 0; i--) {
            Btn b = btns[i];
            if (!shownInEdit(b)) continue;
            float dx = x - cx(b), dy = y - cy(b);
            float r = Math.max(diameter(b) / 2f, 22f * density);
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private void handleEditTouch(MotionEvent e, int action) {
        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int p = hitPanel(x, y);
                if (p >= 0) {
                    panelPressed = p;
                    return;
                }
                int bi = hitEditButton(x, y);
                selected = bi;
                if (bi >= 0) {
                    dragDX = cx(btns[bi]) - x;
                    dragDY = cy(btns[bi]) - y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (panelPressed < 0 && selected >= 0) {
                    Btn b = btns[selected];
                    b.x = clamp((x + dragDX) / getWidth(), 0.02f, 0.98f);
                    b.y = clamp((y + dragDY) / getHeight(), 0.02f, 0.98f);
                }
                break;
            case MotionEvent.ACTION_UP: {
                if (panelPressed >= 0) {
                    int p = hitPanel(x, y);
                    if (p == panelPressed) onPanelAction(p);
                    panelPressed = -1;
                } else if (selected >= 0) {
                    saveLayout();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                panelPressed = -1;
                break;
            default:
                break;
        }
    }

    private void onPanelAction(int i) {
        Btn s = selected >= 0 ? btns[selected] : null;
        switch (i) {
            case 0:   // switch between the game and the menu layout
                editSet = editSet == GAME ? MENU : GAME;
                selected = -1;
                break;
            case 1:
                if (s != null) { s.scale = clamp(s.scale / 1.12f, 0.4f, 3f); saveLayout(); }
                break;
            case 2:
                if (s != null) { s.scale = clamp(s.scale * 1.12f, 0.4f, 3f); saveLayout(); }
                break;
            case 3:
                if (s != null && s.spec.deletable) { s.hidden = !s.hidden; saveLayout(); }
                break;
            case 4:
                resetLayout();
                break;
            case 5:
                leaveEditMode();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ drawing

    private void drawBtn(Canvas c, Btn b, float scaleFactor, int alpha) {
        float d = diameter(b) * scaleFactor;
        float x = cx(b), y = cy(b);
        if (b.spec.backdrop) {
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha(Math.min(alpha, 150));
            c.drawCircle(x, y, d / 2f * 0.98f, fill);
        }
        tmp.set(x - d / 2f, y - d / 2f, x + d / 2f, y + d / 2f);
        bmpPaint.setAlpha(alpha);
        c.drawBitmap(b.bmp, null, tmp, bmpPaint);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!enabled) return;

        if (editMode) {
            drawEditor(c);
            return;
        }

        for (Btn b : btns) {
            if (!shownInPlay(b)) continue;
            if (b.spec.key == K_STICK) {
                drawStick(c, b);
            } else if (b.pressed) {
                drawBtn(c, b, 0.92f, 255);
            } else {
                drawBtn(c, b, 1f, 215);
            }
        }
    }

    private void drawStick(Canvas c, Btn b) {
        drawBtn(c, b, 1f, stickPointer >= 0 ? 235 : 170);
        float d = diameter(b) * 0.42f;
        float kx = stickPointer >= 0 ? stickX : cx(b);
        float ky = stickPointer >= 0 ? stickY : cy(b);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(stickPointer >= 0 ? 190 : 90);
        c.drawCircle(kx, ky, d / 2f, fill);
    }

    private void drawEditor(Canvas c) {
        c.drawColor(0x99000000);

        for (int i = 0; i < btns.length; i++) {
            Btn b = btns[i];
            if (!shownInEdit(b)) continue;
            drawBtn(c, b, 1f, b.hidden ? 60 : 230);
            float r = diameter(b) / 2f;
            line.setColor(i == selected ? 0xFFFFEB3B : 0x66FFFFFF);
            line.setStrokeWidth((i == selected ? 3f : 1.5f) * density);
            c.drawCircle(cx(b), cy(b), r, line);
            if (b.hidden) {
                line.setColor(0xFFFF5252);
                c.drawLine(cx(b) - r * 0.6f, cy(b) - r * 0.6f, cx(b) + r * 0.6f, cy(b) + r * 0.6f, line);
            }
        }

        // panel
        RectF p = panelRect();
        fill.setColor(0xE6202020);
        c.drawRoundRect(p, 14f * density, 14f * density, fill);

        text.setTextSize(12f * density);
        text.setColor(0xFFCCCCCC);
        String title = selected >= 0 ? "Selected: " + btns[selected].spec.id : "Drag a button, tap to select";
        c.drawText(title, p.centerX(), p.top + 18f * density, text);

        for (int i = 0; i < 6; i++) {
            RectF r = panelButton(i);
            boolean enabledBtn = true;
            String label = PANEL_IDLE[i];
            if (i == 0) label = editSet == GAME ? "GAME" : "MENU";
            if (i == 3) {
                label = (selected >= 0 && btns[selected].hidden) ? "RESTORE" : "DELETE";
                enabledBtn = selected >= 0 && btns[selected].spec.deletable;
            }
            if (i == 1 || i == 2) enabledBtn = selected >= 0;
            int col = i == 5 ? 0xFF2E7D32 : (i == 4 ? 0xFFB71C1C : 0xFF424242);
            fill.setColor(panelPressed == i ? 0xFF757575 : col);
            fill.setAlpha(enabledBtn ? 255 : 110);
            c.drawRoundRect(r, 8f * density, 8f * density, fill);
            text.setColor(enabledBtn ? 0xFFFFFFFF : 0x88FFFFFF);
            text.setTextSize(12f * density);
            c.drawText(label, r.centerX(), r.centerY() + 4.5f * density, text);
        }
    }
}
