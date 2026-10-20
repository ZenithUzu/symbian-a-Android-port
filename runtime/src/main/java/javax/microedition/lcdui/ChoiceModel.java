package javax.microedition.lcdui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.Vector;

/** Shared implementation of List and ChoiceGroup. */
final class ChoiceModel {
    interface Listener {
        void selectionChanged(int index, boolean byUser);
    }

    final int type;
    final Vector<String> strings = new Vector<String>();
    final Vector<Image> images = new Vector<Image>();
    final Vector<Boolean> selected = new Vector<Boolean>();
    final Vector<Font> fonts = new Vector<Font>();
    int fitPolicy;

    ChoiceModel(int type) {
        this.type = type;
    }

    synchronized int size() { return strings.size(); }

    synchronized void insert(int i, String s, Image img) {
        if (s == null) throw new NullPointerException();
        if (i < 0 || i > strings.size()) throw new IndexOutOfBoundsException();
        strings.insertElementAt(s, i);
        images.insertElementAt(img, i);
        fonts.insertElementAt(null, i);
        boolean sel = type != Choice.MULTIPLE && strings.size() == 1;
        selected.insertElementAt(sel, i);
    }

    synchronized void delete(int i) {
        check(i);
        boolean wasSel = selected.elementAt(i);
        strings.removeElementAt(i);
        images.removeElementAt(i);
        fonts.removeElementAt(i);
        selected.removeElementAt(i);
        if (wasSel && type != Choice.MULTIPLE && !strings.isEmpty()) selected.setElementAt(true, Math.min(i, strings.size() - 1));
    }

    synchronized void deleteAll() {
        strings.removeAllElements();
        images.removeAllElements();
        fonts.removeAllElements();
        selected.removeAllElements();
    }

    synchronized void set(int i, String s, Image img) {
        check(i);
        if (s == null) throw new NullPointerException();
        strings.setElementAt(s, i);
        images.setElementAt(img, i);
    }

    synchronized boolean isSelected(int i) {
        check(i);
        return selected.elementAt(i);
    }

    synchronized int getSelectedIndex() {
        if (type == Choice.MULTIPLE) return -1;
        for (int i = 0; i < selected.size(); i++) if (selected.elementAt(i)) return i;
        return -1;
    }

    synchronized int getSelectedFlags(boolean[] out) {
        int n = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = i < selected.size() && selected.elementAt(i);
            if (out[i]) n++;
        }
        return n;
    }

    synchronized void setSelectedIndex(int i, boolean sel) {
        check(i);
        if (type == Choice.MULTIPLE) {
            selected.setElementAt(sel, i);
        } else if (sel) {
            for (int k = 0; k < selected.size(); k++) selected.setElementAt(k == i, k);
        }
    }

    synchronized void setSelectedFlags(boolean[] flags) {
        if (type == Choice.MULTIPLE) {
            for (int i = 0; i < selected.size() && i < flags.length; i++) selected.setElementAt(flags[i], i);
        } else {
            for (int i = 0; i < flags.length && i < selected.size(); i++) {
                if (flags[i]) {
                    setSelectedIndex(i, true);
                    return;
                }
            }
            if (!selected.isEmpty()) setSelectedIndex(0, true);
        }
    }

    private void check(int i) {
        if (i < 0 || i >= strings.size()) throw new IndexOutOfBoundsException();
    }

    View createView(Context ctx, final Listener l) {
        LinearLayout box = Ui.vertical(ctx);
        int p = Ui.dp(ctx, 8);
        int n = size();
        for (int i = 0; i < n; i++) {
            final int index = i;
            String s;
            Image img;
            boolean sel;
            synchronized (this) {
                if (i >= strings.size()) break;
                s = strings.elementAt(i);
                img = images.elementAt(i);
                sel = selected.elementAt(i);
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(p, p, p, p);
            if (type == Choice.EXCLUSIVE || type == Choice.POPUP || type == Choice.MULTIPLE) {
                final CompoundButton cb = type == Choice.MULTIPLE ? new CheckBox(ctx) : new RadioButton(ctx);
                cb.setChecked(sel);
                cb.setText(s);
                cb.setTextColor(Ui.FG);
                cb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        setSelectedIndex(index, cb.isChecked() || type != Choice.MULTIPLE);
                        l.selectionChanged(index, true);
                    }
                });
                if (img != null) row.addView(Ui.image(ctx, img));
                row.addView(cb);
            } else {
                if (img != null) {
                    ImageView iv = Ui.image(ctx, img);
                    iv.setPadding(0, 0, p, 0);
                    row.addView(iv);
                }
                TextView t = Ui.text(ctx, s, 17, false);
                row.addView(t);
                row.setClickable(true);
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        setSelectedIndex(index, true);
                        l.selectionChanged(index, true);
                    }
                });
            }
            box.addView(row);
        }
        return box;
    }
}
