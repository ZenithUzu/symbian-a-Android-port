package j2a.core;

import android.view.KeyEvent;

/**
 * J2ME key codes (Nokia convention, used by the vast majority of Symbian/S40
 * games) and the mapping from Android physical keys / gamepads.
 */
public final class Keys {
    public static final int UP = -1, DOWN = -2, LEFT = -3, RIGHT = -4, FIRE = -5;
    public static final int SOFT_LEFT = -6, SOFT_RIGHT = -7, CLEAR = -8, SEND = -10, END = -11;
    public static final int NUM0 = 48, NUM1 = 49, NUM2 = 50, NUM3 = 51, NUM4 = 52,
            NUM5 = 53, NUM6 = 54, NUM7 = 55, NUM8 = 56, NUM9 = 57, STAR = 42, POUND = 35;

    private Keys() {}

    /** Gamepad buttons and d-pad -> physical control id of Config.padMap ("pa", "pdu"...), or null. */
    public static String padId(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "pa";
            case KeyEvent.KEYCODE_BUTTON_B: return "pb";
            case KeyEvent.KEYCODE_BUTTON_X: return "px";
            case KeyEvent.KEYCODE_BUTTON_Y: return "py";
            case KeyEvent.KEYCODE_BUTTON_L1: return "pl";
            case KeyEvent.KEYCODE_BUTTON_R1: return "pr";
            case KeyEvent.KEYCODE_BUTTON_L2: return "pl2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "pr2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "pl3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "pr3";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "pselect";
            case KeyEvent.KEYCODE_BUTTON_START: return "pstart";
            case KeyEvent.KEYCODE_DPAD_UP: return "pdu";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "pdd";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "pdl";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "pdr";
            default: return null;
        }
    }

    /** Keyboard keys (and keys without a pad id). Returns 0 if the Android key has no J2ME equivalent. */
    public static int fromAndroid(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W: return UP;
            case KeyEvent.KEYCODE_S: return DOWN;
            case KeyEvent.KEYCODE_A: return LEFT;
            case KeyEvent.KEYCODE_D: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return FIRE;
            case KeyEvent.KEYCODE_Q: case KeyEvent.KEYCODE_F1: case KeyEvent.KEYCODE_MENU: return SOFT_LEFT;
            case KeyEvent.KEYCODE_E: case KeyEvent.KEYCODE_F2: return SOFT_RIGHT;
            case KeyEvent.KEYCODE_DEL: return CLEAR;
            case KeyEvent.KEYCODE_STAR: case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return STAR;
            case KeyEvent.KEYCODE_POUND: return POUND;
            case KeyEvent.KEYCODE_CALL: return SEND;
            default:
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return NUM0 + (keyCode - KeyEvent.KEYCODE_0);
                if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return NUM0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
                return 0;
        }
    }

    public static String name(int code) {
        switch (code) {
            case UP: return "Up";
            case DOWN: return "Down";
            case LEFT: return "Left";
            case RIGHT: return "Right";
            case FIRE: return "Select";
            case SOFT_LEFT: return "Soft1";
            case SOFT_RIGHT: return "Soft2";
            case CLEAR: return "Clear";
            case SEND: return "Send";
            case END: return "End";
            case STAR: return "*";
            case POUND: return "#";
            default:
                if (code >= NUM0 && code <= NUM9) return String.valueOf((char) code);
                return "Key" + code;
        }
    }
}
