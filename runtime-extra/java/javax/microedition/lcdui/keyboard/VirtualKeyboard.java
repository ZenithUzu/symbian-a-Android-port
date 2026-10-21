package javax.microedition.lcdui.keyboard;

/** Key state bitmask for Vodafone/J-Phone/KDDI device APIs, fed by the j2a keypad and keys. */
public class VirtualKeyboard {
	public int getKeyStatesVodafone() {
		return j2a.core.Host.vodafoneKeyStates();
	}
}
