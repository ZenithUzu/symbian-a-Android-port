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
        c.fpsMode = p.getString("fps.mode", c.fpsMode);
        c.keypadAlpha = p.getInt("keypad.alpha", c.keypadAlpha);
        c.haptics = p.getBoolean("keypad.haptics", c.haptics);
        c.showFps = p.getBoolean("show.fps", c.showFps);
        c.fpsLimit = p.getInt("fps.limit", c.fpsLimit);
        c.sound = p.getBoolean("sound", c.sound);
        c.screenWidth = p.getInt("screen.width", c.screenWidth);
        c.screenHeight = p.getInt("screen.height", c.screenHeight);
        c.orientation = p.getString("orientation", c.orientation);
        c.autoFit = p.getBoolean("auto.fit", c.autoFit);
    }

    /** Area detected by AutoFit for a given virtual screen ("" = not decided, "none" = full screen). */
    static String fitFor(Context ctx, int w, int h) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fit." + w + "x" + h, "");
    }

    static void saveFit(Context ctx, int w, int h, String value) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("fit." + w + "x" + h, value).apply();
    }

    static void save(Context ctx, Config c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("keypad", c.keypad)
                .putString("scale.mode", c.scaleMode)
                .putString("shader", c.shader)
                .putString("fps.mode", c.fpsMode)
                .putInt("keypad.alpha", c.keypadAlpha)
                .putBoolean("keypad.haptics", c.haptics)
                .putBoolean("show.fps", c.showFps)
                .putInt("fps.limit", c.fpsLimit)
                .putBoolean("sound", c.sound)
                .putString("orientation", c.orientation)
                .putBoolean("auto.fit", c.autoFit)
                .apply();
    }

    /** Forget everything the player changed (port.properties values apply on next start). */
    static void reset(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Only written when the player picks a resolution, so port.properties stays the default. */
    static void saveScreen(Context ctx, int w, int h) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("screen.width", w).putInt("screen.height", h).apply();
    }
}
