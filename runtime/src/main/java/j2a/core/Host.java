package j2a.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

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
        screenImage = Image.j2aCreateScreen(cfg.screenWidth, cfg.screenHeight);
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
        System.setProperty("com.nokia.mid.ui.multipointtouch.version", "1.0");
        System.setProperty("com.nokia.pointer.number", "0");
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
                    // keep playing, but say so: a dead loader thread otherwise looks like an endless loading screen
                    final String msg = "Error del juego: " + e;
                    runOnUi(new Runnable() {
                        public void run() {
                            if (activity != null) {
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
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

    /** Image rotation in use: the player's choice, or what AutoFit detected (0 until then). */
    public static int rotation() {
        return cfg.rotate >= 0 ? cfg.rotate : AutoFit.detectedRotation();
    }

    /** Orientation from the player's choice, else from image rotation (sideways games -> landscape). */
    static void applyOrientation(Activity a) {
        if (cfg.orientation.length() > 0) applyOrientation(a, cfg.orientation);
        else if (rotation() == 90 || rotation() == 270) applyOrientation(a, "landscape");
        else {
            try { // back to the orientation the port was generated with (AndroidManifest)
                a.setRequestedOrientation(a.getPackageManager().getActivityInfo(a.getComponentName(), 0).screenOrientation);
            } catch (Exception ignored) {}
        }
    }

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

    /** Asks for the display mode (same resolution) whose refresh rate is closest to 'rate'; 0 = system default. */
    static void preferRefreshRate(final float rate) {
        runOnUi(new Runnable() {
            public void run() {
                Activity a = activity;
                if (a == null || Build.VERSION.SDK_INT < 23) return;
                android.view.Display d = a.getWindowManager().getDefaultDisplay();
                android.view.Display.Mode cur = d.getMode();
                int best = 0;
                float diff = Float.MAX_VALUE;
                for (android.view.Display.Mode m : rate > 0 ? d.getSupportedModes() : new android.view.Display.Mode[0]) {
                    if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                    float dd = Math.abs(m.getRefreshRate() - rate);
                    if (dd < diff) { diff = dd; best = m.getModeId(); }
                }
                android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                if (lp.preferredDisplayModeId == best) return;
                lp.preferredDisplayModeId = best;
                a.getWindow().setAttributes(lp);
            }
        });
    }

    /** FPS limit / vsync for games drawn with repaint() or callSerially() (see GlScreen.throttle). */
    public static void throttle() {
        GlScreen g = gl;
        if (g != null && !paused && Looper.myLooper() != Looper.getMainLooper()) g.throttle();
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

    /** Draws an image straight onto the game screen and shows it (Canvas.flushBuffer). */
    public static void drawToScreen(Image img, int x, int y) {
        if (screenImage == null) return;
        synchronized (screenImage) {
            screenGraphics.j2aReset();
            screenGraphics.drawImage(img, x, y, Graphics.TOP | Graphics.LEFT);
        }
        present(screenImage.j2aBitmap());
    }

    private static final java.util.Set<Integer> keysDown = new java.util.HashSet<Integer>();

    /** Pressed keys as the Vodafone/J-Phone/KDDI key-state bitmask. */
    public static int vodafoneKeyStates() {
        int s = 0;
        synchronized (keysDown) {
            for (int k : keysDown) {
                if (k >= Keys.NUM0 && k <= Keys.NUM9) s |= 1 << (k - Keys.NUM0);
                else if (k == Keys.STAR) s |= 1 << 10;
                else if (k == Keys.POUND) s |= 1 << 11;
                else if (k == Keys.UP) s |= 1 << 12;
                else if (k == Keys.LEFT) s |= 1 << 13;
                else if (k == Keys.RIGHT) s |= 1 << 14;
                else if (k == Keys.DOWN) s |= 1 << 15;
                else if (k == Keys.FIRE) s |= 1 << 16;
                else if (k == Keys.SOFT_LEFT) s |= 1 << 17;
                else if (k == Keys.SOFT_RIGHT) s |= 1 << 18;
                else if (k == Keys.CLEAR) s |= 1 << 19;
            }
        }
        return s;
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
        synchronized (keysDown) {
            if (type == 0) keysDown.add(keyCode);
            else if (type == 1) keysDown.remove(keyCode);
        }
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

    /** type: 0 pressed, 1 dragged, 2 released; id = finger (0 = first). */
    static void pointer(final int type, final int id, final int x, final int y) {
        final Displayable d = current;
        if (!(d instanceof Canvas)) return;
        final Canvas c = (Canvas) d;
        events.post(new Runnable() {
            public void run() {
                // Nokia multipoint touch: the game asks which finger caused the event
                System.setProperty("com.nokia.pointer.number", String.valueOf(id));
                c.j2aPointer(type, x, y);
            }
        });
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

    /** Port menu: opened with Android's back gesture/button (or Menu/Escape). */
    static void showPortMenu() {
        runOnUi(() -> {
            final java.util.List<String> labels = new java.util.ArrayList<>();
            final java.util.List<Runnable> actions = new java.util.ArrayList<>();
            java.util.function.BiConsumer<String, Runnable> item = (l, r) -> { labels.add(l); actions.add(r); };
            final java.util.List<Shaders.Def> shaders = Shaders.all(activity);
            String shaderName = cfg.shader;
            for (Shaders.Def d : shaders) if (d.id.equals(cfg.shader)) shaderName = d.label;

            item.accept("▶ Continuar", () -> {});
            item.accept("Tecla izquierda del juego (menú / aceptar)", () -> tapKey(Keys.SOFT_LEFT));
            item.accept("Tecla derecha del juego (atrás / pausa)", () -> tapKey(Keys.SOFT_RIGHT));
            item.accept("Resolución: " + cfg.screenWidth + "x" + cfg.screenHeight, Host::pickResolution);
            item.accept("Rotación: " + (cfg.orientation.length() == 0 ? "la del juego" : cfg.orientation), () ->
                    pick("Rotación", new String[]{"Automática", "Vertical", "Horizontal"},
                            new String[]{"auto", "portrait", "landscape"}, cfg.orientation,
                            () -> applyOrientation(activity, cfg.orientation), "orientation"));
            item.accept("Girar imagen del juego: " + (cfg.rotate < 0 ? "automático (" + rotation() + "°)"
                    : cfg.rotate == 0 ? "no" : cfg.rotate + "°"), () ->
                    pick("Girar imagen del juego", new String[]{
                                    "Automático (detecta juegos que dibujan de lado)", "No girar",
                                    "90° (juegos que se juegan de lado, girando a la derecha)",
                                    "270° (juegos que se juegan de lado, girando a la izquierda)"},
                            new String[]{"-1", "0", "90", "270"}, String.valueOf(cfg.rotate),
                            () -> applyOrientation(activity), "rotate"));
            item.accept("Pantalla: " + ("wide".equals(cfg.scaleMode) ? "16:9" : "stretch".equals(cfg.scaleMode)
                    ? "completa (estirar)" : "integer".equals(cfg.scaleMode) ? "pixel perfect" : "proporción original"), () ->
                    pick("Pantalla", new String[]{"Proporción original del juego", "Pixel perfect (escalado entero)",
                                    "16:9 (usa casi toda la pantalla, deforma poco)", "Completa (estira hasta llenar todo)"},
                            new String[]{"fit", "integer", "wide", "stretch"}, cfg.scaleMode, null, "scale"));
            item.accept("Shader: " + shaderName, () -> {
                String[] ls = new String[shaders.size()], ids = new String[shaders.size()];
                for (int i = 0; i < ids.length; i++) {
                    ls[i] = shaders.get(i).label;
                    ids[i] = shaders.get(i).id;
                }
                pick("Shader", ls, ids, cfg.shader, null, "shader");
            });
            item.accept("Modo de FPS: " + cfg.fpsMode, () ->
                    pick("Modo de FPS", new String[]{"Sincronizado con la pantalla (vsync)",
                                    "Dinámico (la pantalla se adapta a los FPS del juego)", "Sin límite"},
                            new String[]{"vsync", "dynamic", "unlimited"}, cfg.fpsMode, null, "fpsMode"));
            item.accept("Límite de FPS: " + (cfg.fpsLimit == 0 ? "sin límite" : String.valueOf(cfg.fpsLimit)), () ->
                    pick("Límite de FPS", new String[]{"Sin límite", "15", "20", "25", "30", "50", "60", "90", "120"},
                            new String[]{"0", "15", "20", "25", "30", "50", "60", "90", "120"},
                            String.valueOf(cfg.fpsLimit), null, "fps"));
            item.accept("Mostrar FPS: " + (cfg.showFps ? "sí" : "no"), () -> toggle(() -> cfg.showFps = !cfg.showFps));
            item.accept("Teclado virtual: " + ("none".equals(cfg.keypad) ? "oculto (solo táctil)"
                    : "minimal".equals(cfg.keypad) ? "cruceta + OK" : "completo"), () ->
                    pick("Teclado virtual", new String[]{"Completo", "Cruceta + OK", "Oculto"},
                            new String[]{"full", "minimal", "none"}, cfg.keypad, null, "keypad"));
            item.accept("Opacidad del teclado: " + (cfg.keypadAlpha * 100 / 255) + "%", () ->
                    pick("Opacidad del teclado", new String[]{"25%", "50%", "75%", "100%"},
                            new String[]{"64", "128", "192", "255"}, String.valueOf(cfg.keypadAlpha), null, "alpha"));
            item.accept("Vibración al pulsar: " + (cfg.haptics ? "sí" : "no"), () -> toggle(() -> cfg.haptics = !cfg.haptics));
            item.accept("Sonido: " + (cfg.sound ? "sí" : "no"), () -> toggle(() -> {
                cfg.sound = !cfg.sound;
                if (cfg.sound) javax.microedition.media.Manager.j2aResumeAll();
                else javax.microedition.media.Manager.j2aPauseAll();
            }));
            item.accept("Ajuste automático de resolución: " + (cfg.autoFit ? "sí" : "no"), () -> toggle(() -> {
                cfg.autoFit = !cfg.autoFit;
                if (!cfg.autoFit) AutoFit.disable();
            }));
            item.accept("Captura de pantalla", () -> Screenshot.save(activity, gl.snapshot()));
            item.accept("Restablecer opciones", () -> {
                Settings.reset(activity);
                askRestart("Opciones restablecidas. Se aplican al volver a abrir el juego.");
            });
            item.accept("Salir del juego", Host::requestExit);

            new AlertDialog.Builder(activity)
                    .setTitle("Menú del port")
                    .setItems(labels.toArray(new String[0]), (dlg, which) -> actions.get(which).run())
                    .show();
        });
    }

    /** Presses and releases a game key (menu shortcuts for the soft keys). */
    private static void tapKey(int code) {
        key(0, code);
        ui.postDelayed(() -> key(1, code), 80);
    }

    private static void toggle(Runnable change) {
        change.run();
        settingsChanged();
    }

    private static void askRestart(String message) {
        new AlertDialog.Builder(activity)
                .setMessage(message.replace("al volver a abrir el juego", "al reiniciar el juego"))
                .setPositiveButton("Reiniciar ahora", (d, x) -> restartApp())
                .setNegativeButton("Más tarde", null)
                .show();
    }

    /** Touch games: ask once whether the on-screen keypad is wanted (the menu can change it later). */
    static void askKeypadOnce() {
        if (!cfg.keypadAsk || Settings.keypadAnswered(activity)) return;
        runOnUi(new Runnable() {
            public void run() {
                new AlertDialog.Builder(activity)
                        .setTitle("Controles")
                        .setMessage("Este juego se puede jugar tocando la pantalla. ¿Quieres además el teclado "
                                + "virtual del teléfono? (Puedes cambiarlo luego en el menú: gesto o botón Atrás.)")
                        .setPositiveButton("Sí, mostrar teclado", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if ("none".equals(cfg.keypad)) cfg.keypad = "full";
                                settingsChanged();
                            }
                        })
                        .setNegativeButton("No, solo táctil", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                cfg.keypad = "none";
                                settingsChanged();
                            }
                        })
                        .setCancelable(false)
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
                        else if (field.equals("rotate")) cfg.rotate = Integer.parseInt(v);
                        if (after != null) after.run();
                        settingsChanged();
                    }
                })
                .show();
    }

    /** Classic phone sizes plus a widescreen mode that matches this device's aspect ratio. */
    private static void pickResolution() {
        final java.util.List<int[]> sizes = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        sizes.add(new int[]{cfg.baseWidth, cfg.baseHeight});
        labels.add("Original del juego (" + cfg.baseWidth + "x" + cfg.baseHeight + ")");
        for (int[] r : AutoFit.RESOLUTIONS) {
            sizes.add(r);
            labels.add(r[0] + "x" + r[1]);
        }
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int shortSide = Math.min(cfg.baseWidth, cfg.baseHeight);
        float aspect = Math.max(dm.widthPixels, dm.heightPixels) / (float) Math.min(dm.widthPixels, dm.heightPixels);
        boolean portrait = cfg.baseWidth <= cfg.baseHeight;
        int longSide = Math.round(shortSide * aspect);
        // widescreen from the game's own size (not the current one, so it never grows twice)
        sizes.add(portrait ? new int[]{shortSide, longSide} : new int[]{longSide, shortSide});
        labels.add("Widescreen real " + sizes.get(sizes.size() - 1)[0] + "x" + sizes.get(sizes.size() - 1)[1]
                + " (solo juegos que se adaptan; si no, usa Pantalla > 16:9)");
        sizes.add(null);
        labels.add("Personalizada…");
        new AlertDialog.Builder(activity)
                .setTitle("Resolución del juego")
                .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                    int[] r = sizes.get(which);
                    if (r == null) customResolution();
                    else applyResolution(r[0], r[1]);
                })
                .show();
    }

    private static void customResolution() {
        final android.widget.EditText e = new android.widget.EditText(activity);
        e.setHint("ancho x alto, p. ej. 240x320");
        e.setText(cfg.screenWidth + "x" + cfg.screenHeight);
        new AlertDialog.Builder(activity)
                .setTitle("Resolución personalizada")
                .setView(e)
                .setPositiveButton("Aplicar", (d, w) -> {
                    String[] parts = e.getText().toString().toLowerCase().split("[x*, ]+");
                    try {
                        int rw = Integer.parseInt(parts[0].trim()), rh = Integer.parseInt(parts[1].trim());
                        if (rw >= 64 && rh >= 64 && rw <= 2048 && rh <= 2048) {
                            applyResolution(rw, rh);
                            return;
                        }
                    } catch (RuntimeException ignored) {
                        // fall through to the message
                    }
                    android.widget.Toast.makeText(activity, "Formato: 240x320 (entre 64 y 2048)",
                            android.widget.Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void applyResolution(int w, int h) {
        Settings.saveScreen(activity, w, h);
        new AlertDialog.Builder(activity)
                .setMessage("Resolución " + w + "x" + h + ". Hay que reiniciar el juego para aplicarla.")
                .setPositiveButton("Reiniciar ahora", (d, x) -> restartApp())
                .setNegativeButton("Más tarde", null)
                .show();
    }

    /** Starts the app again in a fresh process (new resolution, reset options...). */
    static void restartApp() {
        android.content.Intent i = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (i == null) {
            requestExit();
            return;
        }
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        javax.microedition.media.Manager.j2aCloseAll();
        activity.startActivity(i);
        android.os.Process.killProcess(android.os.Process.myPid());
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
