package javax.microedition.lcdui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Vector;

import j2a.core.Host;

public class Alert extends Screen {
    public static final int FOREVER = -2;
    public static final Command DISMISS_COMMAND = new Command("OK", Command.OK, 0);

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private String text;
    private Image image;
    private AlertType type;
    private int timeout = getDefaultTimeout();
    private Gauge indicator;
    Displayable next;

    public Alert(String title) {
        this(title, null, null, null);
    }

    public Alert(String title, String alertText, Image alertImage, AlertType alertType) {
        this.title = title;
        this.text = alertText;
        this.image = alertImage;
        this.type = alertType;
    }

    public int getDefaultTimeout() { return 2000; }

    public int getTimeout() { return timeout; }

    public void setTimeout(int time) {
        if (time <= 0 && time != FOREVER) throw new IllegalArgumentException();
        timeout = time;
    }

    public AlertType getType() { return type; }

    public void setType(AlertType type) { this.type = type; }

    public String getString() { return text; }

    public void setString(String str) {
        text = str;
        changed();
    }

    public Image getImage() { return image; }

    public void setImage(Image img) {
        image = img;
        changed();
    }

    public void setIndicator(Gauge indicator) {
        this.indicator = indicator;
        changed();
    }

    public Gauge getIndicator() { return indicator; }

    @Override
    Vector<Command> barCommands() {
        Vector<Command> v = j2aCommands();
        if (v.isEmpty()) v.addElement(DISMISS_COMMAND);
        return v;
    }

    @Override
    View createContent(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout l = Ui.vertical(ctx);
        int p = Ui.dp(ctx, 16);
        l.setPadding(p, p, p, p);
        if (image != null) l.addView(Ui.image(ctx, image));
        if (text != null) {
            TextView t = Ui.text(ctx, text, 17, false);
            t.setPadding(0, p / 2, 0, p / 2);
            l.addView(t);
        }
        if (indicator != null) l.addView(indicator.createView(ctx));
        sv.addView(l);
        return sv;
    }

    @Override
    public void j2aFireCommand(Command c) {
        if (listener == null) {
            if (next != null) Host.setCurrent(next);
            return;
        }
        listener.commandAction(c, this);
    }

    /** Starts the automatic dismissal timer. */
    public void j2aStartTimer() {
        if (timeout == FOREVER || !commands.isEmpty()) return;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (Host.getCurrent() == Alert.this) Host.fireCommand(Alert.this, DISMISS_COMMAND);
            }
        }, timeout);
    }
}
