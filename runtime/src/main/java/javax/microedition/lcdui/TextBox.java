package javax.microedition.lcdui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

public class TextBox extends Screen {
    private final StringBuffer text = new StringBuffer();
    private int maxSize;
    private int constraints;

    public TextBox(String title, String text, int maxSize, int constraints) {
        if (maxSize <= 0) throw new IllegalArgumentException();
        this.title = title;
        this.maxSize = maxSize;
        this.constraints = constraints;
        if (text != null) this.text.append(text.length() > maxSize ? text.substring(0, maxSize) : text);
    }

    public String getString() { synchronized (text) { return text.toString(); } }

    public void setString(String s) {
        synchronized (text) {
            text.setLength(0);
            if (s != null) text.append(s);
        }
        changed();
    }

    public int getChars(char[] data) {
        String s = getString();
        s.getChars(0, s.length(), data, 0);
        return s.length();
    }

    public void setChars(char[] data, int offset, int length) {
        setString(data == null ? "" : new String(data, offset, length));
    }

    public void insert(String src, int position) {
        synchronized (text) { text.insert(Math.max(0, Math.min(position, text.length())), src); }
        changed();
    }

    public void insert(char[] data, int offset, int length, int position) {
        insert(new String(data, offset, length), position);
    }

    public void delete(int offset, int length) {
        synchronized (text) { text.delete(offset, offset + length); }
        changed();
    }

    public int getMaxSize() { return maxSize; }

    public int setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        return maxSize;
    }

    public int size() { synchronized (text) { return text.length(); } }

    public int getCaretPosition() { return size(); }

    public void setConstraints(int constraints) { this.constraints = constraints; }

    public int getConstraints() { return constraints; }

    public void setInitialInputMode(String characterSubset) {}

    @Override
    View createContent(Context ctx) {
        FrameLayout f = new FrameLayout(ctx);
        int p = Ui.dp(ctx, 12);
        f.setPadding(p, p, p, p);
        EditText e = TextField.createEditor(ctx, getString(), maxSize, constraints);
        e.setGravity(Gravity.TOP);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(Editable s) {
                synchronized (text) {
                    text.setLength(0);
                    text.append(s.toString());
                }
            }
        });
        f.addView(e, new FrameLayout.LayoutParams(-1, -2));
        e.requestFocus();
        return f;
    }
}
