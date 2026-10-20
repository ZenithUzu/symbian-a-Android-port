package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

import java.util.Vector;

import j2a.core.Host;

public class List extends Screen implements Choice {
    public static final Command SELECT_COMMAND = new Command("", Command.SCREEN, 0);

    private final ChoiceModel model;
    private Command selectCommand = SELECT_COMMAND;

    public List(String title, int listType) {
        this(title, listType, new String[0], null);
    }

    public List(String title, int listType, String[] stringElements, Image[] imageElements) {
        if (listType != IMPLICIT && listType != EXCLUSIVE && listType != MULTIPLE)
            throw new IllegalArgumentException();
        this.title = title;
        model = new ChoiceModel(listType);
        for (int i = 0; i < stringElements.length; i++)
            model.insert(i, stringElements[i], imageElements == null ? null : imageElements[i]);
    }

    public void setSelectCommand(Command command) {
        selectCommand = command;
        if (command != null && command != SELECT_COMMAND) addCommand(command);
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
        changed();
        return i;
    }

    public void insert(int i, String s, Image img) {
        model.insert(i, s, img);
        changed();
    }

    public void delete(int i) {
        model.delete(i);
        changed();
    }

    public void deleteAll() {
        model.deleteAll();
        changed();
    }

    public void set(int i, String s, Image img) {
        model.set(i, s, img);
        changed();
    }

    public boolean isSelected(int i) { return model.isSelected(i); }

    public int getSelectedIndex() { return model.getSelectedIndex(); }

    public int getSelectedFlags(boolean[] a) { return model.getSelectedFlags(a); }

    public void setSelectedIndex(int i, boolean sel) { model.setSelectedIndex(i, sel); }

    public void setSelectedFlags(boolean[] a) { model.setSelectedFlags(a); }

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
    Vector<Command> barCommands() {
        Vector<Command> v = j2aCommands();
        v.removeElement(SELECT_COMMAND);
        return v;
    }

    @Override
    View createContent(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        sv.addView(model.createView(ctx, new ChoiceModel.Listener() {
            public void selectionChanged(int index, boolean byUser) {
                if (model.type == IMPLICIT && selectCommand != null) Host.fireCommand(List.this, selectCommand);
            }
        }));
        return sv;
    }
}
