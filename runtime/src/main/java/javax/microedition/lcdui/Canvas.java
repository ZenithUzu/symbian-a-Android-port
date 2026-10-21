package javax.microedition.lcdui;

import j2a.core.Host;
import j2a.core.Keys;

public abstract class Canvas extends Displayable {
    public static final int UP = 1;
    public static final int DOWN = 6;
    public static final int LEFT = 2;
    public static final int RIGHT = 5;
    public static final int FIRE = 8;
    public static final int GAME_A = 9;
    public static final int GAME_B = 10;
    public static final int GAME_C = 11;
    public static final int GAME_D = 12;
    public static final int KEY_NUM0 = 48;
    public static final int KEY_NUM1 = 49;
    public static final int KEY_NUM2 = 50;
    public static final int KEY_NUM3 = 51;
    public static final int KEY_NUM4 = 52;
    public static final int KEY_NUM5 = 53;
    public static final int KEY_NUM6 = 54;
    public static final int KEY_NUM7 = 55;
    public static final int KEY_NUM8 = 56;
    public static final int KEY_NUM9 = 57;
    public static final int KEY_STAR = 42;
    public static final int KEY_POUND = 35;

    private boolean fullScreen;
    /** GameCanvas key state bits (current / latched since last getKeyStates()). */
    volatile int keyStates;
    volatile int latchedKeyStates;
    boolean suppressGameKeys;

    protected Canvas() {}

    public int getGameAction(int keyCode) {
        switch (keyCode) {
            case Keys.UP: case KEY_NUM2: return UP;
            case Keys.DOWN: case KEY_NUM8: return DOWN;
            case Keys.LEFT: case KEY_NUM4: return LEFT;
            case Keys.RIGHT: case KEY_NUM6: return RIGHT;
            case Keys.FIRE: case KEY_NUM5: return FIRE;
            case KEY_NUM1: return GAME_A;
            case KEY_NUM3: return GAME_B;
            case KEY_NUM7: return GAME_C;
            case KEY_NUM9: return GAME_D;
            default: return 0;
        }
    }

    public int getKeyCode(int gameAction) {
        switch (gameAction) {
            case UP: return Keys.UP;
            case DOWN: return Keys.DOWN;
            case LEFT: return Keys.LEFT;
            case RIGHT: return Keys.RIGHT;
            case FIRE: return Keys.FIRE;
            case GAME_A: return KEY_NUM1;
            case GAME_B: return KEY_NUM3;
            case GAME_C: return KEY_NUM7;
            case GAME_D: return KEY_NUM9;
            default: throw new IllegalArgumentException();
        }
    }

    public String getKeyName(int keyCode) { return Keys.name(keyCode); }

    public boolean hasPointerEvents() { return true; }

    public boolean hasPointerMotionEvents() { return true; }

    public boolean hasRepeatEvents() { return true; }

    public boolean isDoubleBuffered() { return true; }

    protected void keyPressed(int keyCode) {}

    protected void keyRepeated(int keyCode) {}

    protected void keyReleased(int keyCode) {}

    protected void pointerPressed(int x, int y) {}

    protected void pointerReleased(int x, int y) {}

    protected void pointerDragged(int x, int y) {}

    protected abstract void paint(Graphics g);

    protected void showNotify() {}

    protected void hideNotify() {}

    @Override
    protected void sizeChanged(int w, int h) {}

    public final void repaint() {
        if (!shown) return;
        // game loops that only call repaint() get the FPS limit here (never key/touch handlers)
        if (!Host.events.isEventThread()) Host.throttle();
        Host.events.postRepaint(this);
    }

    public final void repaint(int x, int y, int width, int height) {
        repaint();
    }

    public final void serviceRepaints() {
        if (!shown) return;
        Host.events.serviceRepaints();
        if (!Host.events.isEventThread()) Host.throttle();
    }

    /** Vendor APIs (Siemens ExtendedImage, Vodafone SpriteCanvas): show an image on screen now. */
    public void flushBuffer(Image image, int x, int y) {
        Host.drawToScreen(image, x, y);
    }

    public void setFullScreenMode(boolean mode) {
        fullScreen = mode;
        repaint();
    }

    boolean isFullScreen() { return fullScreen; }

    @Override
    void showNotifyImpl() { showNotify(); }

    @Override
    void hideNotifyImpl() {
        keyStates = 0;
        hideNotify();
    }

    // ------------------------------------------------- j2a host hooks (not MIDP)

    public void j2aPaint(Graphics g) { paint(g); }

    /** type: 0 pressed, 1 released, 2 repeated. Runs on the event thread. */
    public void j2aKey(int type, int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Throwable ignored) {}
        int bit = action == 0 ? 0 : (1 << action);
        if (type == 0) {
            keyStates |= bit;
            latchedKeyStates |= bit;
        } else if (type == 1) {
            keyStates &= ~bit;
        }
        if (suppressGameKeys && action != 0) return;
        if (type == 0) keyPressed(keyCode);
        else if (type == 1) keyReleased(keyCode);
        else keyRepeated(keyCode);
    }

    /** type: 0 pressed, 1 dragged, 2 released. Runs on the event thread. */
    public void j2aPointer(int type, int x, int y) {
        if (type == 0) pointerPressed(x, y);
        else if (type == 1) pointerDragged(x, y);
        else pointerReleased(x, y);
    }

    /** Used by GameCanvas. */
    public final void j2aSetSuppress(boolean suppress) { suppressGameKeys = suppress; }

    /** Used by GameCanvas.getKeyStates(): current state plus presses since the last call. */
    public final int j2aKeyStates() {
        int s = keyStates | latchedKeyStates;
        latchedKeyStates = 0;
        return s;
    }

    /** False for Nokia FullCanvas, whose soft keys always reach keyPressed(). */
    public boolean j2aCommandsUseSoftKeys() { return !commands.isEmpty(); }
}
