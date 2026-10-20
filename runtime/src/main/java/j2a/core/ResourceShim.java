package j2a.core;

import java.io.InputStream;

/**
 * Replacement for Class.getResourceAsStream(String). The j2a tool rewrites the
 * game's bytecode so every call goes here, because on Android
 * {@code "".getClass().getResourceAsStream(...)} (a very common J2ME idiom)
 * returns null: java.lang.String belongs to the boot class loader, which
 * cannot see the APK's resources.
 */
public final class ResourceShim {
    private ResourceShim() {}

    public static InputStream getResourceAsStream(Class<?> c, String name) {
        if (name == null) return null;
        String path;
        if (name.startsWith("/")) {
            path = name.substring(1);
        } else {
            String cn = c == null ? "" : c.getName();
            int dot = cn.lastIndexOf('.');
            path = dot < 0 ? name : cn.substring(0, dot).replace('.', '/') + "/" + name;
        }
        return open(path, name);
    }

    /** Opens a JAR resource by absolute path (with or without leading '/'). */
    public static InputStream open(String path) {
        if (path == null) return null;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return open(p, path);
    }

    private static InputStream open(String path, String original) {
        ClassLoader cl = ResourceShim.class.getClassLoader();
        InputStream in = cl == null ? null : cl.getResourceAsStream(path);
        if (in == null && Host.activity != null) {
            // Fallback: resources copied to assets/res/ by the tool.
            try { in = Host.activity.getAssets().open("res/" + path); } catch (Exception ignored) {}
        }
        if (in == null) {
            // Last resort: case-insensitive lookup (some games were built on Windows).
            String alt = CaseIndex.find(path);
            if (alt != null && cl != null) in = cl.getResourceAsStream(alt);
        }
        if (in == null) android.util.Log.w("j2a", "Resource not found: " + original);
        return in;
    }
}
