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
    /** Resolution from port.properties (the game's own), before any player change. */
    public int baseWidth = 240, baseHeight = 320;
    public String screenProfiles = "";
    public boolean expandedViewport = false;
    /** fit | integer | stretch */
    public String scaleMode = "fit";
    /** Kept for old port.properties: scale.filter=true means shader=smooth. */
    public boolean filter = false;
    /** Post-processing shader id (see Shaders) or "file:<section>" from assets/shaders/*.ini. */
    public String shader = "nearest";
    /** Strength of built-in effects; external RetroArch/PPSSPP shaders keep their own parameters. */
    public int effectStrength = 100;
    /** Rotate the game image 0/90/270 degrees (games that draw sideways); -1 = detect (AutoFit). */
    public int rotate = -1;
    /** Shrink the shown area to what a fixed-resolution game really draws (see AutoFit). */
    public boolean autoFit = true;
    /** vsync | dynamic | unlimited (see GlScreen). */
    public String fpsMode = "vsync";
    /** gamepad | full (phone keypad) | minimal | none */
    public String keypad = "full";
    /** On-screen gamepad size in %. */
    public int padScale = 100;
    /**
     * Game key each control sends (-5 = OK, 53 = key 5...; 0 = nothing). On-screen ids: a b x y l r select
     * start, d-pad du dd dl dr, joystick su sd sl sr. A physical gamepad has its own ids, the same with a
     * "p" in front (pa, pdu, psu...) plus pl2 pr2 pl3 pr3 and the right stick pru prd prl prr, so both
     * are mapped independently. Ids not in the map use the defaults (a physical one: its on-screen twin's).
     */
    public String padMap = DEFAULT_PAD_MAP;
    public static final String DEFAULT_PAD_MAP = "a=-5,b=55,x=57,y=48,l=42,r=35,select=-6,start=-7,"
            + "du=-1,dd=-2,dl=-3,dr=-4,su=-1,sd=-2,sl=-3,sr=-4,pl2=49,pr2=51,pl3=53,pr3=48,pru=50,prd=56,prl=52,prr=54";
    public String padHide = "";
    /** Game image centered on the whole screen; the controls float over the side margins. */
    public boolean center = true;
    /** Ask the player once whether to show the keypad (touch games with their own controls). */
    public boolean keypadAsk = false;
    /** 0..255 */
    public int keypadAlpha = 150;
    public boolean haptics = true;
    public boolean showFps = false;
    /** Shows the average of the last two frames: games that fake transparency by flickering. */
    public boolean frameBlend = false;
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
    /** menu: Android back gesture/button opens the port menu | softkey: it is the game's right soft key */
    public String backAction = "menu";
    public String midletClass;
    /** Key codes sent for the soft keys (Nokia/SE -6/-7, Motorola -21/-22, Siemens -1/-4). */
    public int softLeft = Keys.SOFT_LEFT, softRight = Keys.SOFT_RIGHT;
    /** "" = as in the manifest | auto | portrait | landscape (changed from the port menu). */
    public String orientation = "";

    static Config load(Properties p) {
        Config c = new Config();
        c.screenWidth = i(p, "screen.width", c.screenWidth);
        c.screenHeight = i(p, "screen.height", c.screenHeight);
        c.baseWidth = c.screenWidth;
        c.baseHeight = c.screenHeight;
        c.screenProfiles = s(p, "screen.profiles", "");
        c.scaleMode = s(p, "scale.mode", c.scaleMode);
        c.filter = b(p, "scale.filter", c.filter);
        c.shader = s(p, "shader", c.filter ? "smooth" : "nearest");
        c.effectStrength = Math.max(0, Math.min(100, i(p, "effect.strength", 100)));
        c.fpsMode = s(p, "fps.mode", c.fpsMode);
        c.autoFit = b(p, "auto.fit", c.autoFit);
        c.rotate = i(p, "rotate", c.rotate);
        c.keypad = s(p, "keypad", c.keypad);
        c.keypadAlpha = i(p, "keypad.alpha", c.keypadAlpha);
        c.padScale = i(p, "pad.scale", c.padScale);
        c.padMap = s(p, "pad.map", c.padMap);
        c.padHide = s(p, "pad.hide", c.padHide);
        c.center = b(p, "center", c.center);
        c.keypadAsk = b(p, "keypad.ask", c.keypadAsk);
        c.haptics = b(p, "keypad.haptics", c.haptics);
        c.showFps = b(p, "show.fps", c.showFps);
        c.frameBlend = b(p, "frame.blend", c.frameBlend);
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

    /** Game key a control ("a", "du", "l2"...) sends. */
    public int padCode(String id) {
        Integer v = lookup(padMap, id);
        if (v == null) v = lookup(DEFAULT_PAD_MAP, id);
        if (v == null && id.startsWith("p")) v = lookup(DEFAULT_PAD_MAP, id.substring(1)); // auto-map: as on screen
        return v == null ? 0 : v;
    }

    private static Integer lookup(String map, String id) {
        for (String kv : map.split(",")) {
            String[] p = kv.split("=");
            if (p.length == 2 && p[0].trim().equals(id)) {
                try { return Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    public void setPadCode(String id, int code) {
        StringBuilder b = new StringBuilder();
        boolean found = false;
        for (String kv : padMap.split(",")) {
            if (kv.trim().length() == 0) continue;
            boolean me = kv.split("=")[0].trim().equals(id);
            found |= me;
            b.append(b.length() > 0 ? "," : "").append(me ? id + "=" + code : kv.trim());
        }
        if (!found) b.append(b.length() > 0 ? "," : "").append(id).append('=').append(code);
        padMap = b.toString();
    }

    public boolean padHidden(String id) {
        return ("," + padHide.replace(" ", "") + ",").contains("," + id + ",");
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
