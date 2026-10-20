package javax.microedition.lcdui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import j2a.core.Host;

public abstract class CustomItem extends Item {
    protected static final int TRAVERSE_HORIZONTAL = 1;
    protected static final int TRAVERSE_VERTICAL = 2;
    protected static final int KEY_PRESS = 4;
    protected static final int KEY_RELEASE = 8;
    protected static final int KEY_REPEAT = 0x10;
    protected static final int POINTER_PRESS = 0x20;
    protected static final int POINTER_RELEASE = 0x40;
    protected static final int POINTER_DRAG = 0x80;
    protected static final int NONE = 0;

    private ImageView view;
    private Image buffer;

    protected CustomItem(String label) {
        super(label);
    }

    public int getGameAction(int keyCode) {
        return 0;
    }

    protected final int getInteractionModes() {
        return POINTER_PRESS | POINTER_RELEASE | POINTER_DRAG | KEY_PRESS | KEY_RELEASE;
    }

    protected abstract int getMinContentWidth();

    protected abstract int getMinContentHeight();

    protected abstract int getPrefContentWidth(int height);

    protected abstract int getPrefContentHeight(int width);

    protected abstract void paint(Graphics g, int w, int h);

    protected final void invalidate() {
        super.invalidate();
    }

    protected final void repaint() {
        render();
    }

    protected final void repaint(int x, int y, int w, int h) {
        render();
    }

    protected void keyPressed(int keyCode) {}

    protected void keyReleased(int keyCode) {}

    protected void keyRepeated(int keyCode) {}

    protected void pointerPressed(int x, int y) {}

    protected void pointerReleased(int x, int y) {}

    protected void pointerDragged(int x, int y) {}

    protected void showNotify() {}

    protected void hideNotify() {}

    protected void sizeChanged(int w, int h) {}

    protected boolean traverse(int dir, int viewportWidth, int viewportHeight, int[] visRect_inout) {
        return false;
    }

    protected void traverseOut() {}

    private void render() {
        final ImageView v = view;
        if (v == null) return;
        Host.events.post(new Runnable() {
            public void run() {
                int w = Math.max(1, getPrefContentWidth(-1));
                int h = Math.max(1, getPrefContentHeight(-1));
                if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) buffer = Image.createImage(w, h);
                Graphics g = buffer.getGraphics();
                paint(g, w, h);
                final android.graphics.Bitmap copy = buffer.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                Host.runOnUi(new Runnable() {
                    public void run() { v.setImageBitmap(copy); }
                });
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    View createView(Context ctx) {
        view = new ImageView(ctx);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_START);
        view.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                float scale = v.getWidth() / (float) Math.max(1, getPrefContentWidth(-1));
                final int x = (int) (e.getX() / Math.max(0.01f, scale));
                final int y = (int) (e.getY() / Math.max(0.01f, scale));
                final int a = e.getActionMasked();
                Host.events.post(new Runnable() {
                    public void run() {
                        if (a == MotionEvent.ACTION_DOWN) pointerPressed(x, y);
                        else if (a == MotionEvent.ACTION_MOVE) pointerDragged(x, y);
                        else if (a == MotionEvent.ACTION_UP) pointerReleased(x, y);
                    }
                });
                return true;
            }
        });
        render();
        return view;
    }
}
