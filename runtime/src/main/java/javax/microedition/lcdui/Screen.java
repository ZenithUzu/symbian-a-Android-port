package javax.microedition.lcdui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Vector;

/**
 * Base of the high-level MIDP screens. They are rendered with native Android
 * views (much more usable on a touch screen than the original phone UI).
 */
public abstract class Screen extends Displayable {

    Screen() {}

    abstract View createContent(Context ctx);

    Vector<Command> barCommands() { return j2aCommands(); }

    public View j2aCreateView(Context ctx) {
        LinearLayout root = Ui.vertical(ctx);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(ctx, 10);
        if (title != null && title.length() > 0) {
            TextView t = Ui.text(ctx, title, 19, true);
            t.setPadding(p, p, p, p / 2);
            t.setTextColor(Ui.ACCENT);
            root.addView(t);
        }
        if (ticker != null) {
            TextView t = Ui.text(ctx, ticker.getString(), 14, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            t.setMarqueeRepeatLimit(-1);
            t.setSelected(true);
            t.setPadding(p, 0, p, p / 2);
            root.addView(t);
        }
        View content = createContent(ctx);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        Vector<Command> cmds = barCommands();
        if (!cmds.isEmpty()) root.addView(Ui.commandBar(ctx, this, cmds));
        return root;
    }
}
