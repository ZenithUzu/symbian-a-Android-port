package javax.microedition.lcdui;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.HashMap;

import j2a.core.Host;

public final class Font {
    public static final int FACE_SYSTEM = 0;
    public static final int FACE_MONOSPACE = 32;
    public static final int FACE_PROPORTIONAL = 64;
    public static final int STYLE_PLAIN = 0;
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_UNDERLINED = 4;
    public static final int SIZE_SMALL = 8;
    public static final int SIZE_MEDIUM = 0;
    public static final int SIZE_LARGE = 16;
    public static final int FONT_STATIC_TEXT = 0;
    public static final int FONT_INPUT_TEXT = 1;

    private static final HashMap<Integer, Font> cache = new HashMap<Integer, Font>();
    private static Font defaultFont;

    private final int face, style, size;
    final Paint paint;
    private final int ascent, descent, height;

    private Font(int face, int style, int size) {
        this.face = face;
        this.style = style;
        this.size = size;
        Typeface base = face == FACE_MONOSPACE ? Typeface.MONOSPACE
                : face == FACE_PROPORTIONAL ? Typeface.SANS_SERIF : Typeface.DEFAULT;
        int tstyle = ((style & STYLE_BOLD) != 0 ? Typeface.BOLD : 0) | ((style & STYLE_ITALIC) != 0 ? Typeface.ITALIC : 0);
        paint = new Paint();
        paint.setTypeface(Typeface.create(base, tstyle));
        paint.setAntiAlias(Host.cfg.textAntialias);
        paint.setUnderlineText((style & STYLE_UNDERLINED) != 0);
        int px = size == SIZE_SMALL ? Host.cfg.fontSmall : size == SIZE_LARGE ? Host.cfg.fontLarge : Host.cfg.fontMedium;
        paint.setTextSize(px);
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        ascent = -fm.ascent;
        descent = fm.descent;
        height = ascent + descent;
    }

    public static Font getFont(int face, int style, int size) {
        if (face != FACE_SYSTEM && face != FACE_MONOSPACE && face != FACE_PROPORTIONAL)
            throw new IllegalArgumentException("face");
        if (size != SIZE_SMALL && size != SIZE_MEDIUM && size != SIZE_LARGE)
            throw new IllegalArgumentException("size");
        int key = face | (style << 8) | (size << 16);
        synchronized (cache) {
            Font f = cache.get(key);
            if (f == null) {
                f = new Font(face, style, size);
                cache.put(key, f);
            }
            return f;
        }
    }

    public static Font getFont(int fontSpecifier) {
        if (fontSpecifier != FONT_STATIC_TEXT && fontSpecifier != FONT_INPUT_TEXT)
            throw new IllegalArgumentException();
        return getDefaultFont();
    }

    public static Font getDefaultFont() {
        if (defaultFont == null) defaultFont = getFont(FACE_SYSTEM, STYLE_PLAIN, SIZE_MEDIUM);
        return defaultFont;
    }

    public int getStyle() { return style; }

    public int getSize() { return size; }

    public int getFace() { return face; }

    public boolean isPlain() { return style == STYLE_PLAIN; }

    public boolean isBold() { return (style & STYLE_BOLD) != 0; }

    public boolean isItalic() { return (style & STYLE_ITALIC) != 0; }

    public boolean isUnderlined() { return (style & STYLE_UNDERLINED) != 0; }

    public int getHeight() { return height; }

    public int getBaselinePosition() { return ascent; }

    int getDescent() { return descent; }

    public int charWidth(char ch) {
        synchronized (paint) { return Math.round(paint.measureText(String.valueOf(ch))); }
    }

    public int charsWidth(char[] ch, int offset, int length) {
        synchronized (paint) { return Math.round(paint.measureText(ch, offset, length)); }
    }

    public int stringWidth(String str) {
        synchronized (paint) { return Math.round(paint.measureText(str)); }
    }

    public int substringWidth(String str, int offset, int len) {
        synchronized (paint) { return Math.round(paint.measureText(str, offset, offset + len)); }
    }

    /** Android text size in pixels (used by native Screens). */
    public float j2aTextSize() { return paint.getTextSize(); }

    public Typeface j2aTypeface() { return paint.getTypeface(); }
}
