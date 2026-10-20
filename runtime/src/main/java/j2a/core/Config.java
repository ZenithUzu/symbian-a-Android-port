package j2a.core;

import java.util.Properties;

/**
 * Port configuration, read from assets/j2a/port.properties.
 * Every value can be changed by hand after the project is generated.
 */
public final class Config {
    /** Virtual J2ME screen size (what Canvas.getWidth()/getHeight() return). */
    public int screenWidth = 240;
    public int screenHeight = 320;
    /** fit | integer | stretch */
    public String scaleMode = "fit";
    /** Bilinear filtering when scaling the screen up. */
    public boolean filter = false;
    /** full | minimal | none */
    public String keypad = "full";
    /** 0..255 */
    public int keypadAlpha = 150;
    public boolean haptics = true;
    public boolean showFps = false;
    /** 0 = unlimited. Old games often run too fast on modern CPUs: 30 is a good value. */
    public int fpsLimit = 0;
    public boolean sound = true;
    public boolean vibration = true;
    public boolean textAntialias = true;
    /** Font pixel sizes (0 = automatic, derived from the screen size). */
    public int fontSmall = 0, fontMedium = 0, fontLarge = 0;
    /** Value of System.getProperty("microedition.platform"). */
    public String platform = "Nokia6300/07.21";
    public String locale = "es-MX";
    /** softkey: BACK = right softkey | menu: BACK opens the port menu */
    public String backAction = "softkey";
    public String midletClass;
    /** Key codes sent for the soft keys (Nokia/SE -6/-7, Motorola -21/-22, Siemens -1/-4). */
    public int softLeft = Keys.SOFT_LEFT, softRight = Keys.SOFT_RIGHT;
    /** "" = as in the manifest | auto | portrait | landscape (changed from the port menu). */
    public String orientation = "";

    static Config load(Properties p) {
        Config c = new Config();
        c.screenWidth = i(p, "screen.width", c.screenWidth);
        c.screenHeight = i(p, "screen.height", c.screenHeight);
        c.scaleMode = s(p, "scale.mode", c.scaleMode);
        c.filter = b(p, "scale.filter", c.filter);
        c.keypad = s(p, "keypad", c.keypad);
        c.keypadAlpha = i(p, "keypad.alpha", c.keypadAlpha);
        c.haptics = b(p, "keypad.haptics", c.haptics);
        c.showFps = b(p, "show.fps", c.showFps);
        c.fpsLimit = i(p, "fps.limit", c.fpsLimit);
        c.sound = b(p, "sound", c.sound);
        c.vibration = b(p, "vibration", c.vibration);
        c.textAntialias = b(p, "text.antialias", c.textAntialias);
        c.fontSmall = i(p, "font.small", 0);
        c.fontMedium = i(p, "font.medium", 0);
        c.fontLarge = i(p, "font.large", 0);
        c.platform = s(p, "microedition.platform", c.platform);
        c.locale = s(p, "microedition.locale", c.locale);
        c.backAction = s(p, "back.action", c.backAction);
        c.midletClass = s(p, "midlet.class", null);
        c.softLeft = i(p, "softkey.left", c.softLeft);
        c.softRight = i(p, "softkey.right", c.softRight);
        return c;
    }

    /** Font size in px; 0 in the config = derived from the (possibly user-changed) screen size. */
    public int fontPx(boolean small, boolean large) {
        int set = small ? fontSmall : large ? fontLarge : fontMedium;
        if (set > 0) return set;
        int base = Math.min(screenWidth, screenHeight);
        return small ? Math.max(8, Math.round(base * 0.050f))
                : large ? Math.max(12, Math.round(base * 0.075f)) : Math.max(10, Math.round(base * 0.060f));
    }

    private static String s(Properties p, String k, String d) {
        String v = p.getProperty(k);
        return v == null || v.trim().length() == 0 ? d : v.trim();
    }

    private static int i(Properties p, String k, int d) {
        try { return Integer.parseInt(s(p, k, String.valueOf(d))); } catch (NumberFormatException e) { return d; }
    }

    private static boolean b(Properties p, String k, boolean d) {
        String v = s(p, k, null);
        return v == null ? d : (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }
}
