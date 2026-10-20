package javax.microedition.lcdui.game;

import java.util.Vector;

import javax.microedition.lcdui.Graphics;

public class LayerManager {
    private final Vector<Layer> layers = new Vector<Layer>();
    private int viewX, viewY, viewW = Integer.MAX_VALUE, viewH = Integer.MAX_VALUE;

    public LayerManager() {}

    public void append(Layer l) {
        if (l == null) throw new NullPointerException();
        layers.removeElement(l);
        layers.addElement(l);
    }

    public void insert(Layer l, int index) {
        if (l == null) throw new NullPointerException();
        layers.removeElement(l);
        layers.insertElementAt(l, Math.min(index, layers.size()));
    }

    public Layer getLayerAt(int index) { return layers.elementAt(index); }

    public int getSize() { return layers.size(); }

    public void remove(Layer l) {
        if (l == null) throw new NullPointerException();
        layers.removeElement(l);
    }

    public void setViewWindow(int x, int y, int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException();
        viewX = x;
        viewY = y;
        viewW = width;
        viewH = height;
    }

    public void paint(Graphics g, int x, int y) {
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        int tx = g.getTranslateX(), ty = g.getTranslateY();
        g.clipRect(x, y, viewW, viewH);
        g.translate(x - viewX, y - viewY);
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.elementAt(i);
            if (l.visible) l.paint(g);
        }
        g.translate(tx - g.getTranslateX(), ty - g.getTranslateY());
        g.setClip(cx, cy, cw, ch);
    }
}
