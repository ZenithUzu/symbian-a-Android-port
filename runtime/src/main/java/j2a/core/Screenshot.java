package j2a.core;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Saves the current game frame, scaled up with sharp pixels, to the gallery. */
final class Screenshot {
    private Screenshot() {}

    static void save(final Activity a, final Bitmap frame) {
        if (frame == null) return;
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    int k = Math.max(1, 1080 / Math.max(frame.getWidth(), frame.getHeight()));
                    Bitmap big = Bitmap.createScaledBitmap(frame, frame.getWidth() * k, frame.getHeight() * k, false);
                    String name = "port_" + System.currentTimeMillis() + ".png";
                    OutputStream out;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        v.put("relative_path", Environment.DIRECTORY_PICTURES + "/" + a.getTitle()); // RELATIVE_PATH (API 29)
                        Uri uri = a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        if (uri == null) throw new java.io.IOException("MediaStore insert failed");
                        out = a.getContentResolver().openOutputStream(uri);
                        msg = "Captura guardada en Imágenes/" + a.getTitle();
                    } else {
                        // no storage permission needed for the app's own external folder
                        File dir = a.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                        File f = new File(dir, name);
                        out = new FileOutputStream(f);
                        msg = "Captura guardada en " + f.getAbsolutePath();
                    }
                    try { big.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { out.close(); }
                } catch (Exception e) {
                    msg = "No se pudo guardar la captura: " + e.getMessage();
                }
                final String m = msg;
                a.runOnUiThread(new Runnable() {
                    public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); }
                });
            }
        }, "j2a-screenshot").start();
    }
}
