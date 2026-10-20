package javax.microedition.lcdui.game;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class Sprite extends Layer {
    public static final int TRANS_NONE = 0;
    public static final int TRANS_ROT90 = 5;
    public static final int TRANS_ROT180 = 3;
    public static final int TRANS_ROT270 = 6;
    public static final int TRANS_MIRROR = 2;
    public static final int TRANS_MIRROR_ROT90 = 7;
    public static final int TRANS_MIRROR_ROT180 = 1;
    public static final int TRANS_MIRROR_ROT270 = 4;

    private Image image;
    private int frameW, frameH, rawFrames, perRow;
    private int[] sequence;
    private int seqIndex;
    private int refX, refY;
    private int transform;
    private int colX, colY, colW, colH;

    public Sprite(Image image) {
        this(image, image.getWidth(), image.getHeight());
    }

    public Sprite(Image image, int frameWidth, int frameHeight) {
        super(frameWidth, frameHeight);
        initFrames(image, frameWidth, frameHeight, true);
    }

    public Sprite(Sprite s) {
        super(s.width, s.height);
        image = s.image;
        frameW = s.frameW;
        frameH = s.frameH;
        rawFrames = s.rawFrames;
        perRow = s.perRow;
        sequence = s.sequence.clone();
        seqIndex = s.seqIndex;
        refX = s.refX;
        refY = s.refY;
        transform = s.transform;
        colX = s.colX;
        colY = s.colY;
        colW = s.colW;
        colH = s.colH;
        x = s.x;
        y = s.y;
        visible = s.visible;
    }

    private void initFrames(Image img, int fw, int fh, boolean reset) {
        if (fw < 1 || fh < 1 || img.getWidth() % fw != 0 || img.getHeight() % fh != 0)
            throw new IllegalArgumentException();
        int oldRaw = rawFrames;
        image = img;
        frameW = fw;
        frameH = fh;
        perRow = img.getWidth() / fw;
        rawFrames = perRow * (img.getHeight() / fh);
        if (reset || rawFrames < oldRaw || sequence == null) {
            sequence = new int[rawFrames];
            for (int i = 0; i < rawFrames; i++) sequence[i] = i;
            seqIndex = 0;
        }
        if (reset) {
            colX = 0;
            colY = 0;
            colW = fw;
            colH = fh;
        }
        updateSize();
    }

    public void setImage(Image img, int frameWidth, int frameHeight) {
        int rx = getRefPixelX(), ry = getRefPixelY();
        boolean sizeChanged = frameWidth != frameW || frameHeight != frameH;
        initFrames(img, frameWidth, frameHeight, false);
        if (sizeChanged) {
            colX = 0;
            colY = 0;
            colW = frameWidth;
            colH = frameHeight;
        }
        setRefPixelPosition(rx, ry);
    }

    private boolean swapped() {
        return transform == TRANS_ROT90 || transform == TRANS_ROT270
                || transform == TRANS_MIRROR_ROT90 || transform == TRANS_MIRROR_ROT270;
    }

    private void updateSize() {
        width = swapped() ? frameH : frameW;
        height = swapped() ? frameW : frameH;
    }

    // --------------------------------------------------------- reference pixel

    public void defineReferencePixel(int x, int y) {
        refX = x;
        refY = y;
    }

    private int tRefX() { return tx(refX, refY); }

    private int tRefY() { return ty(refX, refY); }

    /** Frame point -> transformed local point (MIDP RI convention). */
    private int tx(int px, int py) {
        switch (transform) {
            case TRANS_MIRROR: case TRANS_ROT180: return frameW - 1 - px;
            case TRANS_ROT90: case TRANS_MIRROR_ROT90: return frameH - 1 - py;
            case TRANS_ROT270: case TRANS_MIRROR_ROT270: return py;
            default: return px;
        }
    }

    private int ty(int px, int py) {
        switch (transform) {
            case TRANS_ROT180: case TRANS_MIRROR_ROT180: return frameH - 1 - py;
            case TRANS_ROT90: case TRANS_MIRROR_ROT270: return px;
            case TRANS_ROT270: case TRANS_MIRROR_ROT90: return frameW - 1 - px;
            default: return py;
        }
    }

    public void setRefPixelPosition(int x, int y) {
        this.x = x - tRefX();
        this.y = y - tRefY();
    }

    public int getRefPixelX() { return x + tRefX(); }

    public int getRefPixelY() { return y + tRefY(); }

    public void setTransform(int t) {
        if (t < 0 || t > 7) throw new IllegalArgumentException();
        int rx = getRefPixelX(), ry = getRefPixelY();
        transform = t;
        updateSize();
        setRefPixelPosition(rx, ry);
    }

    // ------------------------------------------------------------------ frames

    public void setFrame(int index) {
        if (index < 0 || index >= sequence.length) throw new IndexOutOfBoundsException();
        seqIndex = index;
    }

    public final int getFrame() { return seqIndex; }

    public int getRawFrameCount() { return rawFrames; }

    public int getFrameSequenceLength() { return sequence.length; }

    public void nextFrame() { seqIndex = (seqIndex + 1) % sequence.length; }

    public void prevFrame() { seqIndex = (seqIndex + sequence.length - 1) % sequence.length; }

    public void setFrameSequence(int[] seq) {
        if (seq == null) {
            sequence = new int[rawFrames];
            for (int i = 0; i < rawFrames; i++) sequence[i] = i;
        } else {
            if (seq.length < 1) throw new IllegalArgumentException();
            for (int i = 0; i < seq.length; i++)
                if (seq[i] < 0 || seq[i] >= rawFrames) throw new ArrayIndexOutOfBoundsException();
            sequence = seq.clone();
        }
        seqIndex = 0;
    }

    @Override
    public final void paint(Graphics g) {
        if (!visible) return;
        int f = sequence[seqIndex];
        g.drawRegion(image, (f % perRow) * frameW, (f / perRow) * frameH, frameW, frameH, transform,
                x, y, Graphics.TOP | Graphics.LEFT);
    }

    // --------------------------------------------------------------- collision

    public void defineCollisionRectangle(int x, int y, int w, int h) {
        if (w < 0 || h < 0) throw new IllegalArgumentException();
        colX = x;
        colY = y;
        colW = w;
        colH = h;
    }

    /** Collision rectangle in world coordinates: {x, y, w, h}. */
    private int[] worldCollisionRect() {
        int rx, ry, rw = colW, rh = colH;
        switch (transform) {
            case TRANS_MIRROR: rx = frameW - colX - colW; ry = colY; break;
            case TRANS_ROT180: rx = frameW - colX - colW; ry = frameH - colY - colH; break;
            case TRANS_MIRROR_ROT180: rx = colX; ry = frameH - colY - colH; break;
            case TRANS_ROT90: rx = frameH - colY - colH; ry = colX; rw = colH; rh = colW; break;
            case TRANS_ROT270: rx = colY; ry = frameW - colX - colW; rw = colH; rh = colW; break;
            case TRANS_MIRROR_ROT90: rx = frameH - colY - colH; ry = frameW - colX - colW; rw = colH; rh = colW; break;
            case TRANS_MIRROR_ROT270: rx = colY; ry = colX; rw = colH; rh = colW; break;
            default: rx = colX; ry = colY; break;
        }
        return new int[]{x + rx, y + ry, rw, rh};
    }

    /** True if the world pixel (wx, wy) of this sprite is opaque. */
    private boolean opaqueAt(int wx, int wy) {
        int u = wx - x, v = wy - y;
        if (u < 0 || v < 0 || u >= width || v >= height) return false;
        int fx, fy;
        switch (transform) {
            case TRANS_MIRROR: fx = frameW - 1 - u; fy = v; break;
            case TRANS_ROT180: fx = frameW - 1 - u; fy = frameH - 1 - v; break;
            case TRANS_MIRROR_ROT180: fx = u; fy = frameH - 1 - v; break;
            case TRANS_ROT90: fx = v; fy = frameH - 1 - u; break;
            case TRANS_ROT270: fx = frameW - 1 - v; fy = u; break;
            case TRANS_MIRROR_ROT90: fx = frameW - 1 - v; fy = frameH - 1 - u; break;
            case TRANS_MIRROR_ROT270: fx = v; fy = u; break;
            default: fx = u; fy = v; break;
        }
        int f = sequence[seqIndex];
        int sx = (f % perRow) * frameW + fx, sy = (f / perRow) * frameH + fy;
        return (image.j2aBitmap().getPixel(sx, sy) >>> 24) != 0;
    }

    private static int[] intersect(int[] a, int[] b) {
        int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
        int x2 = Math.min(a[0] + a[2], b[0] + b[2]), y2 = Math.min(a[1] + a[3], b[1] + b[3]);
        if (x2 <= x1 || y2 <= y1) return null;
        return new int[]{x1, y1, x2 - x1, y2 - y1};
    }

    public final boolean collidesWith(Sprite s, boolean pixelLevel) {
        if (!visible || !s.visible) return false;
        int[] r = intersect(worldCollisionRect(), s.worldCollisionRect());
        if (r == null) return false;
        if (!pixelLevel) return true;
        for (int yy = r[1]; yy < r[1] + r[3]; yy++)
            for (int xx = r[0]; xx < r[0] + r[2]; xx++)
                if (opaqueAt(xx, yy) && s.opaqueAt(xx, yy)) return true;
        return false;
    }

    public final boolean collidesWith(TiledLayer t, boolean pixelLevel) {
        if (!visible || !t.visible) return false;
        int[] r = intersect(worldCollisionRect(), new int[]{t.x, t.y, t.width, t.height});
        if (r == null) return false;
        int c0 = (r[0] - t.x) / t.tileW, c1 = (r[0] + r[2] - 1 - t.x) / t.tileW;
        int r0 = (r[1] - t.y) / t.tileH, r1 = (r[1] + r[3] - 1 - t.y) / t.tileH;
        if (!pixelLevel) {
            for (int row = r0; row <= r1; row++)
                for (int col = c0; col <= c1; col++)
                    if (t.staticTileAt(col, row) > 0) return true;
            return false;
        }
        for (int yy = r[1]; yy < r[1] + r[3]; yy++)
            for (int xx = r[0]; xx < r[0] + r[2]; xx++)
                if (opaqueAt(xx, yy) && t.opaqueAt(xx, yy)) return true;
        return false;
    }

    public final boolean collidesWith(Image img, int ix, int iy, boolean pixelLevel) {
        if (!visible) return false;
        int[] r = intersect(worldCollisionRect(), new int[]{ix, iy, img.getWidth(), img.getHeight()});
        if (r == null) return false;
        if (!pixelLevel) return true;
        for (int yy = r[1]; yy < r[1] + r[3]; yy++)
            for (int xx = r[0]; xx < r[0] + r[2]; xx++)
                if (opaqueAt(xx, yy) && (img.j2aBitmap().getPixel(xx - ix, yy - iy) >>> 24) != 0) return true;
        return false;
    }
}
