package j2a.core;

import android.util.Log;

import java.util.LinkedList;

import javax.microedition.lcdui.Canvas;

/**
 * The single J2ME "event thread". MIDP guarantees that paint(), key/pointer
 * callbacks, commandAction() and callSerially() runnables never run at the
 * same time, so all of them go through this queue.
 */
public final class EventQueue implements Runnable {
    private static final String TAG = "j2a";
    private final Object lock = new Object();
    private final LinkedList<Runnable> queue = new LinkedList<Runnable>();
    private Canvas repaintTarget;
    private boolean repaintPending;
    private boolean painting;
    private volatile boolean running = true;
    private Thread thread;

    void start() {
        thread = new Thread(this, "J2ME-EventThread");
        thread.start();
    }

    void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
    }

    public boolean isEventThread() {
        return Thread.currentThread() == thread;
    }

    public void post(Runnable r) {
        synchronized (lock) {
            queue.addLast(r);
            lock.notifyAll();
        }
    }

    /** Coalesces repaint requests: only one paint is ever pending. */
    public void postRepaint(Canvas c) {
        synchronized (lock) {
            repaintTarget = c;
            repaintPending = true;
            lock.notifyAll();
        }
    }

    /** Blocks until the pending repaint (if any) has been painted. */
    public void serviceRepaints() {
        if (isEventThread()) {
            performRepaint();
            return;
        }
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 1000;
            while (running && (repaintPending || painting)) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { return; }
            }
        }
    }

    public void run() {
        // input + paint thread: above normal game threads so key/touch events are handled promptly
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        while (running) {
            Runnable r = null;
            synchronized (lock) {
                while (running && queue.isEmpty() && !repaintPending) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                if (!queue.isEmpty()) r = queue.removeFirst();
            }
            try {
                if (r != null) r.run();
                else performRepaint();
            } catch (Throwable t) {
                Log.e(TAG, "Uncaught exception in J2ME event thread", t);
            }
        }
    }

    private void performRepaint() {
        Canvas c;
        synchronized (lock) {
            c = repaintTarget;
            repaintTarget = null;
            repaintPending = false;
            painting = c != null;
        }
        try {
            if (c != null && c.isShown()) Host.paintCanvas(c);
        } finally {
            synchronized (lock) {
                painting = false;
                lock.notifyAll();
            }
        }
    }
}
