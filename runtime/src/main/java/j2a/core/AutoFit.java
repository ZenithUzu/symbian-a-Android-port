package j2a.core;

import android.graphics.Rect;

/**
 * Automatic resolution fit. Many J2ME games were built for one fixed screen (e.g. 176x208)
 * and only draw that area even when the screen is bigger. This watches what the game really
 * draws on the screen buffer and, once it clearly matches a classic phone resolution, shows
 * only that area (scaled to fill the display) and turns the device to its orientation.
 *
 * It never changes what the game sees (getWidth/getHeight stay the same), so it can't break
 * a game: at worst the full screen is shown, as before. The result is remembered per game.
 */
public final class AutoFit {
    private static final int[][] KNOWN = {
            {128, 128}, {128, 160}, {132, 176}, {176, 208}, {176, 220}, {208, 208}, {240, 320},
            {320, 240}, {240, 400}, {400, 240}, {352, 416}, {360, 640}, {640, 360}, {320, 480}, {480, 320}};
    private static final int TOLERANCE = 4;
    private static final long WARMUP_NS = 5000000000L, GIVE_UP_NS = 120000000000L;

    /** Read by Graphics on every draw: keep it a plain field. */
    public static volatile boolean tracking;

    private static final Object lock = new Object();
    private static int screenW, screenH;
    private static int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = -1, b = -1;
    private static int frames;
    private static long firstNs;
    private static volatile Rect fit; // null = whole screen

    private AutoFit() {}

    static void start() {
        screenW = Host.cfg.screenWidth;
        screenH = Host.cfg.screenHeight;
        if (!Host.cfg.autoFit) return;
        String saved = Settings.fitFor(Host.activity, screenW, screenH);
        if ("none".equals(saved)) return; // this game uses the whole screen
        int[] wh = parse(saved);
        if (wh != null && wh[0] <= screenW && wh[1] <= screenH) {
            fit = new Rect(0, 0, wh[0], wh[1]);
            orient();
        }
        tracking = true;
    }

    /** Player turned it off: show the whole screen again (turning it on applies on next start). */
    static void disable() {
        tracking = false;
        fit = null;
        Host.relayout();
    }

    /** Visible part of the game screen, in game pixels. */
    public static Rect area() {
        Rect f = fit;
        return f != null ? new Rect(f) : new Rect(0, 0, Host.cfg.screenWidth, Host.cfg.screenHeight);
    }

    /** Called by Graphics for every primitive drawn on a screen-sized buffer (device coords). */
    public static void drawn(int dl, int dt, int dr, int db, int targetW, int targetH) {
        if (targetW != screenW || targetH != screenH) return;
        if (dl <= 0 && dt <= 0 && dr >= screenW && db >= screenH) return; // clears say nothing
        synchronized (lock) {
            if (dl < l) l = dl;
            if (dt < t) t = dt;
            if (dr > r) r = dr;
            if (db > b) b = db;
        }
    }

    /** Called once per game frame shown. */
    static void frameShown() {
        if (!tracking) return;
        long now = System.nanoTime();
        if (firstNs == 0) firstNs = now;
        frames++;
        if (now - firstNs > GIVE_UP_NS) {
            tracking = false; // undecided after 2 min: leave the full screen, try again next launch
            return;
        }
        if (frames < 120 || now - firstNs < WARMUP_NS) return;
        int cl, ct, cr, cb;
        synchronized (lock) {
            cl = l;
            ct = t;
            cr = r;
            cb = b;
        }
        if (cr < 0) return; // only full-screen draws so far
        Rect current = fit;
        if (current != null && cr <= current.right && cb <= current.bottom) return; // still inside
        int[] match = snap(cl, ct, cr, cb);
        if (match == null) {
            if (!fitsAnySmaller(cr, cb)) decide(null); // draws beyond every smaller size: full screen
            return; // otherwise keep watching (e.g. only a small logo so far)
        }
        decide(new Rect(0, 0, match[0], match[1]));
    }

    private static void decide(Rect f) {
        fit = f;
        if (f == null) tracking = false;
        Settings.saveFit(Host.activity, screenW, screenH, f == null ? "none" : f.width() + "x" + f.height());
        orient();
        Host.relayout();
    }

    /** Rotates to the fitted area's orientation unless the player chose one. */
    private static void orient() {
        if (Host.cfg.orientation.length() > 0 || Host.activity == null) return;
        Rect a = area();
        final String o = a.width() > a.height() ? "landscape" : "portrait";
        Host.runOnUi(new Runnable() {
            public void run() { Host.applyOrientation(Host.activity, o); }
        });
    }

    /** Known resolution smaller than the screen whose size matches the drawn area. */
    static int[] snap(int dl, int dt, int dr, int db) {
        if (dl > TOLERANCE || dt > TOLERANCE) return null; // fixed-size games draw from (0,0)
        for (int[] k : KNOWN) {
            if (k[0] > screenW || k[1] > screenH || (k[0] == screenW && k[1] == screenH)) continue;
            if (dr <= k[0] && dr >= k[0] - TOLERANCE && db <= k[1] && db >= k[1] - TOLERANCE) return k;
        }
        return null;
    }

    private static boolean fitsAnySmaller(int dr, int db) {
        for (int[] k : KNOWN) {
            if (k[0] > screenW || k[1] > screenH || (k[0] == screenW && k[1] == screenH)) continue;
            if (dr <= k[0] && db <= k[1]) return true;
        }
        return false;
    }

    private static int[] parse(String s) {
        int x = s.indexOf('x');
        if (x <= 0) return null;
        try {
            return new int[]{Integer.parseInt(s.substring(0, x)), Integer.parseInt(s.substring(x + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Self-check of the matching rules (run from a unit test or a debugger). */
    static void selfCheck() {
        screenW = 240;
        screenH = 320;
        if (snap(0, 0, 176, 208) == null || snap(0, 0, 176, 208)[0] != 176) throw new AssertionError("176x208");
        if (snap(30, 40, 176, 208) != null) throw new AssertionError("centered content must not fit");
        if (snap(0, 0, 230, 300) != null) throw new AssertionError("240x320 game with margins must not fit");
        if (snap(0, 0, 240, 320) != null) throw new AssertionError("full screen is not a smaller size");
        if (!fitsAnySmaller(100, 50) || fitsAnySmaller(239, 319)) throw new AssertionError("fitsAnySmaller");
    }
}
