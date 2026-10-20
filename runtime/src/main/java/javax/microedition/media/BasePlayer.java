package javax.microedition.media;

import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.media.control.VolumeControl;

import j2a.core.Host;

/** State machine, listeners and VolumeControl shared by all players. */
abstract class BasePlayer implements Player, VolumeControl {
    private static final ExecutorService events = Executors.newSingleThreadExecutor();

    protected volatile int state = UNREALIZED;
    protected int loopCount = 1;
    protected final String contentType;
    private final Vector<PlayerListener> listeners = new Vector<PlayerListener>();
    private int level = 100;
    private boolean muted;
    private TimeBase timeBase;
    boolean resumeAfterPause;

    BasePlayer(String contentType) {
        this.contentType = contentType;
        Manager.register(this);
    }

    abstract void doRealize() throws MediaException;

    abstract void doPrefetch() throws MediaException;

    abstract void doStart() throws MediaException;

    abstract void doStop();

    abstract void doDeallocate();

    abstract void applyVolume(float v);

    float volume() {
        if (muted || !Host.cfg.sound) return 0f;
        return level / 100f;
    }

    private void checkClosed() {
        if (state == CLOSED) throw new IllegalStateException("Player is closed");
    }

    public synchronized void realize() throws MediaException {
        checkClosed();
        if (state == UNREALIZED) {
            doRealize();
            state = REALIZED;
        }
    }

    public synchronized void prefetch() throws MediaException {
        checkClosed();
        realize();
        if (state == REALIZED) {
            doPrefetch();
            state = PREFETCHED;
        }
    }

    public synchronized void start() throws MediaException {
        checkClosed();
        prefetch();
        if (state != STARTED) {
            if (Host.isPaused()) {
                resumeAfterPause = true; // started while in background: play on resume
            } else {
                applyVolume(volume());
                doStart();
            }
            state = STARTED;
            fire(PlayerListener.STARTED, Long.valueOf(getMediaTime()));
        }
    }

    public synchronized void stop() throws MediaException {
        checkClosed();
        if (state == STARTED) {
            doStop();
            state = PREFETCHED;
            fire(PlayerListener.STOPPED, Long.valueOf(getMediaTime()));
        }
    }

    public synchronized void deallocate() {
        checkClosed();
        if (state == STARTED) {
            doStop();
            state = PREFETCHED;
        }
        if (state == PREFETCHED) {
            doDeallocate();
            state = REALIZED;
        }
    }

    public synchronized void close() {
        if (state == CLOSED) return;
        if (state == STARTED) doStop();
        doDeallocate();
        state = CLOSED;
        Manager.unregister(this);
        fire(PlayerListener.CLOSED, null);
    }

    /** Called by subclasses when playback finished. */
    void endOfMedia() {
        synchronized (this) {
            if (state != STARTED) return;
            state = PREFETCHED;
        }
        fire(PlayerListener.END_OF_MEDIA, Long.valueOf(getMediaTime()));
    }

    void fire(final String event, final Object data) {
        if (listeners.isEmpty()) return;
        final PlayerListener[] ls = new PlayerListener[listeners.size()];
        listeners.copyInto(ls);
        events.execute(new Runnable() {
            public void run() {
                for (int i = 0; i < ls.length; i++) {
                    try { ls[i].playerUpdate(BasePlayer.this, event, data); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void setTimeBase(TimeBase master) throws MediaException {
        timeBase = master;
    }

    public TimeBase getTimeBase() {
        return timeBase != null ? timeBase : Manager.getSystemTimeBase();
    }

    public int getState() { return state; }

    public String getContentType() { return contentType; }

    public void setLoopCount(int count) {
        checkClosed();
        if (count == 0) throw new IllegalArgumentException();
        if (state == STARTED) throw new IllegalStateException();
        loopCount = count;
    }

    public void addPlayerListener(PlayerListener l) {
        checkClosed();
        if (l != null && !listeners.contains(l)) listeners.addElement(l);
    }

    public void removePlayerListener(PlayerListener l) {
        listeners.removeElement(l);
    }

    public Control[] getControls() {
        return new Control[]{this};
    }

    public Control getControl(String type) {
        if (type == null) return null;
        if (type.endsWith("VolumeControl")) return this;
        return null;
    }

    // ------------------------------------------------------------ VolumeControl

    public void setMute(boolean mute) {
        muted = mute;
        applyVolume(volume());
        fire(PlayerListener.VOLUME_CHANGED, this);
    }

    public boolean isMuted() { return muted; }

    public int setLevel(int l) {
        level = Math.max(0, Math.min(100, l));
        applyVolume(volume());
        fire(PlayerListener.VOLUME_CHANGED, this);
        return level;
    }

    public int getLevel() { return level; }

    // ------------------------------------------------------- activity pausing

    synchronized void j2aPause() {
        if (state == STARTED) {
            resumeAfterPause = true;
            doStop();
        }
    }

    synchronized void j2aResume() {
        if (resumeAfterPause && state == STARTED) {
            resumeAfterPause = false;
            try {
                applyVolume(volume());
                doStart();
            } catch (MediaException ignored) {}
        }
    }
}
