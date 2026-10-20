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
    }

    static void save(Context ctx, Config c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("keypad", c.keypad)
                .putString("scale.mode", c.scaleMode)
                .putBoolean("scale.filter", c.filter)
                .putBoolean("show.fps", c.showFps)
                .putInt("fps.limit", c.fpsLimit)
                .putBoolean("sound", c.sound)
                .apply();
    }
}
