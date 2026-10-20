package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;

public class Spacer extends Item {
    private int minW, minH;

    public Spacer(int minWidth, int minHeight) {
        super(null);
        setMinimumSize(minWidth, minHeight);
    }

    public void setMinimumSize(int minWidth, int minHeight) {
        if (minWidth < 0 || minHeight < 0) throw new IllegalArgumentException();
        minW = minWidth;
        minH = minHeight;
    }

    @Override
    public void addCommand(Command cmd) { throw new IllegalStateException(); }

    @Override
    public void setDefaultCommand(Command cmd) { throw new IllegalStateException(); }

    @Override
    public void setLabel(String label) { throw new IllegalStateException(); }

    @Override
    View createView(Context ctx) {
        View v = new View(ctx);
        v.setMinimumWidth(Ui.dp(ctx, minW));
        v.setMinimumHeight(Ui.dp(ctx, minH));
        return v;
    }
}
