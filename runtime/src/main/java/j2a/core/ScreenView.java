package j2a.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * Transparent overlay above {@link GlScreen}: computes where the game image goes, draws the
 * on-screen controls and the FPS counter, and handles touch.
 *
 * Control modes (Config.keypad): "gamepad" (d-pad or joystick on the left, Xbox / PlayStation /
 * arcade buttons on the right, Select/Start = the phone's soft keys on top), "full" (phone keypad),
 * "minimal" (d-pad + OK) and "none". Controls form three groups (directions, buttons, top row)
 * that the player can drag to another place ("Mover controles").
 */
public class ScreenView extends View {

    // kinds
    static final int NORMAL = 0, SOFT_L = 1, SOFT_R = 2, SECONDARY = 4, DIR = 6, TOGGLE = 7, FACE = 8;

    static final class Button {
        final RectF r = new RectF();
        final int code, kind, group;
        final String label, id;
        int color = Color.WHITE;
        boolean pressed;

        Button(float l, float t, float rr, float b, int code, String label, int kind, int group, String id) {
            r.set(l, t, rr, b);
            this.code = code;
            this.label = label;
            this.kind = kind;
            this.group = group;
            this.id = id;
        }
    }

    private final Object presentLock = new Object();
    private final GlScreen gl;
    private int vw, vh;
    private final Rect dst = new Rect();
    private final ArrayList<Button> buttons = new ArrayList<Button>();
    private final SparseArray<Button> touches = new SparseArray<Button>();
    private final float density;
    /** Keypad hidden because a gamepad/keyboard is being used; any touch brings it back. */
    private volatile boolean controllerActive;

    /** Directions held on the d-pad / joystick (bits: up 1, down 2, left 4, right 8) and the knob. */
    private int dirMask;
    private float knobX = Float.NaN, knobY;
    /** "Mover controles": dragging moves a whole group; offsets are view fractions per group. */
    private boolean editing;
    private int dragGroup = -1;
    private float lastX, lastY;
    private float[] offsets = new float[6];
    private boolean offsetsLandscape, offsetsLoaded;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScreenView(Context ctx, GlScreen gl) {
        super(ctx);
        this.gl = gl;
        density = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vw = w;
        vh = h;
        relayout();
    }

    /** Re-draws keypad / FPS (any thread). */
    public void redraw() {
        postInvalidate();
    }

    void setControllerActive(boolean active) {
        if (controllerActive == active) return;
        controllerActive = active;
        post(new Runnable() { public void run() { relayout(); } });
    }

    /** "Mover controles" from the port menu: drag the groups, tap the game image to finish. */
    void startEditing() {
        editing = true;
        controllerActive = false;
        relayout();
    }

    /** Puts every group back where the layout places it. */
    void resetPositions() {
        offsets = new float[6];
        Settings.savePadOffsets(getContext(), vw > vh, offsets);
        relayout();
    }

    private String keypadMode() {
        return controllerActive ? "none" : Host.cfg.keypad;
    }

    @Override
    protected void onDraw(Canvas c) {
        drawControls(c);
        if (Host.cfg.showFps) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(14 * density);
            text.setColor(Color.YELLOW);
            c.drawText(gl.fps + " fps", 6 * density, 18 * density, text);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }
        if (editing) {
            String hint = "Arrastra los controles. Toca el juego para terminar.";
            text.setTextSize(15 * density);
            float tw = text.measureText(hint) / 2 + 12 * density, y = 64 * density;
            lit.setColor(Color.argb(200, 0, 0, 0));
            c.drawRoundRect(vw / 2f - tw, y - 24 * density, vw / 2f + tw, y + 10 * density, 8 * density, 8 * density, lit);
            text.setColor(Color.YELLOW);
            c.drawText(hint, vw / 2f, y, text);
            text.setColor(Color.WHITE);
        }
    }

    private void drawControls(Canvas c) {
        int alpha = Math.max(0, Math.min(255, Host.cfg.keypadAlpha));
        float radius = 10 * density;
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            int base = b.kind == SECONDARY ? alpha / 2 : alpha;
            if (editing) stroke.setColor(Color.argb(255, 255, 220, 0));
            else stroke.setColor(Color.argb(base, 255, 255, 255));
            fill.setColor(b.pressed ? Color.argb(Math.min(255, base + 60), 120, 170, 255)
                    : Color.argb(base / 3, 255, 255, 255));
            if (b.kind == DIR) {
                drawDirections(c, b, base);
                continue;
            }
            if (b.kind == FACE) {
                float rr = Math.min(b.r.width(), b.r.height()) / 2;
                c.drawCircle(b.r.centerX(), b.r.centerY(), rr, fill);
                c.drawCircle(b.r.centerX(), b.r.centerY(), rr, stroke);
            } else {
                c.drawRoundRect(b.r, radius, radius, fill);
                c.drawRoundRect(b.r, radius, radius, stroke);
            }
            String label = b.label;
            if (b.kind == SOFT_L || b.kind == SOFT_R) {
                String soft = Host.softLabel(b.kind == SOFT_L);
                if (soft != null) label = soft;
            }
            if (label == null || label.length() == 0) continue;
            float size = Math.min(b.r.height() * (b.kind == FACE ? 0.45f : 0.42f), 22 * density);
            text.setTextSize(size);
            float tw = text.measureText(label);
            float max = b.r.width() - 8 * density;
            if (tw > max && tw > 0) text.setTextSize(size * max / tw);
            int col = b.kind == FACE ? b.color : Color.WHITE;
            text.setColor(Color.argb(Math.min(255, base + 80), Color.red(col), Color.green(col), Color.blue(col)));
            float ty = b.r.centerY() - (text.descent() + text.ascent()) / 2;
            if (b.kind == FACE) ty -= b.r.height() * 0.08f;
            c.drawText(label, b.r.centerX(), ty, text);
            if (b.kind == FACE) { // which game key it sends, small
                text.setTextSize(size * 0.42f);
                text.setColor(Color.argb(Math.min(255, base + 40), 255, 255, 255));
                c.drawText(keyLabel(b.code), b.r.centerX(), b.r.bottom - b.r.height() * 0.14f, text);
            }
        }
        text.setColor(Color.WHITE);
    }

    /** D-pad (cross with the held arms lit) or joystick (ring + knob). */
    private void drawDirections(Canvas c, Button b, int base) {
        float cx = b.r.centerX(), cy = b.r.centerY(), s = b.r.width();
        if (Host.cfg.padStick) {
            c.drawCircle(cx, cy, s / 2, fill);
            c.drawCircle(cx, cy, s / 2, stroke);
            float kx = Float.isNaN(knobX) ? cx : knobX, ky = Float.isNaN(knobX) ? cy : knobY;
            lit.setColor(Color.argb(Math.min(255, base + 40), 200, 210, 230));
            c.drawCircle(kx, ky, s * 0.2f, lit);
            c.drawCircle(kx, ky, s * 0.2f, stroke);
            return;
        }
        float t = s / 3, r = 8 * density;
        RectF[] arms = {new RectF(cx - t / 2, cy - s / 2, cx + t / 2, cy - t / 2),   // up
                new RectF(cx - t / 2, cy + t / 2, cx + t / 2, cy + s / 2),          // down
                new RectF(cx - s / 2, cy - t / 2, cx - t / 2, cy + t / 2),          // left
                new RectF(cx + t / 2, cy - t / 2, cx + s / 2, cy + t / 2)};         // right
        String[] arrows = {"▲", "▼", "◀", "▶"};
        lit.setColor(Color.argb(Math.min(255, base + 60), 120, 170, 255));
        c.drawRect(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2, fill);
        text.setTextSize(t * 0.4f);
        text.setColor(Color.argb(Math.min(255, base + 80), 255, 255, 255));
        for (int i = 0; i < 4; i++) {
            c.drawRoundRect(arms[i], r, r, (dirMask & (1 << i)) != 0 ? lit : fill);
            c.drawRoundRect(arms[i], r, r, stroke);
            c.drawText(arrows[i], arms[i].centerX(), arms[i].centerY() - (text.descent() + text.ascent()) / 2, text);
        }
    }

    static String keyLabel(int code) {
        switch (code) {
            case Keys.FIRE: return "OK";
            case Keys.SOFT_LEFT: return "◀soft";
            case Keys.SOFT_RIGHT: return "soft▶";
            case Keys.UP: return "▲";
            case Keys.DOWN: return "▼";
            case Keys.LEFT: return "◀";
            case Keys.RIGHT: return "▶";
            default: return Keys.name(code);
        }
    }

    // ---------------------------------------------------------------- layout

    public void relayout() {
        synchronized (presentLock) {
            buttons.clear();
            int w = vw, h = vh;
            if (w <= 0 || h <= 0) return;
            boolean land = w > h;
            if (!offsetsLoaded || offsetsLandscape != land) {
                offsets = Settings.padOffsets(getContext(), land);
                offsetsLandscape = land;
                offsetsLoaded = true;
            }
            String mode = keypadMode();
            if ("none".equals(mode)) {
                fit(0, 0, w, h, false);
            } else if ("gamepad".equals(mode)) {
                layoutGamepad(w, h, land);
            } else {
                layoutPhone(w, h, land, "full".equals(mode));
            }
            for (int i = 0; i < buttons.size(); i++) { // where the player dragged each group
                Button b = buttons.get(i);
                b.r.offset(offsets[b.group * 2] * w, offsets[b.group * 2 + 1] * h);
            }
        }
        gl.setDestination(dst);
        invalidate();
    }

    private void layoutPhone(int w, int h, boolean land, boolean full) {
        float pad = 6 * density;
        if (!land) {
            fit(0, 0, w, (int) (h * 0.62f), true);
            float top = dst.bottom + pad;
            float rowH = Math.min((h - top) * 0.18f, 54 * density);
            add(0, top, w * 0.4f, top + rowH, Keys.SOFT_LEFT, "", SOFT_L, 2, "select");
            add(w * 0.4f, top, w * 0.6f, top + rowH, 0, "Mando", TOGGLE, 2, "toggle");
            add(w * 0.6f, top, w, top + rowH, Keys.SOFT_RIGHT, "", SOFT_R, 2, "start");
            float bodyTop = top + rowH + pad;
            addDpad(new RectF(0, bodyTop, w * 0.5f, h - pad), full);
            RectF right = new RectF(w * 0.5f, bodyTop, w, h - pad);
            if (full) addGrid(right); else addOk(right);
        } else {
            int side = (int) (w * 0.22f);
            if (Host.cfg.center) fit(0, 0, w, h, false);
            else fit(side, 0, w - side, h, false);
            float lw = side, rx = w - side;
            float rowH = Math.min(h * 0.16f, 54 * density);
            add(0, 0, lw * 0.7f, rowH, Keys.SOFT_LEFT, "", SOFT_L, 2, "select");
            add(lw * 0.7f, 0, lw, rowH, 0, "Mando", TOGGLE, 2, "toggle");
            add(rx, 0, w, rowH, Keys.SOFT_RIGHT, "", SOFT_R, 2, "start");
            addDpad(new RectF(0, rowH + pad, lw, h - pad), full);
            RectF right = new RectF(rx, rowH + pad, w, h - pad);
            if (full) addGrid(right); else addOk(right);
        }
    }

    private void layoutGamepad(int w, int h, boolean land) {
        Config c = Host.cfg;
        float k = Math.max(50, Math.min(200, c.padScale)) / 100f;
        float m = 12 * density;
        float pillW = Math.min((land ? w : w * 1.6f) * 0.13f, 110 * density) * k, pillH = 34 * density * k;
        float s; // size of the d-pad and of the button cluster
        float topY, bodyCy;
        if (land) {
            s = Math.min(h * 0.42f, w * 0.2f) * k;
            int side = (int) (s + 2 * m);
            if (c.center) fit(0, 0, w, h, false);
            else fit(side, 0, w - side, h, false);
            topY = m;
            bodyCy = h - m - s / 2;
            addTop(m, topY, w - m, pillW, pillH);
        } else {
            fit(0, 0, w, (int) (h * 0.58f), true);
            s = Math.min(w * 0.4f, (h - dst.bottom) * 0.62f) * k;
            topY = dst.bottom + m;
            bodyCy = (topY + pillH + h) / 2;
            addTop(w / 2f - pillW * 1.6f, topY, w / 2f + pillW * 1.6f, pillW, pillH);
        }
        if (!c.padHidden("dir")) {
            float cx = m + s / 2 + (land ? m : 0);
            add(cx - s / 2, bodyCy - s / 2, cx + s / 2, bodyCy + s / 2, 0, "", DIR, 0, "dir");
        }
        addFace(w - m - s / 2 - (land ? m : 0), bodyCy, s);
    }

    /** Select (left soft key), the 123 switch and Start (right soft key). */
    private void addTop(float left, float top, float right, float pillW, float pillH) {
        Config c = Host.cfg;
        if (!c.padHidden("select")) add(left, top, left + pillW, top + pillH, Keys.SOFT_LEFT, "Select", SOFT_L, 2, "select");
        if (!c.padHidden("toggle")) {
            add(left + pillW, top, left + pillW * 1.6f, top + pillH, 0, "123", TOGGLE, 2, "toggle");
        }
        if (!c.padHidden("start")) add(right - pillW, top, right, top + pillH, Keys.SOFT_RIGHT, "Start", SOFT_R, 2, "start");
    }

    /** Ids, labels and colors of each style; positions: bottom, right, left, top (+ arcade extras). */
    private void addFace(float cx, float cy, float s) {
        Config c = Host.cfg;
        String style = c.padStyle;
        if ("arcade".equals(style)) {
            String[] ids = {"x", "y", "z", "a", "b", "c"};
            String[] labels = {"X", "Y", "Z", "A", "B", "C"};
            float d = s * 0.36f, r = s * 0.16f;
            for (int i = 0; i < 6; i++) {
                float x = cx + (i % 3 - 1) * d, y = cy + (i < 3 ? -0.55f : 0.55f) * d;
                addFace(ids[i], labels[i], Color.rgb(255, 230, 140), x, y, r);
            }
            return;
        }
        boolean play = "play".equals(style);
        String[] ids = {"a", "b", "x", "y"};
        String[] labels = play ? new String[]{"✕", "○", "□", "△"} : new String[]{"A", "B", "X", "Y"};
        int[] colors = play
                ? new int[]{Color.rgb(140, 180, 255), Color.rgb(255, 120, 120), Color.rgb(240, 150, 220), Color.rgb(120, 230, 180)}
                : new int[]{Color.rgb(120, 220, 120), Color.rgb(255, 110, 110), Color.rgb(110, 170, 255), Color.rgb(255, 220, 90)};
        float d = s * 0.31f, r = s * 0.18f;
        float[][] pos = {{0, d}, {d, 0}, {-d, 0}, {0, -d}};
        for (int i = 0; i < 4; i++) addFace(ids[i], labels[i], colors[i], cx + pos[i][0], cy + pos[i][1], r);
    }

    private void addFace(String id, String label, int color, float x, float y, float r) {
        if (Host.cfg.padHidden(id)) return;
        Button b = new Button(x - r, y - r, x + r, y + r, Host.cfg.padCode(id), label, FACE, 1, id);
        b.color = color;
        buttons.add(b);
    }

    private void fit(int ax, int ay, int aw, int ah, boolean top) {
        Config c = Host.cfg;
        Rect area = AutoFit.area();
        boolean turned = Host.rotation() == 90 || Host.rotation() == 270;
        int sw = turned ? area.height() : area.width(), sh = turned ? area.width() : area.height();
        aw -= ax;
        ah -= ay;
        int w, h;
        if ("stretch".equals(c.scaleMode)) {
            w = aw;
            h = ah;
        } else if ("wide".equals(c.scaleMode)) {
            // widen (or heighten) the game toward the free area, but never past 16:9 / 9:16
            float game = sw / (float) sh, room = aw / (float) ah, target = game;
            if (game >= 1 && room > game) target = Math.min(room, 16f / 9f);
            else if (game < 1 && room < game) target = Math.max(room, 9f / 16f);
            if (target > room) {
                w = aw;
                h = Math.round(aw / target);
            } else {
                h = ah;
                w = Math.round(ah * target);
            }
        } else {
            float s = Math.min(aw / (float) sw, ah / (float) sh);
            if ("integer".equals(c.scaleMode) && s >= 1) s = (float) Math.floor(s);
            w = Math.round(sw * s);
            h = Math.round(sh * s);
        }
        int x = ax + (aw - w) / 2;
        int y = top && !c.center ? ay : ay + (ah - h) / 2;
        dst.set(x, y, x + w, y + h);
    }

    private void add(float l, float t, float r, float b, int code, String label, int kind, int group, String id) {
        float in = 3 * density;
        if (r - l <= 2 * in || b - t <= 2 * in) return;
        buttons.add(new Button(l + in, t + in, r - in, b - in, code, label, kind, group, id));
    }

    private void addDpad(RectF a, boolean diagonals) {
        float s = Math.min(a.width(), a.height());
        float x = a.centerX() - s / 2, y = a.centerY() - s / 2, c = s / 3;
        add(x + c, y, x + 2 * c, y + c, Keys.UP, "▲", NORMAL, 0, "up");
        add(x, y + c, x + c, y + 2 * c, Keys.LEFT, "◀", NORMAL, 0, "left");
        add(x + c, y + c, x + 2 * c, y + 2 * c, Keys.FIRE, "OK", NORMAL, 0, "ok");
        add(x + 2 * c, y + c, x + 3 * c, y + 2 * c, Keys.RIGHT, "▶", NORMAL, 0, "right");
        add(x + c, y + 2 * c, x + 2 * c, y + 3 * c, Keys.DOWN, "▼", NORMAL, 0, "down");
        if (diagonals) {
            add(x, y, x + c, y + c, Keys.NUM1, "1", SECONDARY, 0, "1");
            add(x + 2 * c, y, x + 3 * c, y + c, Keys.NUM3, "3", SECONDARY, 0, "3");
            add(x, y + 2 * c, x + c, y + 3 * c, Keys.NUM7, "7", SECONDARY, 0, "7");
            add(x + 2 * c, y + 2 * c, x + 3 * c, y + 3 * c, Keys.NUM9, "9", SECONDARY, 0, "9");
        }
    }

    private void addGrid(RectF a) {
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        int[] codes = {Keys.NUM1, Keys.NUM2, Keys.NUM3, Keys.NUM4, Keys.NUM5, Keys.NUM6,
                Keys.NUM7, Keys.NUM8, Keys.NUM9, Keys.STAR, Keys.NUM0, Keys.POUND};
        float cw = a.width() / 3;
        float ch = Math.min(a.height() / 4, cw * 0.9f);
        float top = a.centerY() - ch * 2;
        for (int i = 0; i < 12; i++) {
            float l = a.left + (i % 3) * cw, t = top + (i / 3) * ch;
            add(l, t, l + cw, t + ch, codes[i], labels[i], NORMAL, 1, labels[i]);
        }
    }

    private void addOk(RectF a) {
        float s = Math.min(a.width(), a.height()) * 0.55f;
        add(a.centerX() - s / 2, a.centerY() - s / 2, a.centerX() + s / 2, a.centerY() + s / 2, Keys.FIRE, "OK", NORMAL, 1, "ok");
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && android.os.Build.VERSION.SDK_INT >= 30) {
            requestUnbufferedDispatch(e); // touches reach the game without waiting for the next frame
        }
        if (controllerActive && action == MotionEvent.ACTION_DOWN) {
            setControllerActive(false);
            return true;
        }
        if (editing) {
            edit(action, e);
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) up(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
        }
        return true;
    }

    /** Drag a group of controls; a tap outside every control ends editing and saves. */
    private void edit(int action, MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            Button b = hit(x, y);
            if (b == null) {
                editing = false;
                Settings.savePadOffsets(getContext(), vw > vh, offsets);
                relayout();
                return;
            }
            dragGroup = b.group;
            lastX = x;
            lastY = y;
        } else if (action == MotionEvent.ACTION_MOVE && dragGroup >= 0) {
            offsets[dragGroup * 2] += (x - lastX) / Math.max(1, vw);
            offsets[dragGroup * 2 + 1] += (y - lastY) / Math.max(1, vh);
            lastX = x;
            lastY = y;
            relayout();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragGroup = -1;
        }
    }

    private Button hit(float x, float y) {
        synchronized (presentLock) {
            for (int i = 0; i < buttons.size(); i++) {
                Button b = buttons.get(i);
                RectF r = b.r;
                if (b.kind == DIR || b.kind == FACE) { // a bit of slack around round controls
                    float sl = r.width() * 0.15f;
                    if (x >= r.left - sl && x <= r.right + sl && y >= r.top - sl && y <= r.bottom + sl) return b;
                } else if (r.contains(x, y)) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Fingers on the game image, by Android pointer id -> last game coordinates. Every finger is
     * sent to the game (Nokia multipoint style: System.getProperty("com.nokia.pointer.number")
     * tells which one), so on-screen pads drawn by touch games work with two or more fingers.
     */
    private final SparseArray<int[]> gamePointers = new SparseArray<int[]>();

    private void down(int id, float x, float y) {
        Button b = hit(x, y);
        if (b != null) {
            touches.put(id, b);
            if (b.kind == DIR) {
                if (Host.cfg.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                steer(b, x, y);
            } else {
                press(b);
            }
        } else if (dst.contains((int) x, (int) y)) {
            int[] p = map(x, y);
            gamePointers.put(id, p);
            Host.pointer(0, id, p[0], p[1]);
        }
    }

    private void move(int id, float x, float y) {
        Button old = touches.get(id);
        if (old != null) {
            if (old.kind == DIR) {
                steer(old, x, y);
                return;
            }
            Button nb = hit(x, y);
            if (nb != null && nb != old && nb.kind != DIR && slides(old) && slides(nb)) { // slide between keys
                release(old);
                touches.put(id, nb);
                press(nb);
            }
            return;
        }
        int[] p = gamePointers.get(id);
        if (p == null) return;
        int[] m = map(x, y);
        int gx = m[0], gy = m[1];
        if (gx == p[0] && gy == p[1]) return; // MOVE batches include fingers that didn't move
        p[0] = gx;
        p[1] = gy;
        Host.pointer(1, id, gx, gy);
    }

    private static boolean slides(Button b) {
        return b.kind == NORMAL || b.kind == SECONDARY || b.kind == FACE;
    }

    private void up(int id, float x, float y) {
        Button b = touches.get(id);
        if (b != null) {
            touches.remove(id);
            if (b.kind == DIR) {
                setDirections(0);
                knobX = Float.NaN;
                redraw();
            } else {
                release(b);
            }
            return;
        }
        int[] p = gamePointers.get(id);
        if (p == null) return;
        gamePointers.remove(id);
        int[] m = map(x, y);
        Host.pointer(2, id, m[0], m[1]);
        // Most of these games were written for one finger: a release often clears every touch
        // control. Remind the game about the fingers that are still down.
        for (int i = 0; i < gamePointers.size(); i++) {
            int[] q = gamePointers.valueAt(i);
            Host.pointer(1, gamePointers.keyAt(i), q[0], q[1]);
        }
    }

    /** D-pad / joystick: 8 directions from the finger's angle, with a small dead zone in the middle. */
    private void steer(Button b, float x, float y) {
        float r = b.r.width() / 2, dx = x - b.r.centerX(), dy = y - b.r.centerY();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int mask = 0;
        if (len > r * 0.22f) {
            float h = dx / len, v = dy / len; // 0.38 = cos 67.5°: 45° sectors, diagonals included
            if (v < -0.38f) mask |= 1;
            if (v > 0.38f) mask |= 2;
            if (h < -0.38f) mask |= 4;
            if (h > 0.38f) mask |= 8;
        }
        float lim = Math.min(len, r * 0.6f) / Math.max(len, 1f);
        knobX = b.r.centerX() + dx * lim;
        knobY = b.r.centerY() + dy * lim;
        setDirections(mask);
        redraw();
    }

    private void setDirections(int mask) {
        int[] codes = {Keys.UP, Keys.DOWN, Keys.LEFT, Keys.RIGHT};
        int changed = mask ^ dirMask;
        if (changed == 0) return;
        for (int i = 0; i < 4; i++) {
            if ((changed & (1 << i)) == 0) continue;
            Host.key((mask & (1 << i)) != 0 ? 0 : 1, codes[i]);
        }
        if ((mask & ~dirMask) != 0 && Host.cfg.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        dirMask = mask;
    }

    private void press(Button b) {
        b.pressed = true;
        if (Host.cfg.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (b.kind == TOGGLE) { // gamepad <-> phone keypad (for the number keys)
            Host.cfg.keypad = "gamepad".equals(Host.cfg.keypad) ? "full" : "gamepad";
            Settings.save(getContext(), Host.cfg);
            post(new Runnable() { public void run() { relayout(); } });
            return;
        }
        Host.key(0, b.code);
        redraw();
    }

    private void release(Button b) {
        b.pressed = false;
        if (b.kind != TOGGLE) Host.key(1, b.code);
        redraw();
    }

    /** View point -> game pixel, undoing scaling, AutoFit cropping and image rotation. */
    private int[] map(float x, float y) {
        float nx = (x - dst.left) / Math.max(1f, dst.width());
        float ny = (y - dst.top) / Math.max(1f, dst.height());
        float u, v;
        if (Host.rotation() == 90) { u = ny; v = 1 - nx; }
        else if (Host.rotation() == 270) { u = 1 - ny; v = nx; }
        else { u = nx; v = ny; }
        Rect a = AutoFit.area();
        int gx = a.left + (int) (u * a.width()), gy = a.top + (int) (v * a.height());
        return new int[]{Math.max(a.left, Math.min(a.right - 1, gx)), Math.max(a.top, Math.min(a.bottom - 1, gy))};
    }
}
