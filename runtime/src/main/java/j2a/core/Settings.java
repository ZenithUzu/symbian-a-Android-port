package j2a.core;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the options the player changes in the port menu. */
final class Settings {
    private static final String PREFS = "j2a_port";

    private Settings() {}

    static void apply(Context ctx, Config c) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        c.keypad = p.getString("keypad", c.keypad);
        c.scaleMode = p.getString("scale.mode", c.scaleMode);
        c.shader = p.getString("shader", c.shader);
        c.effectStrength = Math.max(0, Math.min(100, p.getInt("effect.strength", c.effectStrength)));
        c.fpsMode = p.getString("fps.mode", c.fpsMode);
        c.keypadAlpha = p.getInt("keypad.alpha", c.keypadAlpha);
        c.padScale = p.getInt("pad.scale", c.padScale);
        c.padMap = p.getString("pad.map", c.padMap);
        c.padHide = p.getString("pad.hide", c.padHide);
        c.center = p.getBoolean("center", c.center);
        c.haptics = p.getBoolean("keypad.haptics", c.haptics);
        c.showFps = p.getBoolean("show.fps", c.showFps);
        c.frameBlend = p.getBoolean("frame.blend", c.frameBlend);
        c.fpsLimit = p.getInt("fps.limit", c.fpsLimit);
        c.sound = p.getBoolean("sound", c.sound);
        c.vibration = p.getBoolean("vibration", c.vibration);
        c.screenWidth = p.getInt("screen.width", c.screenWidth);
        c.screenHeight = p.getInt("screen.height", c.screenHeight);
        c.expandedViewport = p.getBoolean("viewport.expanded", false);
        c.orientation = p.getString("orientation", c.orientation);
        c.autoFit = p.getBoolean("auto.fit", c.autoFit);
        c.rotate = p.getInt("rotate.choice", c.rotate);
    }

    /** Area detected by AutoFit for a given virtual screen ("" = not decided, "none" = full screen). */
    static String fitFor(Context ctx, int w, int h) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fit." + w + "x" + h, "");
    }

    /** Rotation AutoFit detected for this virtual screen ("" = not decided). */
    static String rotationFor(Context ctx, int w, int h) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rot." + w + "x" + h, "");
    }

    static void saveRotation(Context ctx, int w, int h, int r) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rot." + w + "x" + h, String.valueOf(r)).apply();
    }

    static void saveFit(Context ctx, int w, int h, String value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fit." + w + "x" + h, value).apply();
    }

    static void save(Context ctx, Config c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("keypad", c.keypad)
                .putString("scale.mode", c.scaleMode)
                .putString("shader", c.shader)
                .putInt("effect.strength", c.effectStrength)
                .putBoolean("viewport.expanded", c.expandedViewport)
                .putString("fps.mode", c.fpsMode)
                .putInt("keypad.alpha", c.keypadAlpha)
                .putInt("pad.scale", c.padScale)
                .putString("pad.map", c.padMap)
                .putString("pad.hide", c.padHide)
                .putBoolean("center", c.center)
                .putBoolean("keypad.haptics", c.haptics)
                .putBoolean("show.fps", c.showFps)
                .putBoolean("frame.blend", c.frameBlend)
                .putInt("fps.limit", c.fpsLimit)
                .putBoolean("sound", c.sound)
                .putBoolean("vibration", c.vibration)
                .putString("orientation", c.orientation)
                .putBoolean("auto.fit", c.autoFit)
                .putInt("rotate.choice", c.rotate)
                .apply();
    }

    /** True once the player answered the "show keypad?" question (saved with the keypad choice). */
    static boolean keypadAnswered(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("keypad");
    }

    /** Forget everything the player changed (port.properties values apply on next start). */
    static void reset(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Where the player dragged each control group, per orientation: "dx,dy,dx,dy,dx,dy" (view fractions). */
    static float[] padOffsets(Context ctx, boolean landscape, int n) {
        float[] o = new float[n];
        String[] v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(landscape ? "pad.pos.l" : "pad.pos.p", "").split(",");
        for (int i = 0; i < n && i < v.length; i++) {
            try { o[i] = Float.parseFloat(v[i]); } catch (NumberFormatException ignored) {}
        }
        return o;
    }

    static void savePadOffsets(Context ctx, boolean landscape, float[] o) {
        StringBuilder b = new StringBuilder();
        for (float f : o) b.append(b.length() > 0 ? "," : "").append(f);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(landscape ? "pad.pos.l" : "pad.pos.p", b.toString()).apply();
    }

    /** Only written when the player picks a resolution, so port.properties stays the default. */
    static void saveScreen(Context ctx, int w, int h) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("screen.width", w).putInt("screen.height", h).apply();
    }

    static void rememberViewport(Context ctx, Config c) {
        if (c.expandedViewport) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("viewport.before.orientation", c.orientation)
                .putString("viewport.before.scale", c.scaleMode)
                .putInt("viewport.before.rotate", c.rotate)
                .putBoolean("viewport.before.fit", c.autoFit).apply();
    }

    static void restoreViewport(Context ctx, Config c) {
        if (!c.expandedViewport) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        c.orientation = p.getString("viewport.before.orientation", "auto");
        c.scaleMode = p.getString("viewport.before.scale", "fit");
        c.rotate = p.getInt("viewport.before.rotate", -1);
        c.autoFit = p.getBoolean("viewport.before.fit", true);
        c.expandedViewport = false;
    }
}
