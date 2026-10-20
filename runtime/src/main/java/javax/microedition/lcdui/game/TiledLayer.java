package javax.microedition.lcdui.game;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class TiledLayer extends Layer {
    private final int rows, cols;
    private int[][] cells;
    Image image;
    int tileW, tileH;
    private int tileCount;
    private int[] animated = new int[4];
    private int animatedCount;

    public TiledLayer(int columns, int rows, Image image, int tileWidth, int tileHeight) {
        super(columns * tileWidth, rows * tileHeight);
        if (columns < 1 || rows < 1) throw new IllegalArgumentException();
        this.cols = columns;
        this.rows = rows;
        this.cells = new int[rows][columns];
        setTiles(image, tileWidth, tileHeight);
    }

    private void setTiles(Image img, int tw, int th) {
        if (tw < 1 || th < 1 || img.getWidth() % tw != 0 || img.getHeight() % th != 0)
            throw new IllegalArgumentException();
        image = img;
        tileW = tw;
        tileH = th;
        tileCount = (img.getWidth() / tw) * (img.getHeight() / th);
        width = cols * tw;
        height = rows * th;
    }

    public void setStaticTileSet(Image image, int tileWidth, int tileHeight) {
        int old = tileCount;
        setTiles(image, tileWidth, tileHeight);
        if (tileCount < old) {
            cells = new int[rows][cols];
            animatedCount = 0;
        }
    }

    public int createAnimatedTile(int staticTileIndex) {
        if (staticTileIndex < 0 || staticTileIndex > tileCount) throw new IndexOutOfBoundsException();
        if (animatedCount == animated.length) {
            int[] n = new int[animated.length * 2];
            System.arraycopy(animated, 0, n, 0, animated.length);
            animated = n;
        }
        animated[animatedCount++] = staticTileIndex;
        return -animatedCount;
    }

    public void setAnimatedTile(int animatedTileIndex, int staticTileIndex) {
        int i = -animatedTileIndex - 1;
        if (i < 0 || i >= animatedCount) throw new IndexOutOfBoundsException();
        animated[i] = staticTileIndex;
    }

    public int getAnimatedTile(int animatedTileIndex) {
        int i = -animatedTileIndex - 1;
        if (i < 0 || i >= animatedCount) throw new IndexOutOfBoundsException();
        return animated[i];
    }

    public void setCell(int col, int row, int tileIndex) {
        cells[row][col] = tileIndex;
    }

    public int getCell(int col, int row) {
        return cells[row][col];
    }

    public void fillCells(int col, int row, int numCols, int numRows, int tileIndex) {
        for (int r = row; r < row + numRows; r++)
            for (int c = col; c < col + numCols; c++) cells[r][c] = tileIndex;
    }

    public final int getCellWidth() { return tileW; }

    public final int getCellHeight() { return tileH; }

    public final int getColumns() { return cols; }

    public final int getRows() { return rows; }

    /** Resolves animated tiles; returns 0 for empty cells. */
    int staticTileAt(int col, int row) {
        int t = cells[row][col];
        if (t < 0) t = animated[-t - 1];
        return t;
    }

    @Override
    public final void paint(Graphics g) {
        if (!visible) return;
        int clipX = g.getClipX(), clipY = g.getClipY();
        int c0 = Math.max(0, (clipX - x) / tileW);
        int r0 = Math.max(0, (clipY - y) / tileH);
        int c1 = Math.min(cols - 1, (clipX + g.getClipWidth() - x) / tileW);
        int r1 = Math.min(rows - 1, (clipY + g.getClipHeight() - y) / tileH);
        int perRow = image.getWidth() / tileW;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int t = staticTileAt(c, r);
                if (t <= 0) continue;
                t--;
                g.drawRegion(image, (t % perRow) * tileW, (t / perRow) * tileH, tileW, tileH, 0,
                        x + c * tileW, y + r * tileH, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    /** True if the world pixel (wx, wy) is covered by an opaque tile pixel. */
    boolean opaqueAt(int wx, int wy) {
        int lx = wx - x, ly = wy - y;
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return false;
        int t = staticTileAt(lx / tileW, ly / tileH);
        if (t <= 0) return false;
        t--;
        int perRow = image.getWidth() / tileW;
        int sx = (t % perRow) * tileW + lx % tileW;
        int sy = (t / perRow) * tileH + ly % tileH;
        return (image.j2aBitmap().getPixel(sx, sy) >>> 24) != 0;
    }
}
