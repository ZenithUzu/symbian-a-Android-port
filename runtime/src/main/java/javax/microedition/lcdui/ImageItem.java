package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;

public class ImageItem extends Item {
    public static final int LAYOUT_DEFAULT = 0;
    public static final int LAYOUT_LEFT = 1;
    public static final int LAYOUT_RIGHT = 2;
    public static final int LAYOUT_CENTER = 3;
    public static final int LAYOUT_NEWLINE_BEFORE = 0x100;
    public static final int LAYOUT_NEWLINE_AFTER = 0x200;

    private Image image;
    private String altText;
    private final int appearance;

    public ImageItem(String label, Image img, int layout, String altText) {
        this(label, img, layout, altText, PLAIN);
    }

    public ImageItem(String label, Image image, int layout, String altText, int appearanceMode) {
        super(label);
        this.image = image;
        this.layout = layout;
        this.altText = altText;
        this.appearance = appearanceMode;
    }

    public Image getImage() { return image; }

    public void setImage(Image img) {
        image = img;
        invalidate();
    }

    public String getAltText() { return altText; }

    public void setAltText(String text) { altText = text; }

    public int getAppearanceMode() { return appearance; }

    @Override
    View createView(Context ctx) {
        View v = Ui.image(ctx, image);
        if (defaultCommand != null) {
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { fireItemCommand(defaultCommand); }
            });
        }
        return v;
    }
}
