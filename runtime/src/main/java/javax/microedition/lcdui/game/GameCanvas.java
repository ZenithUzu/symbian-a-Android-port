package javax.microedition.lcdui.game;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import j2a.core.Host;

public abstract class GameCanvas extends Canvas {
    public static final int UP_PRESSED = 1 << Canvas.UP;
    public static final int DOWN_PRESSED = 1 << Canvas.DOWN;
    public static final int LEFT_PRESSED = 1 << Canvas.LEFT;
    public static final int RIGHT_PRESSED = 1 << Canvas.RIGHT;
    public static final int FIRE_PRESSED = 1 << Canvas.FIRE;
    public static final int GAME_A_PRESSED = 1 << Canvas.GAME_A;
    public static final int GAME_B_PRESSED = 1 << Canvas.GAME_B;
    public static final int GAME_C_PRESSED = 1 << Canvas.GAME_C;
    public static final int GAME_D_PRESSED = 1 << Canvas.GAME_D;

    private final Image buffer;

    protected GameCanvas(boolean suppressKeyEvents) {
        buffer = Image.j2aCreateScreen(Host.cfg.screenWidth, Host.cfg.screenHeight);
        j2aSetSuppress(suppressKeyEvents);
    }

    protected Graphics getGraphics() {
        Graphics g = buffer.getGraphics();
        g.j2aTrack();
        return g;
    }

    public int getKeyStates() {
        int s = j2aKeyStates();
        return s;
    }

    @Override
    public void paint(Graphics g) {
        g.drawImage(buffer, 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    public void flushGraphics(int x, int y, int width, int height) {
        flushGraphics();
    }

    public void flushGraphics() {
        if (isShown()) Host.present(buffer.j2aBitmap());
    }
}
