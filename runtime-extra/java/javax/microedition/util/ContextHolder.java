package javax.microedition.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import j2a.core.Host;
import j2a.core.ResourceShim;

/**
 * Compatibility layer for the APIs imported from J2ME Loader (vendor APIs, sensors, MascotCapsule):
 * the same static helpers, implemented on the j2a engine.
 */
public final class ContextHolder {
    private static final java.util.ArrayList<ActivityResultListener> listeners = new java.util.ArrayList<>();

    private ContextHolder() {}

    public static Context getAppContext() {
        return Host.getActivity().getApplicationContext();
    }

    public static Activity getActivity() {
        return Host.getActivity();
    }

    /** Key states for vendor APIs (the j2a keypad itself is not exposed). */
    public static javax.microedition.lcdui.keyboard.VirtualKeyboard getVk() {
        return VK;
    }

    private static final javax.microedition.lcdui.keyboard.VirtualKeyboard VK =
            new javax.microedition.lcdui.keyboard.VirtualKeyboard();

    public static InputStream getResourceAsStream(Class<?> resClass, String resName) {
        return ResourceShim.getResourceAsStream(resClass, resName);
    }

    public static File getFileByName(String name) {
        return new File(Host.filesDir(), name);
    }

    public static File getCacheDir() {
        return Host.cacheDir();
    }

    public static boolean requestPermission(String permission) {
        return requestPermissions(new String[]{permission});
    }

    public static boolean requestPermissions(String[] permissions) {
        Activity a = Host.getActivity();
        if (Build.VERSION.SDK_INT < 23 || a == null) return true;
        boolean all = true;
        for (String p : permissions) all &= a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (!all) a.requestPermissions(permissions, 0);
        return all;
    }

    public static void addActivityResultListener(ActivityResultListener l) {
        synchronized (listeners) { listeners.add(l); }
    }

    public static void removeActivityResultListener(ActivityResultListener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    public static void notifyOnActivityResult(int requestCode, int resultCode, Intent data) {
        ActivityResultListener[] ls;
        synchronized (listeners) { ls = listeners.toArray(new ActivityResultListener[0]); }
        for (ActivityResultListener l : ls) l.onActivityResult(requestCode, resultCode, data);
    }

    public static String getAssetAsString(String fileName) {
        try (InputStream in = Host.getActivity().getAssets().open(fileName)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean vibrate(int duration) {
        return Host.vibrate(duration);
    }

    public static void vibratePeriodically(int durationOn, int durationOff) {
        Host.vibrate(durationOn);
    }
}
