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
    /** Image area drawn upright / rotated 90° clockwise / rotated 270° (sideways games). */
    private static long uprightArea, cwArea, ccwArea;
    private static volatile int detectedRotation;
    private static boolean rotationDecided;

    private AutoFit() {}

    static int detectedRotation() {
        return detectedRotation;
    }

    static void start() {
        screenW = Host.cfg.screenWidth;
        screenH = Host.cfg.screenHeight;
        String rot = Settings.rotationFor(Host.activity, screenW, screenH);
        if (rot.length() > 0) {
            detectedRotation = Integer.parseInt(rot);
            rotationDecided = true;
        }
        if (!Host.cfg.autoFit) return;
        String saved = Settings.fitFor(Host.activity, screenW, screenH);
        tracking = true;
        if ("none".equals(saved)) { // whole screen (still watching rotation if undecided)
            fitDone = true;
            tracking = !rotationDecided;
            return;
        }
        int[] wh = parse(saved);
        if (wh != null && wh[0] + wh[2] <= screenW && wh[1] + wh[3] <= screenH) {
            fit = new Rect(wh[0], wh[1], wh[0] + wh[2], wh[1] + wh[3]);
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

    /**
     * An image drawn on the screen. turn: 0 upright, 90 = MIDP TRANS_ROT90 (clockwise),
     * 270 = TRANS_ROT270. Games that play sideways on a portrait phone draw (almost) every
     * image rotated, often as one big rotated buffer.
     */
    public static void imageDrawn(int turn, int area) {
        if (rotationDecided) return;
        synchronized (lock) {
            if (turn == 90) cwArea += area;
            else if (turn == 270) ccwArea += area;
            else uprightArea += area;
        }
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
        if (now - firstNs > GIVE_UP_NS || (fitDone && rotationDecided)) {
            tracking = false; // undecided after 2 min: leave the full screen, try again next launch
            return;
        }
        if (frames < 120 || now - firstNs < WARMUP_NS) return;
        if (!rotationDecided) decideRotation();
        if (fitDone) return;
        int cl, ct, cr, cb;
        synchronized (lock) {
            cl = l;
            ct = t;
            cr = r;
            cb = b;
        }
        if (cr < 0) return; // only full-screen draws so far
        Rect current = fit;
        if (current != null && cl >= current.left && ct >= current.top && cr <= current.right && cb <= current.bottom) {
            return; // still inside the fitted area
        }
        int[] match = snap(cl, ct, cr, cb);
        if (match == null) {
            if (!fitsAnySmaller(cr - cl, cb - ct)) decide(null); // bigger than any smaller size: full screen
            return; // otherwise keep watching (e.g. only a small logo so far)
        }
        decide(new Rect(match[0], match[1], match[0] + match[2], match[1] + match[3]));
    }

    private static boolean fitDone;

    private static void decideRotation() {
        long up, cw, ccw;
        synchronized (lock) {
            up = uprightArea;
            cw = cwArea;
            ccw = ccwArea;
        }
        long screens = (long) screenW * screenH;
        int r = 0;
        // rotated images must clearly dominate and add up to at least ~20 full screens
        if (cw > 2 * (up + ccw) && cw > 20 * screens) r = 270;       // drawn clockwise -> turn back
        else if (ccw > 2 * (up + cw) && ccw > 20 * screens) r = 90;
        else if (up + cw + ccw < 20 * screens) return;               // not enough evidence yet
        rotationDecided = true;
        detectedRotation = r;
        Settings.saveRotation(Host.activity, screenW, screenH, r);
        if (r != 0 && Host.cfg.rotate < 0) {
            Host.runOnUi(new Runnable() {
                public void run() { Host.applyOrientation(Host.activity); }
            });
            Host.relayout();
        }
        if (fitDone) tracking = false;
    }

    private static void decide(Rect f) {
        fit = f;
        if (f == null) {
            fitDone = true;
            if (rotationDecided) tracking = false;
        }
        Settings.saveFit(Host.activity, screenW, screenH,
                f == null ? "none" : f.left + "," + f.top + "," + f.width() + "x" + f.height());
        orient();
        Host.relayout();
    }

    /** Rotates to the fitted area's orientation unless the player chose one. */
    private static void orient() {
        if (Host.cfg.orientation.length() > 0 || Host.rotation() != 0 || Host.activity == null) return;
        Rect a = area();
        final String o = a.width() > a.height() ? "landscape" : "portrait";
        Host.runOnUi(new Runnable() {
            public void run() { Host.applyOrientation(Host.activity, o); }
        });
    }

    /**
     * Known resolution smaller than the screen matching the drawn area: {left, top, w, h}.
     * Content at (0,0) is how fixed-size games draw; centered content (games that center
     * themselves, or the "widescreen" resolution) must be at least 176 px to rule out logos.
     */
    static int[] snap(int dl, int dt, int dr, int db) {
        boolean anchored = dl <= TOLERANCE && dt <= TOLERANCE;
        int left = anchored ? 0 : dl, top = anchored ? 0 : dt;
        int w = dr - left, h = db - top;
        for (int[] k : KNOWN) {
            if (k[0] > screenW || k[1] > screenH || (k[0] == screenW && k[1] == screenH)) continue;
            if (!anchored && (k[0] < 176 || k[1] < 176)) continue;
            if (left + k[0] > screenW || top + k[1] > screenH) continue;
            if (w <= k[0] && w >= k[0] - TOLERANCE && h <= k[1] && h >= k[1] - TOLERANCE) {
                return new int[]{left, top, k[0], k[1]};
            }
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

    /** "left,top,WxH" -> {left, top, w, h}; also accepts the older "WxH". */
    private static int[] parse(String s) {
        try {
            int l = 0, t = 0;
            String[] parts = s.split(",");
            if (parts.length == 3) {
                l = Integer.parseInt(parts[0]);
                t = Integer.parseInt(parts[1]);
                s = parts[2];
            }
            int x = s.indexOf('x');
            if (x <= 0) return null;
            return new int[]{l, t, Integer.parseInt(s.substring(0, x)), Integer.parseInt(s.substring(x + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Self-check of the matching rules (run from a unit test or a debugger). */
    static void selfCheck() {
        screenW = 240;
        screenH = 320;
        if (snap(0, 0, 176, 208) == null || snap(0, 0, 176, 208)[2] != 176) throw new AssertionError("176x208");
        if (snap(30, 40, 176, 208) != null) throw new AssertionError("centered content must not fit");
        if (snap(0, 0, 230, 300) != null) throw new AssertionError("240x320 game with margins must not fit");
        if (snap(0, 0, 240, 320) != null) throw new AssertionError("full screen is not a smaller size");
        if (!fitsAnySmaller(100, 50) || fitsAnySmaller(239, 319)) throw new AssertionError("fitsAnySmaller");
        if (snap(60, 100, 160, 150) != null) throw new AssertionError("small centered logo must not fit");
        screenW = 240;
        screenH = 533; // "widescreen" resolution, game still draws 240x320 in the middle
        int[] c = snap(0, 106, 240, 426);
        if (c == null || c[1] != 106 || c[3] != 320) throw new AssertionError("centered 240x320");
        int[] p = parse("0,106,240x320");
        if (p[1] != 106 || p[3] != 320 || parse("176x208")[2] != 176) throw new AssertionError("parse");
    }
}
