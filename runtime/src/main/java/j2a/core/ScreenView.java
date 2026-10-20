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
 * on-screen keypad (soft keys, d-pad, numeric keypad) and the FPS counter, and handles touch.
 */
public class ScreenView extends View {

    static final class Button {
        final RectF r = new RectF();
        final int code;
        final String label;
        final int kind; // 0 normal, 1 soft left, 2 soft right, 3 port menu, 4 secondary
        boolean pressed;

        Button(float l, float t, float rr, float b, int code, String label, int kind) {
            r.set(l, t, rr, b);
            this.code = code;
            this.label = label;
            this.kind = kind;
        }
    }

    private final Object presentLock = new Object();
    private final GlScreen gl;
    private int vw, vh;
    private final Rect dst = new Rect();
    private final ArrayList<Button> buttons = new ArrayList<Button>();
    private final SparseArray<Button> touches = new SparseArray<Button>();
    private int screenPointer = -1;
    private final float density;
    /** Keypad hidden because a gamepad/keyboard is being used; any touch brings it back. */
    private volatile boolean controllerActive;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

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

    private String keypadMode() {
        return controllerActive ? "none" : Host.cfg.keypad;
    }

    @Override
    protected void onDraw(Canvas c) {
        drawKeypad(c);
        if (Host.cfg.showFps) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(14 * density);
            text.setColor(Color.YELLOW);
            c.drawText(gl.fps + " fps", 6 * density, 18 * density, text);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawKeypad(Canvas c) {
        int alpha = Math.max(0, Math.min(255, Host.cfg.keypadAlpha));
        float radius = 10 * density;
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            int base = b.kind == 4 ? alpha / 2 : alpha;
            fill.setColor(b.pressed ? Color.argb(Math.min(255, base + 60), 120, 170, 255)
                    : Color.argb(base / 3, 255, 255, 255));
            stroke.setColor(Color.argb(base, 255, 255, 255));
            c.drawRoundRect(b.r, radius, radius, fill);
            c.drawRoundRect(b.r, radius, radius, stroke);
            String label = b.label;
            if (b.kind == 1 || b.kind == 2) {
                String soft = Host.softLabel(b.kind == 1);
                if (soft != null) label = soft;
            }
            if (label == null || label.length() == 0) continue;
            float size = Math.min(b.r.height() * 0.42f, 22 * density);
            text.setTextSize(size);
            float tw = text.measureText(label);
            float max = b.r.width() - 8 * density;
            if (tw > max && tw > 0) text.setTextSize(size * max / tw);
            text.setColor(Color.argb(Math.min(255, base + 80), 255, 255, 255));
            float ty = b.r.centerY() - (text.descent() + text.ascent()) / 2;
            c.drawText(label, b.r.centerX(), ty, text);
        }
    }

    // ---------------------------------------------------------------- layout

    public void relayout() {
        synchronized (presentLock) {
            buttons.clear();
            int w = vw, h = vh;
            if (w <= 0 || h <= 0) return;
            Config c = Host.cfg;
            boolean keypad = !"none".equals(keypadMode());
            boolean full = "full".equals(keypadMode());
            float pad = 6 * density;
            if (!keypad) {
                fit(0, 0, w, h);
            } else if (h >= w) {
                fit(0, 0, w, (int) (h * 0.62f));
                float top = dst.bottom + pad;
                float rowH = Math.min((h - top) * 0.18f, 54 * density);
                addSoftRow(0, top, w, top + rowH);
                float bodyTop = top + rowH + pad;
                addDpad(new RectF(0, bodyTop, w * 0.5f, h - pad), full);
                RectF right = new RectF(w * 0.5f, bodyTop, w, h - pad);
                if (full) addGrid(right); else addOk(right);
            } else {
                int side = (int) (w * 0.22f);
                fit(side, 0, w - side, h);
                float lw = Math.max(dst.left, side * 0.6f);
                float rx = Math.min(dst.right, w - side * 0.6f);
                float rowH = Math.min(h * 0.16f, 54 * density);
                add(0, 0, lw, rowH, Keys.SOFT_LEFT, "", 1);
                add(rx, 0, rx + (w - rx) * 0.7f, rowH, Keys.SOFT_RIGHT, "", 2);
                add(rx + (w - rx) * 0.7f, 0, w, rowH, 0, "☰", 3);
                addDpad(new RectF(0, rowH + pad, lw, h - pad), full);
                RectF right = new RectF(rx, rowH + pad, w, h - pad);
                if (full) addGrid(right); else addOk(right);
            }
        }
        gl.setDestination(dst);
        invalidate();
    }

    private void fit(int ax, int ay, int aw, int ah) {
        Config c = Host.cfg;
        Rect area = AutoFit.area();
        int sw = area.width(), sh = area.height();
        aw -= ax;
        ah -= ay;
        int w, h;
        if ("stretch".equals(c.scaleMode)) {
            w = aw;
            h = ah;
        } else {
            float s = Math.min(aw / (float) sw, ah / (float) sh);
            if ("integer".equals(c.scaleMode) && s >= 1) s = (float) Math.floor(s);
            w = Math.round(sw * s);
            h = Math.round(sh * s);
        }
        int x = ax + (aw - w) / 2;
        int y = vh >= vw && !"none".equals(keypadMode()) ? ay : ay + (ah - h) / 2;
        dst.set(x, y, x + w, y + h);
    }

    private void add(float l, float t, float r, float b, int code, String label, int kind) {
        float in = 3 * density;
        if (r - l <= 2 * in || b - t <= 2 * in) return;
        buttons.add(new Button(l + in, t + in, r - in, b - in, code, label, kind));
    }

    private void addSoftRow(float l, float t, float r, float b) {
        float w = r - l;
        add(l, t, l + w * 0.36f, b, Keys.SOFT_LEFT, "", 1);
        add(l + w * 0.42f, t, l + w * 0.58f, b, 0, "☰", 3);
        add(l + w * 0.64f, t, r, b, Keys.SOFT_RIGHT, "", 2);
    }

    private void addDpad(RectF a, boolean diagonals) {
        float s = Math.min(a.width(), a.height());
        float x = a.centerX() - s / 2, y = a.centerY() - s / 2, c = s / 3;
        add(x + c, y, x + 2 * c, y + c, Keys.UP, "▲", 0);
        add(x, y + c, x + c, y + 2 * c, Keys.LEFT, "◀", 0);
        add(x + c, y + c, x + 2 * c, y + 2 * c, Keys.FIRE, "OK", 0);
        add(x + 2 * c, y + c, x + 3 * c, y + 2 * c, Keys.RIGHT, "▶", 0);
        add(x + c, y + 2 * c, x + 2 * c, y + 3 * c, Keys.DOWN, "▼", 0);
        if (diagonals) {
            add(x, y, x + c, y + c, Keys.NUM1, "1", 4);
            add(x + 2 * c, y, x + 3 * c, y + c, Keys.NUM3, "3", 4);
            add(x, y + 2 * c, x + c, y + 3 * c, Keys.NUM7, "7", 4);
            add(x + 2 * c, y + 2 * c, x + 3 * c, y + 3 * c, Keys.NUM9, "9", 4);
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
            add(l, t, l + cw, t + ch, codes[i], labels[i], 0);
        }
    }

    private void addOk(RectF a) {
        float s = Math.min(a.width(), a.height()) * 0.55f;
        add(a.centerX() - s / 2, a.centerY() - s / 2, a.centerX() + s / 2, a.centerY() + s / 2, Keys.FIRE, "OK", 0);
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (controllerActive && action == MotionEvent.ACTION_DOWN) {
            setControllerActive(false);
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

    private Button hit(float x, float y) {
        synchronized (presentLock) {
            for (int i = 0; i < buttons.size(); i++) {
                Button b = buttons.get(i);
                if (b.r.contains(x, y)) return b;
            }
        }
        return null;
    }

    private void down(int id, float x, float y) {
        Button b = hit(x, y);
        if (b != null) {
            touches.put(id, b);
            press(b);
        } else if (screenPointer == -1 && dst.contains((int) x, (int) y)) {
            screenPointer = id;
            Host.pointer(0, vx(x), vy(y));
        }
    }

    private void move(int id, float x, float y) {
        Button old = touches.get(id);
        if (old != null) {
            Button nb = hit(x, y);
            if (nb != null && nb != old && old.kind != 3 && nb.kind != 3) {
                release(old);
                touches.put(id, nb);
                press(nb);
            }
        } else if (id == screenPointer) {
            Host.pointer(1, vx(x), vy(y));
        }
    }

    private void up(int id, float x, float y) {
        Button b = touches.get(id);
        if (b != null) {
            touches.remove(id);
            release(b);
        } else if (id == screenPointer) {
            screenPointer = -1;
            Host.pointer(2, vx(x), vy(y));
        }
    }

    private void press(Button b) {
        b.pressed = true;
        if (Host.cfg.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (b.kind == 3) Host.showPortMenu();
        else Host.key(0, b.code);
        redraw();
    }

    private void release(Button b) {
        b.pressed = false;
        if (b.kind != 3) Host.key(1, b.code);
        redraw();
    }

    private int vx(float x) {
        int w = Math.max(1, dst.width());
        Rect a = AutoFit.area();
        int v = a.left + (int) ((x - dst.left) * a.width() / w);
        return Math.max(a.left, Math.min(a.right - 1, v));
    }

    private int vy(float y) {
        int h = Math.max(1, dst.height());
        Rect a = AutoFit.area();
        int v = a.top + (int) ((y - dst.top) * a.height() / h);
        return Math.max(a.top, Math.min(a.bottom - 1, v));
    }
}
