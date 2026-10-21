package j2a.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
 * Control modes (Config.keypad): "gamepad" (skins xbox / switch / play: d-pad AND joystick, four face
 * buttons, L/R, Select/Start; every one sends the game key set in Config.padMap), "full" (phone
 * keypad), "minimal" (d-pad + OK) and "none". Each piece can be dragged elsewhere ("Mover controles").
 */
public class ScreenView extends View {

    // kinds
    static final int NORMAL = 0, SOFT_L = 1, SOFT_R = 2, SECONDARY = 4, DIR = 6, TOGGLE = 7, FACE = 8, PILL = 9;
    // movable groups (two offsets each, saved per orientation)
    static final int G_DPAD = 0, G_FACE = 1, G_TOP = 2, G_STICK = 3, G_L = 4, G_R = 5, GROUPS = 6;

    static final class Button {
        final RectF r = new RectF();
        final int code, kind, group;
        final String label, id;
        int color = Color.WHITE;
        boolean pressed;
        /** Directions held (up 1, down 2, left 4, right 8) and the knob, for DIR controls. */
        int mask;
        float knobX = Float.NaN, knobY;

        Button(float l, float t, float rr, float b, int code, String label, int kind, int group, String id) {
            r.set(l, t, rr, b);
            this.code = code;
            this.label = label;
            this.kind = kind;
            this.group = group;
            this.id = id;
        }
    }

    /** Skin look: color of a held control and the face buttons' letters/colors. */
    private static final class Skin {
        int accent;
        String[] face;      // labels for a, b, x, y
        int[] faceColor;
        float[][] facePos;  // a, b, x, y offsets in cluster units (x right, y down)
        String l, r, select, start;
    }

    private static Skin skin(String style) {
        Skin s = new Skin();
        int ink = Color.rgb(38, 41, 46);
        if ("switch".equals(style)) {
            s.accent = Color.rgb(230, 0, 18);
            s.face = new String[]{"A", "B", "X", "Y"};
            s.faceColor = new int[]{ink, ink, ink, ink};
            s.facePos = new float[][]{{1, 0}, {0, 1}, {0, -1}, {-1, 0}};
            s.l = "L";
            s.r = "R";
            s.select = "−";
            s.start = "+";
        } else if ("play".equals(style)) {
            s.accent = Color.rgb(46, 109, 180);
            s.face = new String[]{"cross", "circle", "square", "triangle"}; // drawn as shapes
            s.faceColor = new int[]{Color.rgb(70, 110, 200), Color.rgb(210, 60, 70), Color.rgb(200, 90, 170), Color.rgb(40, 160, 120)};
            s.facePos = new float[][]{{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
            s.l = "L1";
            s.r = "R1";
            s.select = "Select";
            s.start = "Start";
        } else {
            s.accent = Color.rgb(16, 124, 16);
            s.face = new String[]{"A", "B", "X", "Y"};
            s.faceColor = new int[]{Color.rgb(30, 140, 50), Color.rgb(200, 40, 40), Color.rgb(30, 100, 200), Color.rgb(200, 150, 0)};
            s.facePos = new float[][]{{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
            s.l = "LB";
            s.r = "RB";
            s.select = "Select";
            s.start = "Start";
        }
        return s;
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

    /** "Mover controles": dragging moves one piece; offsets are view fractions per group. */
    private boolean editing;
    private int dragGroup = -1;
    private float lastX, lastY;
    private float[] offsets = new float[GROUPS * 2];
    private boolean offsetsLandscape, offsetsLoaded;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), armV = new Path(), armH = new Path(), cross = new Path();
    private final RectF tmp = new RectF();

    public ScreenView(Context ctx, GlScreen gl) {
        super(ctx);
        this.gl = gl;
        density = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
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

    /** "Mover controles" from the port menu: drag the pieces, tap the game image to finish. */
    void startEditing() {
        editing = true;
        controllerActive = false;
        relayout();
    }

    /** Puts every piece back where the layout places it. */
    void resetPositions() {
        offsets = new float[GROUPS * 2];
        Settings.savePadOffsets(getContext(), vw > vh, offsets);
        relayout();
    }

    private String keypadMode() {
        return controllerActive ? "none" : Host.cfg.keypad;
    }

    // ---------------------------------------------------------------- drawing

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
        boolean pad = "gamepad".equals(keypadMode());
        Skin sk = skin(Host.cfg.padStyle);
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            if (pad) drawPadButton(c, b, alpha, sk);
            else drawKey(c, b, alpha);
        }
        text.setColor(Color.WHITE);
    }

    /** Phone keypad style: dark translucent keys with white labels. */
    private void drawKey(Canvas c, Button b, int alpha) {
        float radius = 10 * density;
        int base = b.kind == SECONDARY ? alpha / 2 : alpha;
        stroke.setColor(editing ? Color.rgb(255, 220, 0) : Color.argb(base, 255, 255, 255));
        fill.setColor(b.pressed ? Color.argb(Math.min(255, base + 60), 120, 170, 255) : Color.argb(base / 3, 255, 255, 255));
        c.drawRoundRect(b.r, radius, radius, fill);
        c.drawRoundRect(b.r, radius, radius, stroke);
        String label = b.label;
        if (b.kind == SOFT_L || b.kind == SOFT_R) {
            String soft = Host.softLabel(b.kind == SOFT_L);
            if (soft != null) label = soft;
        }
        drawLabel(c, b, label, Color.argb(Math.min(255, base + 80), 255, 255, 255), 0.42f);
    }

    /** Gamepad skin: light translucent shapes, dark glyphs, the skin's color while held. */
    private void drawPadButton(Canvas c, Button b, int alpha, Skin sk) {
        int ink = Color.argb(Math.min(255, alpha + 60), 38, 41, 46);
        fill.setColor(Color.argb(alpha, 228, 230, 234));
        stroke.setColor(editing ? Color.rgb(255, 200, 0) : Color.argb(Math.min(255, alpha / 2 + 30), 20, 22, 26));
        lit.setColor(withAlpha(sk.accent, Math.min(255, alpha + 70)));
        Paint body = b.pressed ? lit : fill;
        float cx = b.r.centerX(), cy = b.r.centerY(), s = Math.min(b.r.width(), b.r.height());
        switch (b.kind) {
            case DIR:
                if ("stick".equals(b.id)) drawStick(c, b, ink);
                else drawDpad(c, b, ink);
                return;
            case FACE: {
                c.drawCircle(cx, cy, s / 2, body);
                c.drawCircle(cx, cy, s / 2, stroke);
                int idx = "a".equals(b.id) ? 0 : "b".equals(b.id) ? 1 : "x".equals(b.id) ? 2 : 3;
                int col = b.pressed ? Color.WHITE : withAlpha(sk.faceColor[idx], Math.min(255, alpha + 80));
                if (sk.face[idx].length() > 1) drawSymbol(c, sk.face[idx], cx, cy, s * 0.22f, col);
                else drawLabel(c, b, sk.face[idx], col, 0.5f);
                return;
            }
            default: { // pills: L/R, Select/Start, 123
                float rr = b.r.height() / 2;
                c.drawRoundRect(b.r, rr, rr, body);
                c.drawRoundRect(b.r, rr, rr, stroke);
                String label = b.label;
                if (b.kind == SOFT_L || b.kind == SOFT_R) {
                    String soft = Host.softLabel(b.kind == SOFT_L); // the game's command name if it has one
                    label = soft != null ? soft : b.kind == SOFT_L ? sk.select : sk.start;
                } else if ("l".equals(b.id)) {
                    label = sk.l;
                } else if ("r".equals(b.id)) {
                    label = sk.r;
                }
                drawLabel(c, b, label, b.pressed ? Color.WHITE : ink, 0.5f);
            }
        }
    }

    /** One-piece cross with the held arms in the skin's color and small arrow heads. */
    private void drawDpad(Canvas c, Button b, int ink) {
        float cx = b.r.centerX(), cy = b.r.centerY(), s = b.r.width() / 2, t = s * 0.36f, rr = t * 0.45f;
        armV.reset();
        tmp.set(cx - t, cy - s, cx + t, cy + s);
        armV.addRoundRect(tmp, rr, rr, Path.Direction.CW);
        armH.reset();
        tmp.set(cx - s, cy - t, cx + s, cy + t);
        armH.addRoundRect(tmp, rr, rr, Path.Direction.CW);
        cross.op(armV, armH, Path.Op.UNION); // one piece: no darker overlap, clean outline
        c.drawPath(cross, fill);
        float[][] arms = {{cx - t, cy - s, cx + t, cy - t}, {cx - t, cy + t, cx + t, cy + s},
                {cx - s, cy - t, cx - t, cy + t}, {cx + t, cy - t, cx + s, cy + t}};
        for (int i = 0; i < 4; i++) {
            if ((b.mask & (1 << i)) == 0) continue;
            tmp.set(arms[i][0], arms[i][1], arms[i][2], arms[i][3]);
            c.drawRoundRect(tmp, rr, rr, lit);
        }
        c.drawPath(cross, stroke);
        glyph.setColor(ink);
        glyph.setStyle(Paint.Style.FILL);
        float a = t * 0.55f, o = s * 0.68f; // arrow size, distance from center
        float[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (float[] d : dirs) {
            float px = cx + d[0] * o, py = cy + d[1] * o;
            path.reset();
            path.moveTo(px + d[0] * a * 0.6f, py + d[1] * a * 0.6f);
            path.lineTo(px - d[0] * a * 0.4f + d[1] * a * 0.6f, py - d[1] * a * 0.4f + d[0] * a * 0.6f);
            path.lineTo(px - d[0] * a * 0.4f - d[1] * a * 0.6f, py - d[1] * a * 0.4f - d[0] * a * 0.6f);
            path.close();
            c.drawPath(path, glyph);
        }
        glyph.setStyle(Paint.Style.STROKE);
    }

    /** Octagonal gate with a knob that follows the finger. */
    private void drawStick(Canvas c, Button b, int ink) {
        float cx = b.r.centerX(), cy = b.r.centerY(), s = b.r.width() / 2;
        path.reset();
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 8 + i * Math.PI / 4;
            float x = cx + (float) Math.cos(ang) * s, y = cy + (float) Math.sin(ang) * s;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
        float kx = Float.isNaN(b.knobX) ? cx : b.knobX, ky = Float.isNaN(b.knobX) ? cy : b.knobY;
        Paint knob = b.mask != 0 ? lit : fill;
        c.drawCircle(kx, ky, s * 0.52f, knob);
        c.drawCircle(kx, ky, s * 0.52f, stroke);
        glyph.setColor(ink);
        glyph.setStrokeWidth(2.2f * density);
        c.drawCircle(kx, ky, s * 0.36f, glyph);
    }

    /** PlayStation-style symbols as shapes (crisper than font glyphs). */
    private void drawSymbol(Canvas c, String sym, float cx, float cy, float r, int color) {
        glyph.setColor(color);
        glyph.setStrokeWidth(Math.max(2f * density, r * 0.28f));
        switch (sym) {
            case "cross":
                c.drawLine(cx - r, cy - r, cx + r, cy + r, glyph);
                c.drawLine(cx - r, cy + r, cx + r, cy - r, glyph);
                break;
            case "circle":
                c.drawCircle(cx, cy, r, glyph);
                break;
            case "square":
                c.drawRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, glyph);
                break;
            default: // triangle
                path.reset();
                path.moveTo(cx, cy - r);
                path.lineTo(cx + r * 1.05f, cy + r * 0.75f);
                path.lineTo(cx - r * 1.05f, cy + r * 0.75f);
                path.close();
                c.drawPath(path, glyph);
        }
    }

    private void drawLabel(Canvas c, Button b, String label, int color, float rel) {
        if (label == null || label.length() == 0) return;
        float size = Math.min(b.r.height() * rel, 22 * density);
        text.setTextSize(size);
        float tw = text.measureText(label), max = b.r.width() - 10 * density;
        if (tw > max && tw > 0) text.setTextSize(size * max / tw);
        text.setColor(color);
        c.drawText(label, b.r.centerX(), b.r.centerY() - (text.descent() + text.ascent()) / 2, text);
    }

    private static int withAlpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    static String keyLabel(int code) {
        switch (code) {
            case Keys.FIRE: return "OK";
            case Keys.SOFT_LEFT: return "tecla izquierda";
            case Keys.SOFT_RIGHT: return "tecla derecha";
            case Keys.UP: return "▲";
            case Keys.DOWN: return "▼";
            case Keys.LEFT: return "◀";
            case Keys.RIGHT: return "▶";
            case 0: return "nada";
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
                offsets = Settings.padOffsets(getContext(), land, GROUPS * 2);
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
            for (int i = 0; i < buttons.size(); i++) { // where the player dragged each piece
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
            add(0, top, w * 0.4f, top + rowH, Keys.SOFT_LEFT, "", SOFT_L, G_TOP, "select");
            add(w * 0.4f, top, w * 0.6f, top + rowH, 0, "Mando", TOGGLE, G_TOP, "toggle");
            add(w * 0.6f, top, w, top + rowH, Keys.SOFT_RIGHT, "", SOFT_R, G_TOP, "start");
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
            add(0, 0, lw * 0.7f, rowH, Keys.SOFT_LEFT, "", SOFT_L, G_TOP, "select");
            add(lw * 0.7f, 0, lw, rowH, 0, "Mando", TOGGLE, G_TOP, "toggle");
            add(rx, 0, w, rowH, Keys.SOFT_RIGHT, "", SOFT_R, G_TOP, "start");
            addDpad(new RectF(0, rowH + pad, lw, h - pad), full);
            RectF right = new RectF(rx, rowH + pad, w, h - pad);
            if (full) addGrid(right); else addOk(right);
        }
    }

    /**
     * Landscape: L / Select 123 / Start / R along the top, d-pad above the joystick on the left,
     * face buttons on the right. Portrait: the same pieces under the game image.
     */
    private void layoutGamepad(int w, int h, boolean land) {
        Config c = Host.cfg;
        float k = Math.max(50, Math.min(200, c.padScale)) / 100f;
        float m = 12 * density;
        float s; // stick size; the d-pad is a bit smaller, the button cluster about the same
        float shW, shH, pillW, pillH;
        float dpadCy, stickCy, faceCy, leftCx, rightCx, topY;
        if (land) {
            s = Math.min(h * 0.34f, w * 0.17f) * k;
            int side = (int) (s + 3 * m);
            if (c.center) fit(0, 0, w, h, false);
            else fit(side, 0, w - side, h, false);
            shW = s * 1.0f;
            shH = s * 0.3f;
            pillW = s * 0.62f;
            pillH = s * 0.26f;
            topY = m;
            leftCx = m + s / 2 + m * 0.5f;
            rightCx = w - leftCx;
            stickCy = h - m - s / 2;
            dpadCy = topY + shH + m + s * 0.45f;
            faceCy = h - m - s * 0.62f;
            addPill(m, topY, m + shW, topY + shH, "l", PILL, G_L);
            addPill(w - m - shW, topY, w - m, topY + shH, "r", PILL, G_R);
            float tx = m + shW + m;
            addPill(tx, topY, tx + pillW, topY + pillH, "select", SOFT_L, G_TOP);
            addPill(tx + pillW + m * 0.5f, topY, tx + pillW * 1.55f + m * 0.5f, topY + pillH, "toggle", TOGGLE, G_TOP);
            addPill(w - m - shW - m - pillW, topY, w - m - shW - m, topY + pillH, "start", SOFT_R, G_TOP);
        } else {
            fit(0, 0, w, (int) (h * 0.56f), true);
            float room = h - dst.bottom;
            s = Math.min(w * 0.3f, room * 0.36f) * k;
            shW = s * 0.9f;
            shH = s * 0.28f;
            pillW = s * 0.6f;
            pillH = s * 0.26f;
            topY = dst.bottom + m;
            leftCx = m + s * 0.62f;
            rightCx = w - m - s * 0.75f;
            dpadCy = topY + shH + m + s * 0.48f;
            stickCy = h - m - s / 2;
            faceCy = (dpadCy + stickCy) / 2;
            // one row: L | Select | 123 | Start | R
            float gap = m * 0.5f, avail = w - 2 * m - 4 * gap, x = m;
            float[] widths = {avail * 0.23f, avail * 0.2f, avail * 0.14f, avail * 0.2f, avail * 0.23f};
            String[] ids = {"l", "select", "toggle", "start", "r"};
            int[] kinds = {PILL, SOFT_L, TOGGLE, SOFT_R, PILL};
            int[] groups = {G_L, G_TOP, G_TOP, G_TOP, G_R};
            for (int i = 0; i < 5; i++) {
                addPill(x, topY, x + widths[i], topY + shH, ids[i], kinds[i], groups[i]);
                x += widths[i] + gap;
            }
        }
        float ds = s * 0.92f;
        if (!c.padHidden("dpad")) addDir("dpad", G_DPAD, leftCx, dpadCy, ds);
        if (!c.padHidden("stick")) addDir("stick", G_STICK, leftCx, stickCy, s);
        Skin sk = skin(c.padStyle);
        float d = s * 0.36f, r = s * 0.2f;
        String[] ids = {"a", "b", "x", "y"};
        for (int i = 0; i < 4; i++) {
            if (c.padHidden(ids[i])) continue;
            float x = rightCx + sk.facePos[i][0] * d, y = faceCy + sk.facePos[i][1] * d;
            buttons.add(new Button(x - r, y - r, x + r, y + r, c.padCode(ids[i]), "", FACE, G_FACE, ids[i]));
        }
    }

    private void addPill(float l, float t, float r, float b, String id, int kind, int group) {
        if (Host.cfg.padHidden(id)) return;
        String label = "toggle".equals(id) ? "123" : "";
        int code = kind == TOGGLE ? 0 : Host.cfg.padCode(id);
        buttons.add(new Button(l, t, r, b, code, label, kind, group, id));
    }

    private void addDir(String id, int group, float cx, float cy, float s) {
        buttons.add(new Button(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, 0, "", DIR, group, id));
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
        add(x + c, y, x + 2 * c, y + c, Keys.UP, "▲", NORMAL, G_DPAD, "up");
        add(x, y + c, x + c, y + 2 * c, Keys.LEFT, "◀", NORMAL, G_DPAD, "left");
        add(x + c, y + c, x + 2 * c, y + 2 * c, Keys.FIRE, "OK", NORMAL, G_DPAD, "ok");
        add(x + 2 * c, y + c, x + 3 * c, y + 2 * c, Keys.RIGHT, "▶", NORMAL, G_DPAD, "right");
        add(x + c, y + 2 * c, x + 2 * c, y + 3 * c, Keys.DOWN, "▼", NORMAL, G_DPAD, "down");
        if (diagonals) {
            add(x, y, x + c, y + c, Keys.NUM1, "1", SECONDARY, G_DPAD, "1");
            add(x + 2 * c, y, x + 3 * c, y + c, Keys.NUM3, "3", SECONDARY, G_DPAD, "3");
            add(x, y + 2 * c, x + c, y + 3 * c, Keys.NUM7, "7", SECONDARY, G_DPAD, "7");
            add(x + 2 * c, y + 2 * c, x + 3 * c, y + 3 * c, Keys.NUM9, "9", SECONDARY, G_DPAD, "9");
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
            add(l, t, l + cw, t + ch, codes[i], labels[i], NORMAL, G_FACE, labels[i]);
        }
    }

    private void addOk(RectF a) {
        float s = Math.min(a.width(), a.height()) * 0.55f;
        add(a.centerX() - s / 2, a.centerY() - s / 2, a.centerX() + s / 2, a.centerY() + s / 2, Keys.FIRE, "OK", NORMAL, G_FACE, "ok");
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

    /** Drag one piece; a tap outside every control ends editing and saves. */
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
            Button best = null;
            for (int i = 0; i < buttons.size(); i++) {
                Button b = buttons.get(i);
                RectF r = b.r;
                if (r.contains(x, y)) return b;
                if (b.kind == DIR || b.kind == FACE) { // a bit of slack around round controls
                    float sl = r.width() * 0.15f;
                    if (best == null && x >= r.left - sl && x <= r.right + sl && y >= r.top - sl && y <= r.bottom + sl) best = b;
                }
            }
            return best;
        }
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
            if (nb != null && nb != old && slides(old) && slides(nb)) { // slide between keys
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
                setDirections(b, 0);
                b.knobX = Float.NaN;
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
        float lim = Math.min(len, r * 0.5f) / Math.max(len, 1f);
        b.knobX = b.r.centerX() + dx * lim;
        b.knobY = b.r.centerY() + dy * lim;
        setDirections(b, mask);
        redraw();
    }

    /** Presses/releases the game keys mapped to this control's four directions ("du".. or "su"..). */
    private void setDirections(Button b, int mask) {
        String p = "stick".equals(b.id) ? "s" : "d";
        String[] dirs = {"u", "d", "l", "r"};
        int changed = mask ^ b.mask;
        if (changed == 0) return;
        for (int i = 0; i < 4; i++) {
            if ((changed & (1 << i)) == 0) continue;
            int code = Host.cfg.padCode(p + dirs[i]);
            if (code != 0) Host.key((mask & (1 << i)) != 0 ? 0 : 1, code);
        }
        if ((mask & ~b.mask) != 0 && Host.cfg.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        b.mask = mask;
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
        if (b.code != 0) Host.key(0, b.code);
        redraw();
    }

    private void release(Button b) {
        b.pressed = false;
        if (b.kind != TOGGLE && b.code != 0) Host.key(1, b.code);
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
