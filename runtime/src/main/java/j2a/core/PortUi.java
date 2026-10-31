package j2a.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Compact settings sheet shared by game menus. */
final class PortUi {
    private PortUi() {}
    private static int dp(Activity a, int n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
    private static TextView text(Activity a, String label, int sp, int color) {
        TextView v = new TextView(a); v.setText(label); v.setTextSize(sp); v.setTextColor(color); return v;
    }
    static void show(Activity a, String title, String[] labels, int checked,
                     java.util.function.IntConsumer pick, Runnable back, boolean dashboard) {
        boolean dark = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? 0xff202124 : 0xfffafafa;
        int ink = dark ? 0xffedf0f3 : 0xff202124;
        int muted = dark ? 0xffaeb5bf : 0xff59616c;
        int accent = dark ? 0xffa8c7fa : 0xff215da8;
        int line = dark ? 0xff393c42 : 0xffe5e7eb;
        if (android.os.Build.VERSION.SDK_INT >= 31) accent = a.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
        final int highlight = accent;
        Dialog dialog = new Dialog(a); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable(); background.setColor(surface); background.setCornerRadius(dp(a, 22)); root.setBackground(background);
        LinearLayout header = new LinearLayout(a); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(a, 12), dp(a, 8), dp(a, 8), dp(a, 8));
        TextView heading = text(a, title, 20, ink); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setPadding(dp(a, 12), 0, 0, 0); heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        TextView close = text(a, back == null ? "×" : "‹", 28, muted); close.setGravity(Gravity.CENTER); close.setContentDescription(back == null ? "Cerrar ajustes" : "Volver"); close.setFocusable(true);
        header.addView(close, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        close.setOnClickListener(v -> { dialog.dismiss(); if (back != null) back.run(); }); root.addView(header);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); a.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        android.graphics.Rect visible = new android.graphics.Rect(); a.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        ScrollView scroll = new ScrollView(a) {
            @Override protected void onMeasure(int w, int h) {
                android.graphics.Rect current = new android.graphics.Rect();
                a.getWindow().getDecorView().getWindowVisibleDisplayFrame(current);
                int available = current.height() > 0 ? current.height() : a.getResources().getDisplayMetrics().heightPixels;
                int limit = Math.max(dp(a, 48), (int)(available * .84f) - dp(a, 64));
                if (View.MeasureSpec.getMode(h) != View.MeasureSpec.UNSPECIFIED) limit = Math.min(limit, View.MeasureSpec.getSize(h));
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setClipToPadding(false); scroll.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 12));
        LinearLayout body = new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body); root.addView(scroll);
        for (int i = 0; i < labels.length; i++) {
            final int index = i; String label = labels[i];
            if (label.startsWith("# ")) { TextView section = text(a, label.substring(2), 12, muted); section.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 5)); body.addView(section); continue; }
            LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(a, 52)); row.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
            GradientDrawable fill = new GradientDrawable(); fill.setColor(i == checked ? (dark ? 0xff303b4c : 0xffe7effa) : surface); fill.setCornerRadius(dp(a, 12));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000), fill, null));
            int split = label.indexOf(": "); LinearLayout words = new LinearLayout(a); words.setOrientation(LinearLayout.VERTICAL);
            words.addView(text(a, split < 0 ? label : label.substring(0, split), 16, ink));
            if (split >= 0) { TextView value = text(a, label.substring(split + 2), 13, muted); value.setPadding(0, dp(a, 3), 0, 0); words.addView(value); }
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1)); if (i == checked) row.addView(text(a, "✓", 20, highlight));
            row.setFocusable(true); row.setClickable(true); row.setContentDescription(label + (i == checked ? ", seleccionado" : ""));
            row.setOnClickListener(v -> { dialog.dismiss(); pick.accept(index); }); row.setOnFocusChangeListener((v, focused) -> fill.setStroke(focused ? dp(a, 2) : 0, highlight)); body.addView(row);
            if (dashboard && i < labels.length - 1) { View divider = new View(a); divider.setBackgroundColor(line); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(a, 1)); p.setMargins(dp(a, 16), 0, dp(a, 16), 0); body.addView(divider, p); }
        }
        dialog.setContentView(root); dialog.setOnCancelListener(d -> { if (back != null) back.run(); }); Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams p = window.getAttributes(); p.dimAmount = .45f; window.setAttributes(p); }
        dialog.show(); if (window != null) window.setLayout(Math.min(dp(a, 440), (int)(metrics.widthPixels * .92f)), WindowManager.LayoutParams.WRAP_CONTENT);
    }
}

