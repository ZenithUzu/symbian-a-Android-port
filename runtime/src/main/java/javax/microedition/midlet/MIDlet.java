package javax.microedition.midlet;

import j2a.core.Host;

public abstract class MIDlet {

    protected MIDlet() {
        Host.registerMIDlet(this);
    }

    protected abstract void startApp() throws MIDletStateChangeException;

    protected abstract void pauseApp();

    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public final void notifyDestroyed() {
        Host.exit();
    }

    public final void notifyPaused() {}

    public final void resumeRequest() {}

    public final String getAppProperty(String key) {
        return Host.getAppProperty(key);
    }

    public final boolean platformRequest(String url) throws javax.microedition.io.ConnectionNotFoundException {
        return Host.platformRequest(url);
    }

    public final int checkPermission(String permission) {
        return 1;
    }
}
