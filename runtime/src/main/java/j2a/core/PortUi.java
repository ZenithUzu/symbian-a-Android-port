package j2a.core;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Console dashboard shared by the port menu and its settings panels. No external UI dependency. */
final class PortUi {
    private static final int GOLD = Color.rgb(242, 191, 91);
    private static final int INK = Color.rgb(231, 237, 247);
    private static final int MUTED = Color.rgb(157, 175, 201);

    private static int dp(Activity a, int n) {
        return Math.round(n * a.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable panel(int top, int bottom, int border, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setCornerRadius(radius);
        d.setStroke(1, border);
        return d;
    }

    private static TextView text(Activity a, String label, int size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static void show(Activity a, String title, String[] labels, int checked,
                     java.util.function.IntConsumer pick, Runnable back, boolean dashboard) {
        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 12));
        root.setBackground(panel(0xff202d45, 0xff0b1323, 0xff556682, dp(a, 18)));
        root.addView(text(a, "J2A  /  PLAYER CONSOLE", 11, GOLD, true));
        TextView heading = text(a, title, 23, INK, true);
        heading.setPadding(0, dp(a, 6), 0, dp(a, 4));
        root.addView(heading);
        TextView subtitle = text(a, dashboard ? "PANTALLA · CONTROLES · SONIDO" : "AJUSTES DEL JUEGO  /  Se guardan automáticamente", 11, MUTED, false);
        subtitle.setPadding(0, 0, 0, dp(a, 12));
        root.addView(subtitle);
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            if (labels[i].startsWith("# ")) {
                TextView section = text(a, labels[i].substring(2), 12, GOLD, true);
                section.setPadding(dp(a, 3), dp(a, 16), 0, dp(a, 7));
                body.addView(section);
                continue;
            }
            LinearLayout card = new LinearLayout(a);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(a, 14), dp(a, 12), dp(a, 12), dp(a, 12));
            card.setMinimumHeight(dp(a, 54));
            card.setBackground(panel(i == checked ? 0xff455032 : 0xff2b3b56,
                    i == checked ? 0xff283a2e : 0xff18263d, i == checked ? GOLD : 0xff41516c, dp(a, 9)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(a, 7);
            body.addView(card, cp);
            TextView marker = text(a, i == checked ? "●" : dashboard ? "◆" : "◇", 17, GOLD, true);
            card.addView(marker, new LinearLayout.LayoutParams(dp(a, 30), -2));
            String label = labels[i];
            int split = label.indexOf(": ");
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(text(a, split >= 0 ? label.substring(0, split) : label, 15, INK, true));
            if (split >= 0) words.addView(text(a, label.substring(split + 2), 13, MUTED, false));
            card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(text(a, "›", 24, GOLD, false));
            card.setFocusable(true);
            card.setClickable(true);
            card.setContentDescription(label);
            card.setOnFocusChangeListener((v, focused) -> v.setAlpha(focused ? 1f : .9f));
            card.setOnClickListener(v -> { dialog.dismiss(); pick.accept(index); });
        }
        TextView close = text(a, back == null ? "▶  VOLVER AL JUEGO" : "‹  VOLVER", 13, GOLD, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(a, 16), 0, dp(a, 12));
        close.setFocusable(true);
        close.setOnClickListener(v -> { dialog.dismiss(); if (back != null) back.run(); });
        root.addView(close);
        dialog.setContentView(root);
        dialog.setOnCancelListener(d -> { if (back != null) back.run(); });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = .72f;
            w.setAttributes(lp);
        }
        dialog.show();
        if (w != null) {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            w.setLayout(Math.min(dp(a, 620), (int)(metrics.widthPixels * .94f)), (int)(metrics.heightPixels * .88f));
        }
    }
}
