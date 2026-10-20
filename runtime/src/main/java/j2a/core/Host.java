package j2a.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Screen;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletAccess;

/**
 * Glue between the J2ME API implementation and Android. Everything is static
 * because a J2ME application is a process-wide singleton anyway.
 */
public final class Host {
    private static final String TAG = "j2a";

    public static Config cfg = new Config();
    public static Properties appProperties = new Properties();
    static Activity activity;
    static ScreenView view;
    static GlScreen gl;
    static FrameLayout screenContainer;
    static final Handler ui = new Handler(Looper.getMainLooper());
    public static final EventQueue events = new EventQueue();
    private static final ExecutorService lifecycle = Executors.newSingleThreadExecutor();

    static MIDlet midlet;
    static volatile Displayable current;
    private static Image screenImage;
    private static Graphics screenGraphics;
    static volatile boolean paused;
    private static boolean started;

    private Host() {}

    // ------------------------------------------------------------------ setup

    static void init(Activity a, Config c, Properties props, GlScreen g, ScreenView v, FrameLayout container) {
        activity = a;
        cfg = c;
        appProperties = props;
        gl = g;
        view = v;
        screenContainer = container;
        if (screenImage != null) return; // activity re-created, process alive
        installCrashPolicy();
        screenImage = Image.createImage(cfg.screenWidth, cfg.screenHeight);
        screenGraphics = screenImage.getGraphics();
        screenGraphics.j2aTrack();
        AutoFit.start();
        System.setProperty("microedition.platform", cfg.platform);
        System.setProperty("microedition.locale", cfg.locale);
        System.setProperty("microedition.configuration", "CLDC-1.1");
        System.setProperty("microedition.profiles", "MIDP-2.0");
        System.setProperty("microedition.encoding", "UTF-8");
        System.setProperty("microedition.media.version", "1.1");
        System.setProperty("supports.mixing", "true");
        System.setProperty("microedition.commports", "");
        System.setProperty("microedition.hostname", "localhost");
        events.start();
    }

    /**
     * On a phone an uncaught exception only ends the thread that threw it; on Android it kills
     * the whole app. Games often let worker threads (loaders, sound, timers) die on purpose, so
     * only exceptions on the Android main thread keep the default (crash) behaviour.
     */
    private static void installCrashPolicy() {
        final Thread.UncaughtExceptionHandler android = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                if (t == Looper.getMainLooper().getThread() && android != null) {
                    android.uncaughtException(t, e);
                } else {
                    Log.e(TAG, "Thread '" + t.getName() + "' ended with an exception (ignored, as on a phone)", e);
                }
            }
        });
    }

    static void startMIDlet(final String className) {
        lifecycle.execute(new Runnable() {
            public void run() {
                try {
                    Class<?> cls = Class.forName(className);
                    midlet = (MIDlet) cls.newInstance();
                    started = true;
                    MIDletAccess.startApp(midlet);
                } catch (Throwable t) {
                    Log.e(TAG, "Could not start MIDlet " + className, t);
                    showFatal(t);
                }
            }
        });
    }

    public static void registerMIDlet(MIDlet m) {
        midlet = m;
    }

    public static Activity getActivity() { return activity; }

    public static File filesDir() { return activity.getFilesDir(); }

    public static File cacheDir() { return activity.getCacheDir(); }

    public static void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    // -------------------------------------------------------------- lifecycle

    static void onPause() {
        paused = true;
        if (gl != null) gl.onPause();
        final Displayable d = current;
        if (d != null) {
            events.post(new Runnable() { public void run() { d.j2aHideNotify(); } });
        }
        javax.microedition.media.Manager.j2aPauseAll();
        if (started) {
            lifecycle.execute(new Runnable() {
                public void run() {
                    try { MIDletAccess.pauseApp(midlet); } catch (Throwable t) { Log.w(TAG, "pauseApp", t); }
                }
            });
        }
    }

    static void onResume() {
        if (gl != null) gl.onResume();
        if (!paused) return;
        paused = false;
        if (started) {
            lifecycle.execute(new Runnable() {
                public void run() {
                    try { MIDletAccess.startApp(midlet); } catch (Throwable t) { Log.w(TAG, "startApp", t); }
                }
            });
        }
        javax.microedition.media.Manager.j2aResumeAll();
        final Displayable d = current;
        if (d != null) {
            events.post(new Runnable() { public void run() { d.j2aShowNotify(); } });
            if (d instanceof Canvas) ((Canvas) d).repaint();
        }
    }

    /** Called by MIDlet.notifyDestroyed() or the "Salir" menu entry. */
    public static void exit() {
        javax.microedition.media.Manager.j2aCloseAll();
        events.stop();
        ui.post(new Runnable() {
            public void run() {
                if (activity != null) activity.finish();
                ui.postDelayed(new Runnable() {
                    public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
                }, 300);
            }
        });
    }

    static void requestExit() {
        lifecycle.execute(new Runnable() {
            public void run() {
                try { if (midlet != null) MIDletAccess.destroyApp(midlet, true); } catch (Throwable ignored) {}
                exit();
            }
        });
    }

    // ---------------------------------------------------------- displayables

    public static Displayable getCurrent() { return current; }

    /** True while the activity is in the background (Android 17 blocks background audio). */
    public static boolean isPaused() { return paused; }

    static void applyOrientation(Activity a, String o) {
        // 13 FULL_USER, 12 USER_PORTRAIT, 11 USER_LANDSCAPE; "" keeps the manifest value
        if ("auto".equals(o)) a.setRequestedOrientation(13);
        else if ("portrait".equals(o)) a.setRequestedOrientation(12);
        else if ("landscape".equals(o)) a.setRequestedOrientation(11);
    }

    public static void setCurrent(final Displayable next) {
        if (next == null) return;
        final Displayable prev = current;
        if (prev == next) {
            if (next instanceof Canvas) ((Canvas) next).repaint();
            return;
        }
        current = next;
        if (prev != null) {
            prev.j2aSetShown(false);
            events.post(new Runnable() { public void run() { prev.j2aHideNotify(); } });
        }
        next.j2aSetShown(true);
        if (next instanceof Canvas) {
            runOnUi(new Runnable() {
                public void run() {
                    screenContainer.removeAllViews();
                    screenContainer.setVisibility(View.GONE);
                    view.requestFocus();
                    view.redraw();
                }
            });
            events.post(new Runnable() { public void run() { next.j2aShowNotify(); } });
            ((Canvas) next).repaint();
        } else {
            refreshScreen((Screen) next);
            events.post(new Runnable() { public void run() { next.j2aShowNotify(); } });
            if (next instanceof Alert) ((Alert) next).j2aStartTimer();
        }
    }

    /** Rebuilds the native Android view of a Form/List/TextBox/Alert. */
    public static void refreshScreen(final Screen s) {
        if (s != current || activity == null) return;
        runOnUi(new Runnable() {
            public void run() {
                if (s != current) return;
                screenContainer.removeAllViews();
                screenContainer.addView(s.j2aCreateView(activity));
                screenContainer.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Called when commands of the current displayable change. */
    public static void commandsChanged(Displayable d) {
        if (d != current) return;
        if (d instanceof Screen) refreshScreen((Screen) d);
        else runOnUi(new Runnable() { public void run() { view.redraw(); } });
    }

    public static void fireCommand(final Displayable d, final Command c) {
        events.post(new Runnable() { public void run() { d.j2aFireCommand(c); } });
    }

    // ----------------------------------------------------------- painting

    static void paintCanvas(Canvas c) {
        synchronized (screenImage) {
            screenGraphics.j2aReset();
            try {
                c.j2aPaint(screenGraphics);
            } catch (Throwable t) {
                Log.e(TAG, "Exception in paint()", t);
            }
        }
        present(screenImage.j2aBitmap());
    }

    /** Shows a finished frame (screen bitmap or GameCanvas buffer). */
    public static void present(Bitmap frame) {
        GlScreen g = gl;
        if (g == null || frame == null) return;
        if (paused) {
            // in background: don't let a game loop that relies on vsync spin at 100% CPU
            if (Looper.myLooper() != Looper.getMainLooper()) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            return;
        }
        g.present(frame);
    }

    static void relayout() {
        final ScreenView v = view;
        if (v != null) v.post(new Runnable() { public void run() { v.relayout(); } });
    }

    static void redrawOverlay() {
        ScreenView v = view;
        if (v != null) v.redraw();
    }

    // --------------------------------------------------------------- input

    /** type: 0 = pressed, 1 = released, 2 = repeated */
    static void key(final int type, final int keyCode) {
        final Displayable d = current;
        if (d instanceof Canvas) {
            final Canvas c = (Canvas) d;
            if (type == 0 && (keyCode == Keys.SOFT_LEFT || keyCode == Keys.SOFT_RIGHT)) {
                if (handleSoftKey(c, keyCode)) return;
            }
            final int code = keyCode == Keys.SOFT_LEFT ? cfg.softLeft
                    : keyCode == Keys.SOFT_RIGHT ? cfg.softRight : keyCode;
            events.post(new Runnable() { public void run() { c.j2aKey(type, code); } });
        } else if (d != null && type == 0 && keyCode == Keys.SOFT_RIGHT) {
            Command back = d.j2aBackCommand();
            if (back != null) fireCommand(d, back);
        }
    }

    private static boolean handleSoftKey(Canvas c, int keyCode) {
        if (!c.j2aCommandsUseSoftKeys()) return false;
        if (keyCode == Keys.SOFT_RIGHT) {
            Command back = c.j2aBackCommand();
            if (back == null) return false;
            fireCommand(c, back);
            return true;
        }
        Vector<Command> left = c.j2aLeftCommands();
        if (left.isEmpty()) return false;
        if (left.size() == 1) fireCommand(c, left.elementAt(0));
        else showCommandMenu(c, left);
        return true;
    }

    static void pointer(final int type, final int x, final int y) {
        final Displayable d = current;
        if (!(d instanceof Canvas)) return;
        final Canvas c = (Canvas) d;
        events.post(new Runnable() { public void run() { c.j2aPointer(type, x, y); } });
    }

    static String softLabel(boolean left) {
        Displayable d = current;
        if (!(d instanceof Canvas)) return null;
        Canvas c = (Canvas) d;
        if (!c.j2aCommandsUseSoftKeys()) return null;
        if (!left) {
            Command b = c.j2aBackCommand();
            return b == null ? null : b.getLabel();
        }
        Vector<Command> l = c.j2aLeftCommands();
        if (l.isEmpty()) return null;
        return l.size() == 1 ? l.elementAt(0).getLabel() : "Opciones";
    }

    static void backPressed() {
        if ("menu".equals(cfg.backAction)) {
            showPortMenu();
            return;
        }
        Displayable d = current;
        if (d instanceof Canvas) {
            key(0, Keys.SOFT_RIGHT);
            key(1, Keys.SOFT_RIGHT);
        } else if (d != null) {
            Command back = d.j2aBackCommand();
            if (back != null) fireCommand(d, back);
            else showPortMenu();
        }
    }

    // ------------------------------------------------------------- dialogs

    static void showCommandMenu(final Displayable d, final Vector<Command> cmds) {
        runOnUi(new Runnable() {
            public void run() {
                String[] items = new String[cmds.size()];
                for (int i = 0; i < items.length; i++) items[i] = cmds.elementAt(i).getLabel();
                new AlertDialog.Builder(activity)
                        .setItems(items, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int which) {
                                fireCommand(d, cmds.elementAt(which));
                            }
                        })
                        .show();
            }
        });
    }

    static void showPortMenu() {
        runOnUi(new Runnable() {
            public void run() {
                final java.util.List<Shaders.Def> shaders = Shaders.all(activity);
                String shaderName = cfg.shader;
                for (Shaders.Def d : shaders) if (d.id.equals(cfg.shader)) shaderName = d.label;
                final String[] items = {
                        "Resolución: " + cfg.screenWidth + "x" + cfg.screenHeight,
                        "Rotación: " + (cfg.orientation.length() == 0 ? "la del juego" : cfg.orientation),
                        "Escalado: " + cfg.scaleMode,
                        "Shader: " + shaderName,
                        "Modo de FPS: " + cfg.fpsMode,
                        "Límite de FPS: " + (cfg.fpsLimit == 0 ? "sin límite" : String.valueOf(cfg.fpsLimit)),
                        "Mostrar FPS: " + (cfg.showFps ? "sí" : "no"),
                        "Teclado virtual: " + cfg.keypad,
                        "Opacidad del teclado: " + (cfg.keypadAlpha * 100 / 255) + "%",
                        "Vibración al pulsar: " + (cfg.haptics ? "sí" : "no"),
                        "Sonido: " + (cfg.sound ? "sí" : "no"),
                        "Ajuste automático de resolución: " + (cfg.autoFit ? "sí" : "no"),
                        "Captura de pantalla",
                        "Restablecer opciones",
                        "Salir del juego"
                };
                new AlertDialog.Builder(activity)
                        .setTitle("Menú del port")
                        .setItems(items, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int which) {
                                switch (which) {
                                    case 0: pickResolution(); return;
                                    case 1:
                                        pick("Rotación", new String[]{"Automática", "Vertical", "Horizontal"},
                                                new String[]{"auto", "portrait", "landscape"}, cfg.orientation,
                                                new Runnable() {
                                                    public void run() { applyOrientation(activity, cfg.orientation); }
                                                }, "orientation");
                                        return;
                                    case 2:
                                        pick("Escalado", new String[]{"Proporcional", "Entero (pixel perfect)", "Estirar (pantalla completa)"},
                                                new String[]{"fit", "integer", "stretch"}, cfg.scaleMode, null, "scale");
                                        return;
                                    case 3: {
                                        String[] labels = new String[shaders.size()], ids = new String[shaders.size()];
                                        for (int i = 0; i < ids.length; i++) {
                                            labels[i] = shaders.get(i).label;
                                            ids[i] = shaders.get(i).id;
                                        }
                                        pick("Shader", labels, ids, cfg.shader, null, "shader");
                                        return;
                                    }
                                    case 4:
                                        pick("Modo de FPS", new String[]{"Sincronizado con la pantalla (vsync)",
                                                        "Dinámico (la pantalla se adapta a los FPS del juego)", "Sin límite"},
                                                new String[]{"vsync", "dynamic", "unlimited"}, cfg.fpsMode, null, "fpsMode");
                                        return;
                                    case 5:
                                        pick("Límite de FPS", new String[]{"Sin límite", "15", "20", "25", "30", "50", "60", "90", "120"},
                                                new String[]{"0", "15", "20", "25", "30", "50", "60", "90", "120"},
                                                String.valueOf(cfg.fpsLimit), null, "fps");
                                        return;
                                    case 6: cfg.showFps = !cfg.showFps; break;
                                    case 7:
                                        pick("Teclado virtual", new String[]{"Completo", "Cruceta + OK", "Oculto"},
                                                new String[]{"full", "minimal", "none"}, cfg.keypad, null, "keypad");
                                        return;
                                    case 8:
                                        pick("Opacidad del teclado", new String[]{"25%", "50%", "75%", "100%"},
                                                new String[]{"64", "128", "192", "255"}, String.valueOf(cfg.keypadAlpha), null, "alpha");
                                        return;
                                    case 9: cfg.haptics = !cfg.haptics; break;
                                    case 10:
                                        cfg.sound = !cfg.sound;
                                        if (cfg.sound) javax.microedition.media.Manager.j2aResumeAll();
                                        else javax.microedition.media.Manager.j2aPauseAll();
                                        break;
                                    case 11:
                                        cfg.autoFit = !cfg.autoFit;
                                        if (!cfg.autoFit) AutoFit.disable();
                                        break;
                                    case 12: Screenshot.save(activity, gl.snapshot()); return;
                                    case 13:
                                        Settings.reset(activity);
                                        new AlertDialog.Builder(activity)
                                                .setMessage("Opciones restablecidas. Se aplican al volver a abrir el juego.")
                                                .setPositiveButton("Salir ahora", new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, int x) { requestExit(); }
                                                })
                                                .setNegativeButton("Más tarde", null)
                                                .show();
                                        return;
                                    case 14: requestExit(); return;
                                }
                                settingsChanged();
                            }
                        })
                        .show();
            }
        });
    }

    private static void settingsChanged() {
        Settings.save(activity, cfg);
        view.relayout();
    }

    /** Single-choice dialog that writes the picked value into one Config field. */
    private static void pick(String title, String[] labels, final String[] values, String current,
                             final Runnable after, final String field) {
        int checked = java.util.Arrays.asList(values).indexOf(current);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        dlg.dismiss();
                        String v = values[which];
                        if (field.equals("orientation")) cfg.orientation = v;
                        else if (field.equals("scale")) cfg.scaleMode = v;
                        else if (field.equals("fps")) cfg.fpsLimit = Integer.parseInt(v);
                        else if (field.equals("keypad")) cfg.keypad = v;
                        else if (field.equals("shader")) { cfg.shader = v; gl.requestRender(); }
                        else if (field.equals("fpsMode")) cfg.fpsMode = v;
                        else if (field.equals("alpha")) cfg.keypadAlpha = Integer.parseInt(v);
                        if (after != null) after.run();
                        settingsChanged();
                    }
                })
                .show();
    }

    /** Classic phone sizes plus a widescreen mode that matches this device's aspect ratio. */
    private static void pickResolution() {
        final int[][] sizes = {{128, 160}, {176, 208}, {176, 220}, {240, 320}, {320, 240}, {240, 400},
                {360, 640}, {640, 360}, {0, 0}};
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length - 1; i++) labels[i] = sizes[i][0] + "x" + sizes[i][1];
        labels[sizes.length - 1] = "Widescreen (proporción de este móvil)";
        new AlertDialog.Builder(activity)
                .setTitle("Resolución del juego")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        int w = sizes[which][0], h = sizes[which][1];
                        if (w == 0) {
                            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                            float aspect = Math.max(dm.widthPixels, dm.heightPixels)
                                    / (float) Math.min(dm.widthPixels, dm.heightPixels);
                            // keep the short side, stretch the long side to the device aspect
                            w = cfg.screenWidth;
                            h = cfg.screenHeight;
                            if (w <= h) h = Math.round(w * aspect);
                            else w = Math.round(h * aspect);
                        }
                        Settings.saveScreen(activity, w, h);
                        new AlertDialog.Builder(activity)
                                .setMessage("La resolución " + w + "x" + h + " se aplica al reiniciar el juego. "
                                        + "Solo funciona bien en juegos que se adaptan al tamaño de pantalla.")
                                .setPositiveButton("Salir ahora", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int x) { requestExit(); }
                                })
                                .setNegativeButton("Más tarde", null)
                                .show();
                    }
                })
                .show();
    }

    static void showFatal(final Throwable t) {
        runOnUi(new Runnable() {
            public void run() {
                new AlertDialog.Builder(activity)
                        .setTitle("Error al iniciar el juego")
                        .setMessage(String.valueOf(t))
                        .setPositiveButton("Salir", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { exit(); }
                        })
                        .show();
            }
        });
    }

    // ------------------------------------------------------------- platform

    public static boolean vibrate(int ms) {
        if (!cfg.vibration || activity == null) return false;
        try {
            Vibrator v = (Vibrator) activity.getSystemService(Activity.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return false;
            if (ms <= 0) v.cancel();
            else v.vibrate(ms);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean platformRequest(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "platformRequest failed: " + url, t);
        }
        return false;
    }

    public static String getAppProperty(String key) {
        return appProperties.getProperty(key);
    }
}
