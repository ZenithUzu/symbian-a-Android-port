package javax.microedition.lcdui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

public class TextField extends Item {
    public static final int ANY = 0;
    public static final int EMAILADDR = 1;
    public static final int NUMERIC = 2;
    public static final int PHONENUMBER = 3;
    public static final int URL = 4;
    public static final int DECIMAL = 5;
    public static final int CONSTRAINT_MASK = 0xFFFF;
    public static final int PASSWORD = 0x10000;
    public static final int UNEDITABLE = 0x20000;
    public static final int SENSITIVE = 0x40000;
    public static final int NON_PREDICTIVE = 0x80000;
    public static final int INITIAL_CAPS_WORD = 0x100000;
    public static final int INITIAL_CAPS_SENTENCE = 0x200000;

    private final StringBuffer text = new StringBuffer();
    private int maxSize;
    private int constraints;

    public TextField(String label, String text, int maxSize, int constraints) {
        super(label);
        if (maxSize <= 0) throw new IllegalArgumentException();
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
        invalidate();
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
        invalidate();
    }

    public void insert(char[] data, int offset, int length, int position) {
        insert(new String(data, offset, length), position);
    }

    public void delete(int offset, int length) {
        synchronized (text) { text.delete(offset, offset + length); }
        invalidate();
    }

    public int getMaxSize() { return maxSize; }

    public int setMaxSize(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException();
        this.maxSize = maxSize;
        synchronized (text) { if (text.length() > maxSize) text.setLength(maxSize); }
        return maxSize;
    }

    public int size() { synchronized (text) { return text.length(); } }

    public int getCaretPosition() { return size(); }

    public void setConstraints(int constraints) { this.constraints = constraints; }

    public int getConstraints() { return constraints; }

    public void setInitialInputMode(String characterSubset) {}

    @Override
    View createView(Context ctx) {
        EditText e = createEditor(ctx, getString(), maxSize, constraints);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(Editable s) {
                synchronized (text) {
                    text.setLength(0);
                    text.append(s.toString());
                }
                notifyStateChanged();
            }
        });
        return e;
    }

    static EditText createEditor(Context ctx, String value, int maxSize, int constraints) {
        EditText e = new EditText(ctx);
        e.setTextColor(Ui.FG);
        e.setText(value);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxSize)});
        int type;
        switch (constraints & CONSTRAINT_MASK) {
            case NUMERIC: type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED; break;
            case DECIMAL: type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED; break;
            case PHONENUMBER: type = InputType.TYPE_CLASS_PHONE; break;
            case EMAILADDR: type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; break;
            case URL: type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI; break;
            default: type = InputType.TYPE_CLASS_TEXT; break;
        }
        if ((constraints & PASSWORD) != 0) {
            type = (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                    ? type | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }
        if ((constraints & INITIAL_CAPS_WORD) != 0) type |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        if ((constraints & INITIAL_CAPS_SENTENCE) != 0) type |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if ((constraints & NON_PREDICTIVE) != 0) type |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        e.setInputType(type);
        if ((constraints & UNEDITABLE) != 0) e.setEnabled(false);
        return e;
    }
}
