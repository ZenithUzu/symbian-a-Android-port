package javax.microedition.lcdui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Vector;

import j2a.core.Host;

/** Small helpers to build the native Android views of MIDP Screens. */
final class Ui {
    static final int BG = Color.rgb(24, 26, 30);
    static final int FG = Color.rgb(235, 235, 235);
    static final int ACCENT = Color.rgb(90, 150, 255);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextColor(FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** Scales a J2ME image the same way the game screen is scaled (nearest neighbour). */
    static ImageView image(Context c, Image img) {
        ImageView v = new ImageView(c);
        if (img == null) return v;
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int scale = Math.max(1, Math.min(4, screenW / Math.max(1, Host.cfg.screenWidth)));
        Bitmap b = img.bitmap;
        if (scale > 1) b = Bitmap.createScaledBitmap(b, b.getWidth() * scale, b.getHeight() * scale, false);
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setFilterBitmap(false);
        v.setImageDrawable(d);
        v.setAdjustViewBounds(true);
        return v;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static View commandBar(Context c, final Displayable owner, Vector<Command> cmds) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        for (int i = 0; i < cmds.size(); i++) {
            final Command cmd = cmds.elementAt(i);
            Button b = new Button(c);
            b.setText(cmd.getLabel());
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Host.fireCommand(owner, cmd); }
            });
            bar.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }
}
