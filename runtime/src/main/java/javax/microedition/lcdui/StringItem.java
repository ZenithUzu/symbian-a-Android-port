package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class StringItem extends Item {
    private String text;
    private final int appearance;
    private Font font;

    public StringItem(String label, String text) {
        this(label, text, PLAIN);
    }

    public StringItem(String label, String text, int appearanceMode) {
        super(label);
        this.text = text;
        this.appearance = appearanceMode;
    }

    public String getText() { return text; }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public int getAppearanceMode() { return appearance; }

    public void setFont(Font font) { this.font = font; }

    public Font getFont() { return font == null ? Font.getDefaultFont() : font; }

    @Override
    View createView(Context ctx) {
        if ((appearance == BUTTON || appearance == HYPERLINK) && defaultCommand != null) {
            Button b = new Button(ctx);
            b.setAllCaps(false);
            b.setText(text);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { fireItemCommand(defaultCommand); }
            });
            return b;
        }
        TextView t = Ui.text(ctx, text, 16, font != null && font.isBold());
        return t;
    }
}
