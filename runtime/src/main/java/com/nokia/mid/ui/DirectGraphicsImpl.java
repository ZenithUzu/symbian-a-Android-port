package com.nokia.mid.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

final class DirectGraphicsImpl implements DirectGraphics {
    private final Graphics g;

    DirectGraphicsImpl(Graphics g) {
        this.g = g;
    }

    public void setARGBColor(int argb) {
        g.j2aSetARGB(argb);
    }

    public int getAlphaComponent() {
        return g.j2aGetARGB() >>> 24;
    }

    public int getNativePixelFormat() {
        return TYPE_INT_8888_ARGB;
    }

    public void drawImage(Image img, int x, int y, int anchor, int manipulation) {
        if (img == null) throw new NullPointerException();
        drawBitmap(img.j2aBitmap(), x, y, anchor, manipulation);
    }

    private void drawBitmap(Bitmap b, int x, int y, int anchor, int manipulation) {
        int w = b.getWidth(), h = b.getHeight();
        Matrix m = new Matrix();
        if ((manipulation & FLIP_HORIZONTAL) != 0) m.postScale(-1, 1, w / 2f, h / 2f);
        if ((manipulation & FLIP_VERTICAL) != 0) m.postScale(1, -1, w / 2f, h / 2f);
        int rot = manipulation & ~(FLIP_HORIZONTAL | FLIP_VERTICAL);
        if (rot != 0) m.postRotate(-rot, w / 2f, h / 2f); // Nokia rotates counter-clockwise
        RectF bounds = new RectF(0, 0, w, h);
        m.mapRect(bounds);
        m.postTranslate(-bounds.left, -bounds.top);
        int bw = Math.round(bounds.width()), bh = Math.round(bounds.height());
        x += g.getTranslateX();
        y += g.getTranslateY();
        if ((anchor & Graphics.HCENTER) != 0) x -= bw / 2;
        else if ((anchor & Graphics.RIGHT) != 0) x -= bw;
        if ((anchor & Graphics.VCENTER) != 0) y -= bh / 2;
        else if ((anchor & Graphics.BOTTOM) != 0) y -= bh;
        Canvas c = g.j2aCanvas();
        c.save();
        c.translate(x, y);
        c.concat(m);
        c.drawBitmap(b, 0, 0, g.j2aBitmapPaint());
        c.restore();
        g.j2aMark(x, y, x + bw, y + bh);
    }

    private void withColor(int argb, Runnable r) {
        int old = g.j2aGetARGB();
        g.j2aSetARGB(argb);
        try { r.run(); } finally { g.j2aSetARGB(old); }
    }

    public void drawTriangle(final int x1, final int y1, final int x2, final int y2, final int x3, final int y3, int argb) {
        withColor(argb, new Runnable() {
            public void run() { g.j2aPolygon(new int[]{x1, x2, x3}, 0, new int[]{y1, y2, y3}, 0, 3, false); }
        });
    }

    public void fillTriangle(final int x1, final int y1, final int x2, final int y2, final int x3, final int y3, int argb) {
        withColor(argb, new Runnable() {
            public void run() { g.j2aPolygon(new int[]{x1, x2, x3}, 0, new int[]{y1, y2, y3}, 0, 3, true); }
        });
    }

    public void drawPolygon(final int[] xs, final int xo, final int[] ys, final int yo, final int n, int argb) {
        withColor(argb, new Runnable() { public void run() { g.j2aPolygon(xs, xo, ys, yo, n, false); } });
    }

    public void fillPolygon(final int[] xs, final int xo, final int[] ys, final int yo, final int n, int argb) {
        withColor(argb, new Runnable() { public void run() { g.j2aPolygon(xs, xo, ys, yo, n, true); } });
    }

    public void drawPixels(int[] pixels, boolean transparency, int offset, int scanlength, int x, int y,
                           int width, int height, int manipulation, int format) {
        if (width <= 0 || height <= 0) return;
        int[] px = new int[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int v = pixels[offset + r * scanlength + c];
                px[r * width + c] = transparency ? v : (v | 0xFF000000);
            }
        }
        drawBitmap(Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), x, y, Graphics.TOP | Graphics.LEFT, manipulation);
    }

    public void drawPixels(short[] pixels, boolean transparency, int offset, int scanlength, int x, int y,
                           int width, int height, int manipulation, int format) {
        if (width <= 0 || height <= 0) return;
        int[] px = new int[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int v = pixels[offset + r * scanlength + c] & 0xFFFF;
                int argb;
                switch (format) {
                    case TYPE_USHORT_4444_ARGB:
                        argb = ((v >> 12) & 0xF) * 0x11 << 24 | ((v >> 8) & 0xF) * 0x11 << 16
                                | ((v >> 4) & 0xF) * 0x11 << 8 | (v & 0xF) * 0x11;
                        if (!transparency) argb |= 0xFF000000;
                        break;
                    case TYPE_USHORT_565_RGB:
                        argb = 0xFF000000 | ((v >> 11) & 0x1F) * 255 / 31 << 16 | ((v >> 5) & 0x3F) * 255 / 63 << 8
                                | (v & 0x1F) * 255 / 31;
                        break;
                    case TYPE_USHORT_555_RGB:
                    case TYPE_USHORT_1555_ARGB:
                        argb = ((v >> 10) & 0x1F) * 255 / 31 << 16 | ((v >> 5) & 0x1F) * 255 / 31 << 8 | (v & 0x1F) * 255 / 31;
                        argb |= (format == TYPE_USHORT_1555_ARGB && transparency && (v & 0x8000) == 0) ? 0 : 0xFF000000;
                        break;
                    default: // TYPE_USHORT_444_RGB
                        argb = 0xFF000000 | ((v >> 8) & 0xF) * 0x11 << 16 | ((v >> 4) & 0xF) * 0x11 << 8 | (v & 0xF) * 0x11;
                        break;
                }
                px[r * width + c] = argb;
            }
        }
        drawBitmap(Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), x, y, Graphics.TOP | Graphics.LEFT, manipulation);
    }

    public void drawPixels(byte[] pixels, byte[] mask, int offset, int scanlength, int x, int y,
                           int width, int height, int manipulation, int format) {
        if (width <= 0 || height <= 0 || format != TYPE_BYTE_1_GRAY) return;
        int[] px = new int[width * height];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int bit = offset + r * scanlength + c;
                boolean black = (pixels[bit >> 3] & (0x80 >> (bit & 7))) != 0;
                boolean opaque = mask == null || (mask[bit >> 3] & (0x80 >> (bit & 7))) != 0;
                px[r * width + c] = !opaque ? 0 : black ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        drawBitmap(Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), x, y, Graphics.TOP | Graphics.LEFT, manipulation);
    }

    public void getPixels(int[] pixels, int offset, int scanlength, int x, int y, int width, int height, int format) {
        g.j2aTarget().getPixels(pixels, offset, scanlength, x + g.getTranslateX(), y + g.getTranslateY(), width, height);
    }

    public void getPixels(short[] pixels, int offset, int scanlength, int x, int y, int width, int height, int format) {
        int[] tmp = new int[width * height];
        g.j2aTarget().getPixels(tmp, 0, width, x + g.getTranslateX(), y + g.getTranslateY(), width, height);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int v = tmp[r * width + c];
                int s;
                if (format == TYPE_USHORT_565_RGB) {
                    s = ((v >> 19) & 0x1F) << 11 | ((v >> 10) & 0x3F) << 5 | ((v >> 3) & 0x1F);
                } else if (format == TYPE_USHORT_4444_ARGB) {
                    s = ((v >>> 28) & 0xF) << 12 | ((v >> 20) & 0xF) << 8 | ((v >> 12) & 0xF) << 4 | ((v >> 4) & 0xF);
                } else {
                    s = ((v >> 20) & 0xF) << 8 | ((v >> 12) & 0xF) << 4 | ((v >> 4) & 0xF);
                }
                pixels[offset + r * scanlength + c] = (short) s;
            }
        }
    }

    public void getPixels(byte[] pixels, byte[] mask, int offset, int scanlength, int x, int y, int width,
                          int height, int format) {
        // 1-bit formats are practically unused by Symbian-era games; not implemented.
    }
}
