package javax.microedition.lcdui;

import javax.microedition.midlet.MIDlet;

import j2a.core.Host;

public class Display {
    public static final int LIST_ELEMENT = 1;
    public static final int CHOICE_GROUP_ELEMENT = 2;
    public static final int ALERT = 3;
    public static final int COLOR_BACKGROUND = 0;
    public static final int COLOR_FOREGROUND = 1;
    public static final int COLOR_HIGHLIGHTED_BACKGROUND = 2;
    public static final int COLOR_HIGHLIGHTED_FOREGROUND = 3;
    public static final int COLOR_BORDER = 4;
    public static final int COLOR_HIGHLIGHTED_BORDER = 5;

    private static Display instance;

    private Display() {}

    public static synchronized Display getDisplay(MIDlet m) {
        if (instance == null) instance = new Display();
        return instance;
    }

    public Displayable getCurrent() { return Host.getCurrent(); }

    public void setCurrent(Displayable next) {
        if (next instanceof Alert) {
            setCurrent((Alert) next, Host.getCurrent());
            return;
        }
        Host.setCurrent(next);
    }

    public void setCurrent(Alert alert, Displayable nextDisplayable) {
        if (alert == null) throw new NullPointerException();
        Displayable next = nextDisplayable;
        if (next == null || next instanceof Alert) next = Host.getCurrent();
        alert.next = next;
        Host.setCurrent(alert);
    }

    public void setCurrentItem(Item item) {
        if (item.owner != null) Host.setCurrent(item.owner);
    }

    public void callSerially(Runnable r) {
        Host.throttle(); // a callSerially(this) game loop gets the FPS limit here
        Host.events.post(r);
    }

    public boolean flashBacklight(int duration) { return true; }

    public boolean vibrate(int duration) { return Host.vibrate(duration); }

    public boolean isColor() { return true; }

    public int numColors() { return 1 << 24; }

    public int numAlphaLevels() { return 256; }

    public int getColor(int colorSpecifier) {
        switch (colorSpecifier) {
            case COLOR_BACKGROUND: return 0x202020;
            case COLOR_FOREGROUND: return 0xFFFFFF;
            case COLOR_HIGHLIGHTED_BACKGROUND: return 0x3060C0;
            case COLOR_HIGHLIGHTED_FOREGROUND: return 0xFFFFFF;
            case COLOR_BORDER: return 0x808080;
            case COLOR_HIGHLIGHTED_BORDER: return 0xFFFFFF;
            default: throw new IllegalArgumentException();
        }
    }

    public int getBorderStyle(boolean highlighted) { return highlighted ? Graphics.SOLID : Graphics.DOTTED; }

    public int getBestImageWidth(int imageType) { return Host.cfg.screenWidth; }

    public int getBestImageHeight(int imageType) { return Host.cfg.screenHeight; }
}
