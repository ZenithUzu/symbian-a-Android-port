package javax.microedition.lcdui;

import java.util.Vector;

import j2a.core.Host;

public abstract class Displayable {
    String title;
    Ticker ticker;
    final Vector<Command> commands = new Vector<Command>();
    CommandListener listener;
    volatile boolean shown;

    Displayable() {}

    public String getTitle() { return title; }

    public void setTitle(String s) {
        title = s;
        changed();
    }

    public Ticker getTicker() { return ticker; }

    public void setTicker(Ticker t) {
        ticker = t;
        changed();
    }

    public boolean isShown() { return shown; }

    public void addCommand(Command cmd) {
        if (cmd == null) throw new NullPointerException();
        synchronized (commands) {
            if (commands.contains(cmd)) return;
            // keep commands ordered by priority (lower value = more important)
            int i = 0;
            while (i < commands.size() && commands.elementAt(i).getPriority() <= cmd.getPriority()) i++;
            commands.insertElementAt(cmd, i);
        }
        Host.commandsChanged(this);
    }

    public void removeCommand(Command cmd) {
        synchronized (commands) { commands.removeElement(cmd); }
        Host.commandsChanged(this);
    }

    public void setCommandListener(CommandListener l) { listener = l; }

    public int getWidth() { return Host.cfg.screenWidth; }

    public int getHeight() { return Host.cfg.screenHeight; }

    protected void sizeChanged(int w, int h) {}

    void changed() {
        Host.commandsChanged(this);
    }

    void showNotifyImpl() {}

    void hideNotifyImpl() {}

    // ------------------------------------------------- j2a host hooks (not MIDP)

    public final void j2aSetShown(boolean s) { shown = s; }

    public final void j2aShowNotify() { showNotifyImpl(); }

    public final void j2aHideNotify() { hideNotifyImpl(); }

    public void j2aFireCommand(Command c) {
        CommandListener l = listener;
        if (l != null) l.commandAction(c, this);
    }

    public Vector<Command> j2aCommands() {
        synchronized (commands) { return new Vector<Command>(commands); }
    }

    /** Command mapped to the right soft key / Android BACK. */
    public Command j2aBackCommand() {
        synchronized (commands) {
            for (int i = 0; i < commands.size(); i++) {
                if (commands.elementAt(i).isBackLike()) return commands.elementAt(i);
            }
        }
        return null;
    }

    /** Commands mapped to the left soft key (more than one = "Opciones" menu). */
    public Vector<Command> j2aLeftCommands() {
        Command back = j2aBackCommand();
        Vector<Command> v = new Vector<Command>();
        synchronized (commands) {
            for (int i = 0; i < commands.size(); i++) {
                if (commands.elementAt(i) != back) v.addElement(commands.elementAt(i));
            }
        }
        return v;
    }
}
