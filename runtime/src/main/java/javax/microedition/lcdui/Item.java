package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;

import java.util.Vector;

import j2a.core.Host;

public abstract class Item {
    public static final int LAYOUT_DEFAULT = 0;
    public static final int LAYOUT_LEFT = 1;
    public static final int LAYOUT_RIGHT = 2;
    public static final int LAYOUT_CENTER = 3;
    public static final int LAYOUT_TOP = 0x10;
    public static final int LAYOUT_BOTTOM = 0x20;
    public static final int LAYOUT_VCENTER = 0x30;
    public static final int LAYOUT_NEWLINE_BEFORE = 0x100;
    public static final int LAYOUT_NEWLINE_AFTER = 0x200;
    public static final int LAYOUT_SHRINK = 0x400;
    public static final int LAYOUT_EXPAND = 0x800;
    public static final int LAYOUT_VSHRINK = 0x1000;
    public static final int LAYOUT_VEXPAND = 0x2000;
    public static final int LAYOUT_2 = 0x4000;
    public static final int PLAIN = 0;
    public static final int HYPERLINK = 1;
    public static final int BUTTON = 2;

    String label;
    int layout;
    Screen owner;
    final Vector<Command> commands = new Vector<Command>();
    Command defaultCommand;
    ItemCommandListener itemCommandListener;
    int prefWidth = -1, prefHeight = -1;

    Item(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    public int getLayout() { return layout; }

    public void setLayout(int layout) { this.layout = layout; }

    public void addCommand(Command cmd) {
        if (cmd == null) throw new NullPointerException();
        if (!commands.contains(cmd)) commands.addElement(cmd);
        invalidate();
    }

    public void removeCommand(Command cmd) {
        commands.removeElement(cmd);
        if (defaultCommand == cmd) defaultCommand = null;
        invalidate();
    }

    public void setDefaultCommand(Command cmd) {
        defaultCommand = cmd;
        if (cmd != null && !commands.contains(cmd)) commands.addElement(cmd);
        invalidate();
    }

    public void setItemCommandListener(ItemCommandListener l) { itemCommandListener = l; }

    public int getPreferredWidth() { return prefWidth > 0 ? prefWidth : Host.cfg.screenWidth; }

    public int getPreferredHeight() { return prefHeight > 0 ? prefHeight : 20; }

    public void setPreferredSize(int width, int height) {
        prefWidth = width;
        prefHeight = height;
    }

    public int getMinimumWidth() { return 10; }

    public int getMinimumHeight() { return 10; }

    public void notifyStateChanged() {
        final Screen o = owner;
        if (o instanceof Form) ((Form) o).fireStateChanged(this);
    }

    void invalidate() {
        Screen o = owner;
        if (o != null) Host.refreshScreen(o);
    }

    void fireItemCommand(final Command c) {
        final ItemCommandListener l = itemCommandListener;
        if (l == null) return;
        Host.events.post(new Runnable() { public void run() { l.commandAction(c, Item.this); } });
    }

    abstract View createView(Context ctx);
}
