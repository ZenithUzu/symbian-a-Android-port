package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import j2a.core.ResourceShim;

public class Image {
    final Bitmap bitmap;
    private final boolean mutable;

    Image(Bitmap b, boolean mutable) {
        this.bitmap = b;
        this.mutable = mutable;
    }

    public static Image createImage(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException();
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        return new Image(b, true);
    }

    public static Image createImage(Image source) {
        if (source == null) throw new NullPointerException();
        if (!source.mutable) return source;
        return new Image(source.bitmap.copy(Bitmap.Config.ARGB_8888, false), false);
    }

    public static Image createImage(String name) throws IOException {
        if (name == null) throw new NullPointerException();
        InputStream in = ResourceShim.open(name);
        if (in == null) throw new IOException("Image not found: " + name);
        try {
            return createImage(in);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    public static Image createImage(byte[] data, int offset, int length) {
        Bitmap b = BitmapFactory.decodeByteArray(data, offset, length, opts());
        if (b == null) throw new IllegalArgumentException("Unsupported image data");
        return new Image(b, false);
    }

    public static Image createImage(InputStream stream) throws IOException {
        if (stream == null) throw new NullPointerException();
        // Read fully first: some J2ME streams return short reads.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        byte[] data = out.toByteArray();
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, opts());
        if (b == null) throw new IOException("Unsupported image data");
        return new Image(b, false);
    }

    public static Image createImage(Image image, int x, int y, int width, int height, int transform) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException();
        boolean swap = Sprite_isSwapped(transform);
        int w = swap ? height : width, h = swap ? width : height;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Graphics.drawRegionOn(c, image.bitmap, x, y, width, height, transform, 0, 0, null);
        return new Image(b, false);
    }

    public static Image createRGBImage(int[] rgb, int width, int height, boolean processAlpha) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException();
        int[] px = rgb;
        if (!processAlpha) {
            px = new int[width * height];
            for (int i = 0; i < px.length; i++) px[i] = rgb[i] | 0xFF000000;
        }
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, width, 0, 0, width, height);
        return new Image(b, false);
    }

    private static BitmapFactory.Options opts() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return o;
    }

    static boolean Sprite_isSwapped(int transform) {
        return transform == 4 || transform == 5 || transform == 6 || transform == 7;
    }

    public Graphics getGraphics() {
        if (!mutable) throw new IllegalStateException("Image is immutable");
        return new Graphics(bitmap);
    }

    public int getWidth() { return bitmap.getWidth(); }

    public int getHeight() { return bitmap.getHeight(); }

    public boolean isMutable() { return mutable; }

    public void getRGB(int[] rgbData, int offset, int scanlength, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        bitmap.getPixels(rgbData, offset, scanlength, x, y, width, height);
    }

    /** Screen-sized buffer: black instead of MIDP's white, so areas a game never draws look like bars. */
    public static Image j2aCreateScreen(int w, int h) {
        return j2aCreateMutable(w, h, 0xFF000000);
    }

    /** Mutable image filled with an ARGB color (Nokia DirectUtils). Not part of MIDP. */
    public static Image j2aCreateMutable(int w, int h, int argb) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(argb);
        return new Image(b, true);
    }

    /** Wraps an Android bitmap. Not part of MIDP. */
    public static Image j2aWrap(Bitmap b, boolean mutable) {
        return new Image(b, mutable);
    }

    /** Underlying Android bitmap. Not part of MIDP. */
    public Bitmap j2aBitmap() { return bitmap; }
}
