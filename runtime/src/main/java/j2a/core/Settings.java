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
        c.filter = p.getBoolean("scale.filter", c.filter);
        c.showFps = p.getBoolean("show.fps", c.showFps);
        c.fpsLimit = p.getInt("fps.limit", c.fpsLimit);
        c.sound = p.getBoolean("sound", c.sound);
        c.screenWidth = p.getInt("screen.width", c.screenWidth);
        c.screenHeight = p.getInt("screen.height", c.screenHeight);
        c.orientation = p.getString("orientation", c.orientation);
    }

    static void save(Context ctx, Config c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("keypad", c.keypad)
                .putString("scale.mode", c.scaleMode)
                .putBoolean("scale.filter", c.filter)
                .putBoolean("show.fps", c.showFps)
                .putInt("fps.limit", c.fpsLimit)
                .putBoolean("sound", c.sound)
                .putString("orientation", c.orientation)
                .apply();
    }

    /** Only written when the player picks a resolution, so port.properties stays the default. */
    static void saveScreen(Context ctx, int w, int h) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("screen.width", w).putInt("screen.height", h).apply();
    }
}
