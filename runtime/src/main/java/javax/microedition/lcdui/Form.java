package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Vector;

import j2a.core.Host;

public class Form extends Screen {
    private final Vector<Item> items = new Vector<Item>();
    private ItemStateListener stateListener;

    public Form(String title) {
        this.title = title;
    }

    public Form(String title, Item[] items) {
        this.title = title;
        if (items != null) for (int i = 0; i < items.length; i++) append(items[i]);
    }

    public int append(Item item) {
        if (item == null) throw new NullPointerException();
        if (item.owner != null && item.owner != this) throw new IllegalStateException();
        item.owner = this;
        int i;
        synchronized (items) {
            items.addElement(item);
            i = items.size() - 1;
        }
        changed();
        return i;
    }

    public int append(String str) {
        return append(new StringItem(null, str));
    }

    public int append(Image img) {
        return append(new ImageItem(null, img, ImageItem.LAYOUT_DEFAULT, null));
    }

    public void insert(int itemNum, Item item) {
        if (item.owner != null && item.owner != this) throw new IllegalStateException();
        item.owner = this;
        synchronized (items) { items.insertElementAt(item, itemNum); }
        changed();
    }

    public void delete(int itemNum) {
        synchronized (items) {
            Item it = items.elementAt(itemNum);
            it.owner = null;
            items.removeElementAt(itemNum);
        }
        changed();
    }

    public void deleteAll() {
        synchronized (items) {
            for (int i = 0; i < items.size(); i++) items.elementAt(i).owner = null;
            items.removeAllElements();
        }
        changed();
    }

    public void set(int itemNum, Item item) {
        synchronized (items) {
            items.elementAt(itemNum).owner = null;
            item.owner = this;
            items.setElementAt(item, itemNum);
        }
        changed();
    }

    public Item get(int itemNum) {
        synchronized (items) { return items.elementAt(itemNum); }
    }

    public int size() {
        synchronized (items) { return items.size(); }
    }

    public void setItemStateListener(ItemStateListener l) { stateListener = l; }

    void fireStateChanged(final Item item) {
        final ItemStateListener l = stateListener;
        if (l == null) return;
        Host.events.post(new Runnable() { public void run() { l.itemStateChanged(item); } });
    }

    @Override
    View createContent(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout l = Ui.vertical(ctx);
        int p = Ui.dp(ctx, 10);
        l.setPadding(p, p / 2, p, p);
        Item[] snapshot;
        synchronized (items) {
            snapshot = new Item[items.size()];
            items.copyInto(snapshot);
        }
        for (int i = 0; i < snapshot.length; i++) {
            final Item it = snapshot[i];
            if (it.label != null && it.label.length() > 0) {
                TextView t = Ui.text(ctx, it.label, 14, true);
                t.setPadding(0, p, 0, 0);
                l.addView(t);
            }
            l.addView(it.createView(ctx));
            Vector<Command> ic = new Vector<Command>(it.commands);
            if (it.defaultCommand != null && it instanceof StringItem) ic.removeElement(it.defaultCommand);
            if (!ic.isEmpty()) {
                LinearLayout bar = new LinearLayout(ctx);
                for (int k = 0; k < ic.size(); k++) {
                    final Command c = ic.elementAt(k);
                    android.widget.Button b = new android.widget.Button(ctx);
                    b.setAllCaps(false);
                    b.setText(c.getLabel());
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { it.fireItemCommand(c); }
                    });
                    bar.addView(b);
                }
                l.addView(bar);
            }
        }
        sv.addView(l);
        return sv;
    }
}
