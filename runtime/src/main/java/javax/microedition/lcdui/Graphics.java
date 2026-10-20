package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import j2a.core.AutoFit;

/**
 * MIDP Graphics implemented on top of android.graphics.Canvas.
 * Translation and clipping are tracked by hand so that J2ME semantics
 * (setClip replaces the clip, clipRect intersects it) are exact.
 */
public class Graphics {
    public static final int HCENTER = 1;
    public static final int VCENTER = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int TOP = 16;
    public static final int BOTTOM = 32;
    public static final int BASELINE = 64;
    public static final int SOLID = 0;
    public static final int DOTTED = 1;

    private final Bitmap target;
    private final Canvas canvas;
    private final Paint fill = new Paint();
    private final Paint stroke = new Paint();
    private final Paint text = new Paint();
    private final Paint bmp = new Paint();
    private final Rect srcRect = new Rect();
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private final Matrix regionMatrix = new Matrix();
    private final Rect regionSrc = new Rect();
    private final RectF regionDst = new RectF();
    private int[] rgbBuf;
    private Bitmap rgbBitmap;
    /** Cached Nokia DirectGraphics for this Graphics (games ask for it every frame). */
    public Object j2aDirect;

    private int tx, ty;
    private int cx, cy, cw, ch;
    private int color = 0xFF000000;
    private int strokeStyle = SOLID;
    private Font font;
    private int saveCount;
    /** Reports drawn areas to Host (only the game screen / GameCanvas buffer does). */
    private boolean track;

    Graphics(Bitmap target) {
        this.target = target;
        this.canvas = new Canvas(target);
        fill.setStyle(Paint.Style.FILL);
        fill.setAntiAlias(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1);
        stroke.setAntiAlias(false);
        bmp.setFilterBitmap(false);
        saveCount = canvas.save();
        j2aReset();
    }

    /** Resets translation, clip, color, font and stroke (used before each paint()). */
    public void j2aReset() {
        tx = ty = 0;
        color = 0xFF000000;
        strokeStyle = SOLID;
        stroke.setPathEffect(null);
        setFont(null);
        updateColor();
        cx = cy = 0;
        cw = target.getWidth();
        ch = target.getHeight();
        applyClip();
    }

    // -------------------------------------------------------- translation/clip

    public void translate(int x, int y) { tx += x; ty += y; }

    public int getTranslateX() { return tx; }

    public int getTranslateY() { return ty; }

    public int getClipX() { return cx - tx; }

    public int getClipY() { return cy - ty; }

    public int getClipWidth() { return cw; }

    public int getClipHeight() { return ch; }

    public void clipRect(int x, int y, int width, int height) {
        int x1 = Math.max(cx, x + tx), y1 = Math.max(cy, y + ty);
        int x2 = Math.min(cx + cw, x + tx + width), y2 = Math.min(cy + ch, y + ty + height);
        cx = x1;
        cy = y1;
        cw = Math.max(0, x2 - x1);
        ch = Math.max(0, y2 - y1);
        applyClip();
    }

    public void setClip(int x, int y, int width, int height) {
        int x1 = Math.max(0, x + tx), y1 = Math.max(0, y + ty);
        int x2 = Math.min(target.getWidth(), x + tx + width), y2 = Math.min(target.getHeight(), y + ty + height);
        cx = x1;
        cy = y1;
        cw = Math.max(0, x2 - x1);
        ch = Math.max(0, y2 - y1);
        applyClip();
    }

    private void applyClip() {
        canvas.restoreToCount(saveCount);
        saveCount = canvas.save();
        canvas.clipRect(cx, cy, cx + cw, cy + ch);
    }

    // ------------------------------------------------------------------ color

    public void setColor(int red, int green, int blue) {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255)
            throw new IllegalArgumentException();
        setColor((red << 16) | (green << 8) | blue);
    }

    public void setColor(int rgb) {
        color = 0xFF000000 | (rgb & 0xFFFFFF);
        updateColor();
    }

    /** Sets a color with alpha (used by Nokia DirectGraphics.setARGBColor). */
    public void j2aSetARGB(int argb) {
        color = argb;
        updateColor();
    }

    private void updateColor() {
        fill.setColor(color);
        stroke.setColor(color);
        text.setColor(color);
    }

    public int getColor() { return color & 0xFFFFFF; }

    public int j2aGetARGB() { return color; }

    public int getRedComponent() { return (color >> 16) & 0xFF; }

    public int getGreenComponent() { return (color >> 8) & 0xFF; }

    public int getBlueComponent() { return color & 0xFF; }

    public int getGrayScale() { return (getRedComponent() * 30 + getGreenComponent() * 59 + getBlueComponent() * 11) / 100; }

    public void setGrayScale(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException();
        setColor(value, value, value);
    }

    public int getDisplayColor(int c) { return c & 0xFFFFFF; }

    public void setStrokeStyle(int style) {
        if (style != SOLID && style != DOTTED) throw new IllegalArgumentException();
        strokeStyle = style;
        stroke.setPathEffect(style == DOTTED ? new DashPathEffect(new float[]{2, 2}, 0) : null);
    }

    public int getStrokeStyle() { return strokeStyle; }

    // ------------------------------------------------------------------- font

    public void setFont(Font f) {
        font = f == null ? Font.getDefaultFont() : f;
        text.setTypeface(font.paint.getTypeface());
        text.setTextSize(font.paint.getTextSize());
        text.setUnderlineText(font.isUnderlined());
        text.setAntiAlias(font.paint.isAntiAlias());
    }

    public Font getFont() { return font; }

    // ------------------------------------------------------------- primitives

    public void drawLine(int x1, int y1, int x2, int y2) {
        x1 += tx; x2 += tx; y1 += ty; y2 += ty;
        if (track) j2aMark(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1);
        if (strokeStyle == SOLID && x1 == x2) {
            canvas.drawRect(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, fill);
        } else if (strokeStyle == SOLID && y1 == y2) {
            canvas.drawRect(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, fill);
        } else {
            canvas.drawLine(x1 + 0.5f, y1 + 0.5f, x2 + 0.5f, y2 + 0.5f, stroke);
            canvas.drawPoint(x2 + 0.5f, y2 + 0.5f, stroke);
        }
    }

    public void fillRect(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        x += tx;
        y += ty;
        canvas.drawRect(x, y, x + width, y + height, fill);
        if (track) j2aMark(x, y, x + width, y + height);
    }

    public void drawRect(int x, int y, int width, int height) {
        if (width < 0 || height < 0) return;
        if (strokeStyle == DOTTED) {
            rf.set(x + tx + 0.5f, y + ty + 0.5f, x + tx + width + 0.5f, y + ty + height + 0.5f);
            canvas.drawRect(rf, stroke);
            markF(rf);
            return;
        }
        if (width == 0 || height == 0) {
            fillRect(x, y, width + 1, height + 1);
            return;
        }
        fillRect(x, y, width + 1, 1);
        fillRect(x, y + height, width + 1, 1);
        fillRect(x, y + 1, 1, height - 1);
        fillRect(x + width, y + 1, 1, height - 1);
    }

    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (width < 0 || height < 0) return;
        rf.set(x + tx + 0.5f, y + ty + 0.5f, x + tx + width + 0.5f, y + ty + height + 0.5f);
        canvas.drawRoundRect(rf, Math.abs(arcWidth) / 2f, Math.abs(arcHeight) / 2f, stroke);
        markF(rf);
    }

    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (width <= 0 || height <= 0) return;
        rf.set(x + tx, y + ty, x + tx + width, y + ty + height);
        canvas.drawRoundRect(rf, Math.abs(arcWidth) / 2f, Math.abs(arcHeight) / 2f, fill);
        markF(rf);
    }

    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        if (width <= 0 || height <= 0) return;
        rf.set(x + tx, y + ty, x + tx + width, y + ty + height);
        if (Math.abs(arcAngle) >= 360) canvas.drawOval(rf, fill);
        else canvas.drawArc(rf, -startAngle, -arcAngle, true, fill);
        markF(rf);
    }

    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        if (width < 0 || height < 0) return;
        rf.set(x + tx + 0.5f, y + ty + 0.5f, x + tx + width + 0.5f, y + ty + height + 0.5f);
        if (Math.abs(arcAngle) >= 360) canvas.drawOval(rf, stroke);
        else canvas.drawArc(rf, -startAngle, -arcAngle, false, stroke);
        markF(rf);
    }

    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3) {
        path.reset();
        path.moveTo(x1 + tx, y1 + ty);
        path.lineTo(x2 + tx, y2 + ty);
        path.lineTo(x3 + tx, y3 + ty);
        path.close();
        canvas.drawPath(path, fill);
        markPath();
    }

    /** Used by Nokia DirectGraphics. */
    public void j2aPolygon(int[] xs, int xOff, int[] ys, int yOff, int n, boolean filled) {
        if (n <= 0) return;
        path.reset();
        path.moveTo(xs[xOff] + tx + (filled ? 0 : 0.5f), ys[yOff] + ty + (filled ? 0 : 0.5f));
        for (int i = 1; i < n; i++) path.lineTo(xs[xOff + i] + tx + (filled ? 0 : 0.5f), ys[yOff + i] + ty + (filled ? 0 : 0.5f));
        path.close();
        canvas.drawPath(path, filled ? fill : stroke);
        markPath();
    }

    // ------------------------------------------------------------------- text

    public void drawString(String str, int x, int y, int anchor) {
        if (str == null) throw new NullPointerException();
        drawText(str, 0, str.length(), x, y, anchor);
    }

    public void drawSubstring(String str, int offset, int len, int x, int y, int anchor) {
        if (str == null) throw new NullPointerException();
        drawText(str, offset, offset + len, x, y, anchor);
    }

    public void drawChar(char character, int x, int y, int anchor) {
        drawString(String.valueOf(character), x, y, anchor);
    }

    public void drawChars(char[] data, int offset, int length, int x, int y, int anchor) {
        drawString(new String(data, offset, length), x, y, anchor);
    }

    private void drawText(String s, int start, int end, int x, int y, int anchor) {
        if (end <= start) return;
        x += tx;
        y += ty;
        if ((anchor & HCENTER) != 0 || (anchor & RIGHT) != 0) {
            int w = Math.round(text.measureText(s, start, end));
            x -= (anchor & HCENTER) != 0 ? w / 2 : w;
        }
        int baseline;
        if ((anchor & BOTTOM) != 0) baseline = y - font.getDescent();
        else if ((anchor & BASELINE) != 0) baseline = y;
        else if ((anchor & VCENTER) != 0) baseline = y - font.getHeight() / 2 + font.getBaselinePosition();
        else baseline = y + font.getBaselinePosition();
        canvas.drawText(s, start, end, x, baseline, text);
        if (track && AutoFit.tracking) {
            j2aMark(x, baseline - font.getBaselinePosition(), x + Math.round(text.measureText(s, start, end)),
                    baseline + font.getDescent());
        }
    }

    // ----------------------------------------------------------------- images

    public void drawImage(Image img, int x, int y, int anchor) {
        if (img == null) throw new NullPointerException();
        Bitmap b = img.bitmap;
        x += tx;
        y += ty;
        if ((anchor & HCENTER) != 0) x -= b.getWidth() / 2;
        else if ((anchor & RIGHT) != 0) x -= b.getWidth();
        if ((anchor & VCENTER) != 0) y -= b.getHeight() / 2;
        else if ((anchor & BOTTOM) != 0) y -= b.getHeight();
        canvas.drawBitmap(b, x, y, bmp);
        if (track) {
            j2aMark(x, y, x + b.getWidth(), y + b.getHeight());
            if (AutoFit.tracking) AutoFit.imageDrawn(0, b.getWidth() * b.getHeight());
        }
    }

    public void drawRegion(Image src, int xSrc, int ySrc, int width, int height, int transform,
                           int xDest, int yDest, int anchor) {
        if (src == null) throw new NullPointerException();
        if (width <= 0 || height <= 0) return;
        boolean swap = Image.Sprite_isSwapped(transform);
        int dw = swap ? height : width, dh = swap ? width : height;
        int x = xDest + tx, y = yDest + ty;
        if ((anchor & HCENTER) != 0) x -= dw / 2;
        else if ((anchor & RIGHT) != 0) x -= dw;
        if ((anchor & VCENTER) != 0) y -= dh / 2;
        else if ((anchor & BOTTOM) != 0) y -= dh;
        if (track) {
            j2aMark(x, y, x + dw, y + dh);
            // TRANS_ROT90 = 5, TRANS_ROT270 = 6 (mirrored variants say nothing about the phone's side)
            if (AutoFit.tracking) AutoFit.imageDrawn(transform == 5 ? 90 : transform == 6 ? 270 : 0, dw * dh);
        }
        if (transform == 0) {
            srcRect.set(xSrc, ySrc, xSrc + width, ySrc + height);
            rf.set(x, y, x + width, y + height);
            canvas.drawBitmap(src.bitmap, srcRect, rf, bmp);
            return;
        }
        regionMatrix.reset();
        transformMatrix(regionMatrix, transform, width, height);
        regionSrc.set(xSrc, ySrc, xSrc + width, ySrc + height);
        regionDst.set(0, 0, width, height);
        canvas.save();
        canvas.translate(x, y);
        canvas.concat(regionMatrix);
        canvas.drawBitmap(src.bitmap, regionSrc, regionDst, bmp);
        canvas.restore();
    }

    /** MIDP Sprite transform for a w x h region whose result starts at (0,0). */
    static void transformMatrix(Matrix m, int transform, int w, int h) {
        switch (transform) {
            case 2: m.postScale(-1, 1); m.postTranslate(w, 0); break;                 // MIRROR
            case 3: m.postRotate(180); m.postTranslate(w, h); break;                  // ROT180
            case 1: m.postScale(1, -1); m.postTranslate(0, h); break;                 // MIRROR_ROT180
            case 5: m.postRotate(90); m.postTranslate(h, 0); break;                   // ROT90
            case 6: m.postRotate(270); m.postTranslate(0, w); break;                  // ROT270
            case 7: m.postScale(-1, 1); m.postTranslate(w, 0); m.postRotate(90); m.postTranslate(h, 0); break;
            case 4: m.postScale(-1, 1); m.postTranslate(w, 0); m.postRotate(270); m.postTranslate(0, w); break;
            default: break;
        }
    }

    /** Draws a transformed region with its top-left corner at (dx, dy). */
    static void drawRegionOn(Canvas c, Bitmap src, int xs, int ys, int w, int h, int transform,
                             int dx, int dy, Paint p) {
        Matrix m = new Matrix();
        transformMatrix(m, transform, w, h);
        c.save();
        c.translate(dx, dy);
        c.concat(m);
        c.drawBitmap(src, new Rect(xs, ys, xs + w, ys + h), new RectF(0, 0, w, h), p);
        c.restore();
    }

    public void drawRGB(int[] rgbData, int offset, int scanlength, int x, int y, int width, int height,
                        boolean processAlpha) {
        if (rgbData == null) throw new NullPointerException();
        if (width <= 0 || height <= 0) return;
        int n = width * height;
        if (rgbBuf == null || rgbBuf.length < n) rgbBuf = new int[n];
        for (int row = 0; row < height; row++) {
            int s = offset + row * scanlength, d = row * width;
            if (processAlpha) {
                System.arraycopy(rgbData, s, rgbBuf, d, width);
            } else {
                for (int col = 0; col < width; col++) rgbBuf[d + col] = rgbData[s + col] | 0xFF000000;
            }
        }
        if (rgbBitmap == null || rgbBitmap.getWidth() != width || rgbBitmap.getHeight() != height) {
            if (rgbBitmap != null) rgbBitmap.recycle();
            rgbBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        rgbBitmap.setPixels(rgbBuf, 0, width, 0, 0, width, height);
        canvas.drawBitmap(rgbBitmap, x + tx, y + ty, bmp);
        if (track) j2aMark(x + tx, y + ty, x + tx + width, y + ty + height);
    }

    public void copyArea(int xSrc, int ySrc, int width, int height, int xDest, int yDest, int anchor) {
        if (width <= 0 || height <= 0) return;
        int sx = Math.max(0, xSrc + tx), sy = Math.max(0, ySrc + ty);
        int w = Math.min(width, target.getWidth() - sx), h = Math.min(height, target.getHeight() - sy);
        if (w <= 0 || h <= 0) return;
        Bitmap part = Bitmap.createBitmap(target, sx, sy, w, h);
        int x = xDest + tx, y = yDest + ty;
        if ((anchor & HCENTER) != 0) x -= w / 2;
        else if ((anchor & RIGHT) != 0) x -= w;
        if ((anchor & VCENTER) != 0) y -= h / 2;
        else if ((anchor & BOTTOM) != 0) y -= h;
        canvas.drawBitmap(part, x, y, bmp);
        if (track) j2aMark(x, y, x + w, y + h);
    }

    // ------------------------------------------------------- j2a extensions

    public boolean j2aTracked() { return track; }

    /** Marks this Graphics as drawing to the visible game screen. */
    public void j2aTrack() { track = true; }

    /** Reports a drawn area (device coords) for automatic resolution detection. */
    public void j2aMark(int l, int t, int r, int b) {
        if (!track || !AutoFit.tracking) return;
        l = Math.max(l, cx);
        t = Math.max(t, cy);
        r = Math.min(r, cx + cw);
        b = Math.min(b, cy + ch);
        if (r <= l || b <= t) return;
        AutoFit.drawn(l, t, r, b, target.getWidth(), target.getHeight());
    }

    private void markF(RectF r) {
        if (track) j2aMark((int) Math.floor(r.left), (int) Math.floor(r.top), (int) Math.ceil(r.right), (int) Math.ceil(r.bottom));
    }

    private void markPath() {
        if (!track) return;
        path.computeBounds(rf, true);
        markF(rf);
    }

    public Canvas j2aCanvas() { return canvas; }

    public Paint j2aBitmapPaint() { return bmp; }

    public Bitmap j2aTarget() { return target; }
}
