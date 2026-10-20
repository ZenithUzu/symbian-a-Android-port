package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;

public class ChoiceGroup extends Item implements Choice {
    private final ChoiceModel model;

    public ChoiceGroup(String label, int choiceType) {
        this(label, choiceType, new String[0], null);
    }

    public ChoiceGroup(String label, int choiceType, String[] stringElements, Image[] imageElements) {
        super(label);
        if (choiceType != EXCLUSIVE && choiceType != MULTIPLE && choiceType != POPUP)
            throw new IllegalArgumentException();
        model = new ChoiceModel(choiceType);
        for (int i = 0; i < stringElements.length; i++)
            model.insert(i, stringElements[i], imageElements == null ? null : imageElements[i]);
    }

    public int size() { return model.size(); }

    public String getString(int i) { synchronized (model) { return model.strings.elementAt(i); } }

    public Image getImage(int i) { synchronized (model) { return model.images.elementAt(i); } }

    public int append(String s, Image img) {
        int i;
        synchronized (model) {
            i = model.size();
            model.insert(i, s, img);
        }
        invalidate();
        return i;
    }

    public void insert(int i, String s, Image img) {
        model.insert(i, s, img);
        invalidate();
    }

    public void delete(int i) {
        model.delete(i);
        invalidate();
    }

    public void deleteAll() {
        model.deleteAll();
        invalidate();
    }

    public void set(int i, String s, Image img) {
        model.set(i, s, img);
        invalidate();
    }

    public boolean isSelected(int i) { return model.isSelected(i); }

    public int getSelectedIndex() { return model.getSelectedIndex(); }

    public int getSelectedFlags(boolean[] a) { return model.getSelectedFlags(a); }

    public void setSelectedIndex(int i, boolean sel) {
        model.setSelectedIndex(i, sel);
        invalidate();
    }

    public void setSelectedFlags(boolean[] a) {
        model.setSelectedFlags(a);
        invalidate();
    }

    public void setFitPolicy(int p) { model.fitPolicy = p; }

    public int getFitPolicy() { return model.fitPolicy; }

    public void setFont(int i, Font f) { synchronized (model) { model.fonts.setElementAt(f, i); } }

    public Font getFont(int i) {
        synchronized (model) {
            Font f = model.fonts.elementAt(i);
            return f == null ? Font.getDefaultFont() : f;
        }
    }

    @Override
    View createView(Context ctx) {
        return model.createView(ctx, new ChoiceModel.Listener() {
            public void selectionChanged(int index, boolean byUser) {
                notifyStateChanged();
                invalidate();
            }
        });
    }
}
