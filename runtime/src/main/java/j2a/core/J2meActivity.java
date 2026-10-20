package j2a.core;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.InputStream;
import java.util.Properties;

import javax.microedition.lcdui.Displayable;

/**
 * Activity that hosts a J2ME MIDlet. The generated app's MainActivity simply
 * extends this class; override {@link #onConfigLoaded(Config)} to tweak the
 * configuration from code.
 */
public class J2meActivity extends Activity {
    private static final String TAG = "j2a";
    private static boolean launched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            // layoutInDisplayCutoutMode = SHORT_EDGES (reflection keeps the runtime compilable with old SDKs)
            try {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.getClass().getField("layoutInDisplayCutoutMode").setInt(lp, 1);
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) {}
        }

        Properties port = loadProps("j2a/port.properties");
        Properties app = loadProps("j2a/app.properties");
        Config cfg = Config.load(port);
        Settings.apply(this, cfg);
        onConfigLoaded(cfg);
        Host.applyOrientation(this, cfg.orientation);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        GlScreen gl = new GlScreen(this);
        ScreenView view = new ScreenView(this, gl);
        FrameLayout screens = new FrameLayout(this);
        screens.setVisibility(View.GONE);
        root.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        root.addView(screens, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        view.requestFocus();

        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC); // volume keys = game volume
        Host.init(this, cfg, app, gl, view, screens);
        if (launched) {
            // The activity was recreated while the process survived: the
            // MIDlet keeps running, we only re-attached the views.
            Displayable d = Host.current;
            Host.current = null;
            if (d != null) Host.setCurrent(d);
            return;
        }
        launched = true;
        String cls = cfg.midletClass;
        if (cls == null) {
            String m1 = app.getProperty("MIDlet-1");
            if (m1 != null) {
                String[] parts = m1.split(",");
                if (parts.length >= 3) cls = parts[2].trim();
            }
        }
        if (cls == null) {
            Host.showFatal(new IllegalStateException("No se encontró la clase MIDlet (MIDlet-1)"));
            return;
        }
        Host.startMIDlet(cls);
        Host.askKeypadOnce();
    }

    /**
     * Android 16+ stops sending KEYCODE_BACK to apps targeting API 36+. The manifest opts out
     * (enableOnBackInvokedCallback=false), which Google calls temporary; this callback keeps BACK
     * working once that opt-out goes away. Reflection keeps the runtime compilable with old SDKs.
     */
    private void registerBackCallback() {
        try {
            Class<?> cb = Class.forName("android.window.OnBackInvokedCallback");
            Class<?> disp = Class.forName("android.window.OnBackInvokedDispatcher");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb},
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object self, java.lang.reflect.Method m, Object[] args) {
                            if (m.getName().equals("onBackInvoked")) Host.backPressed();
                            else if (m.getName().equals("equals")) return self == args[0];
                            else if (m.getName().equals("hashCode")) return System.identityHashCode(self);
                            else if (m.getName().equals("toString")) return "j2a-back";
                            return null;
                        }
                    });
            Object dispatcher = getClass().getMethod("getOnBackInvokedDispatcher").invoke(this);
            disp.getMethod("registerOnBackInvokedCallback", int.class, cb).invoke(dispatcher, 0, proxy);
        } catch (Throwable ignored) {}
    }

    /** Hook for ports: change Config values before the MIDlet starts. */
    protected void onConfigLoaded(Config cfg) {}

    private Properties loadProps(String asset) {
        Properties p = new Properties();
        try {
            InputStream in = getAssets().open(asset);
            try { p.load(new java.io.InputStreamReader(in, "UTF-8")); } finally { in.close(); }
        } catch (Exception e) {
            Log.w(TAG, "Missing asset " + asset);
        }
        return p;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Host.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Host.onResume();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // Let text fields of native screens receive the keyboard.
        if (Host.screenContainer != null && Host.screenContainer.getVisibility() == View.VISIBLE
                && code != KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                if (code == KeyEvent.KEYCODE_ESCAPE) Host.showPortMenu();
                else Host.backPressed();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        // Playing with a gamepad or keyboard: hide the touch keypad until the screen is touched.
        int src = e.getSource();
        if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (e.getDevice() != null && e.getDevice().getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)) {
            if (Host.view != null) Host.view.setControllerActive(true);
        }
        int j2me = Keys.fromAndroid(code);
        if (j2me == 0) {
            // Letters typed on a hardware keyboard are passed as their character code.
            int ch = e.getUnicodeChar();
            if (ch <= 0) return super.dispatchKeyEvent(e);
            j2me = ch;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) Host.key(e.getRepeatCount() == 0 ? 0 : 2, j2me);
        else if (e.getAction() == KeyEvent.ACTION_UP) Host.key(1, j2me);
        return true;
    }
}
