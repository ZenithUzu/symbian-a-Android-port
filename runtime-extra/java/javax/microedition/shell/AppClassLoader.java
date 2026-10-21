package javax.microedition.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import j2a.core.Host;
import j2a.core.ResourceShim;

/** Compatibility layer for imported J2ME Loader code: JAR resources come from the APK. */
public final class AppClassLoader {
    private AppClassLoader() {}

    public static InputStream getResourceAsStream(Class<?> resClass, String resName) {
        return ResourceShim.getResourceAsStream(resClass, resName);
    }

    public static byte[] getResourceAsBytes(String resName) {
        InputStream in = ResourceShim.open(resName);
        if (in == null) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    public static String getDataDir() {
        return Host.filesDir().getAbsolutePath();
    }
}
