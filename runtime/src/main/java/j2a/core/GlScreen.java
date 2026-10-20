package j2a.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the game frame with OpenGL ES 2.0 through a post-processing shader.
 *
 * Frame pacing (fps.mode):
 *  - vsync:     a game thread waits until the previous frame reached the display (like a phone's
 *               blocking flushGraphics), so loops without sleep() don't run at 1000 FPS.
 *  - dynamic:   vsync + asks the display to switch its refresh rate to the game's real FPS
 *               (Surface.setFrameRate, Android 11+): smoother pacing and less battery on 90/120 Hz.
 *  - unlimited: never waits; extra frames are dropped.
 * fps.limit caps any mode.
 */
public class GlScreen extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "j2a";

    private final Object lock = new Object();
    private Bitmap staging;
    private Canvas stagingCanvas;
    private boolean dirty, pending;
    private final Rect dst = new Rect();
    private int viewW, viewH;

    private int program, texture = -1, texW, texH;
    private String loadedShader;
    private boolean linear;
    private int aPos, aTex, uTexel, uPixel, uSampler;
    private final FloatBuffer quad;

    private long nextFrameNs;
    private int frames;
    private long fpsWindowNs;
    volatile int fps;
    private float requestedRate;

    public GlScreen(Context ctx) {
        super(ctx);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        // x, y, u, v (triangle strip); v=0 is the top row of the bitmap
        float[] q = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
    }

    /** Where (in view pixels) the game image goes; set by the keypad overlay layout. */
    void setDestination(Rect r) {
        synchronized (lock) { dst.set(r); }
        requestRender();
    }

    /** Copy of the last shown frame (screenshots). */
    Bitmap snapshot() {
        synchronized (lock) {
            return staging == null ? null : staging.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    /** Called with every finished game frame, from any thread. */
    public void present(Bitmap frame) {
        Config c = Host.cfg;
        boolean gameThread = Looper.myLooper() != Looper.getMainLooper();
        if (gameThread && c.fpsLimit > 0) pace(c.fpsLimit);
        synchronized (lock) {
            if (gameThread && !"unlimited".equals(c.fpsMode)) {
                long until = System.currentTimeMillis() + 100; // never block a game forever
                while (pending && System.currentTimeMillis() < until) {
                    try { lock.wait(20); } catch (InterruptedException e) { return; }
                }
            }
            if (staging == null || staging.getWidth() != frame.getWidth() || staging.getHeight() != frame.getHeight()) {
                staging = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
                stagingCanvas = new Canvas(staging);
            }
            // copy, so the game can keep drawing into its buffer while the GPU uploads this one
            stagingCanvas.drawBitmap(frame, 0, 0, null);
            dirty = true;
            pending = true;
        }
        countFrame();
        requestRender();
    }

    /** Fixed-cadence limiter in nanoseconds (30 really gives 30). */
    private void pace(int limit) {
        long period = 1000000000L / limit;
        long now = System.nanoTime();
        long wait = nextFrameNs - now;
        if (wait > 0) {
            try { Thread.sleep(wait / 1000000L, (int) (wait % 1000000L)); } catch (InterruptedException ignored) {}
        }
        nextFrameNs = (wait > -period ? nextFrameNs : now) + period;
    }

    private void countFrame() {
        frames++;
        long now = System.nanoTime();
        if (now - fpsWindowNs >= 1000000000L) {
            fps = frames;
            frames = 0;
            fpsWindowNs = now;
            if (Host.cfg.showFps) Host.redrawOverlay();
            if ("dynamic".equals(Host.cfg.fpsMode)) matchRefreshRate(fps);
        }
    }

    /** Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT) on Android 11+ (reflection: old SDKs). */
    private void matchRefreshRate(int gameFps) {
        if (Build.VERSION.SDK_INT < 30 || gameFps <= 0) return;
        float rate = gameFps <= 32 ? 30f : gameFps <= 50 ? 48f : gameFps <= 64 ? 60f : gameFps <= 95 ? 90f : 120f;
        if (rate == requestedRate) return;
        requestedRate = rate;
        try {
            Surface s = getHolder().getSurface();
            Surface.class.getMethod("setFrameRate", float.class, int.class).invoke(s, rate, 0);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ renderer

    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        texW = texH = 0;
        loadedShader = null;
        synchronized (lock) { dirty = staging != null; }
    }

    public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW = w;
        viewH = h;
    }

    public void onDrawFrame(GL10 unused) {
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!Host.cfg.shader.equals(loadedShader)) loadShader(Host.cfg.shader);
        Rect r = new Rect();
        synchronized (lock) {
            r.set(dst);
            if (staging == null) {
                pending = false;
                lock.notifyAll();
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            if (dirty) {
                if (texW != staging.getWidth() || texH != staging.getHeight()) {
                    texW = staging.getWidth();
                    texH = staging.getHeight();
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, staging, 0);
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, staging);
                }
                dirty = false;
            }
            pending = false;
            lock.notifyAll();
        }
        if (r.isEmpty() || program == 0) return;
        int filter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glViewport(r.left, viewH - r.bottom, r.width(), r.height());
        GLES20.glUseProgram(program);
        if (uSampler >= 0) GLES20.glUniform1i(uSampler, 0);
        if (uTexel >= 0) GLES20.glUniform2f(uTexel, 1f / texW, 1f / texH);
        if (uPixel >= 0) GLES20.glUniform2f(uPixel, 1f / r.width(), 1f / r.height());
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        if (aTex >= 0) {
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void loadShader(String id) {
        loadedShader = id;
        Shaders.Def d = Shaders.find(getContext(), id);
        int p = link(d.vertex, d.fragment);
        if (p == 0 && !"nearest".equals(d.id)) {
            Log.w(TAG, "Shader '" + id + "' failed, using 'nearest'");
            d = Shaders.builtIn("nearest");
            p = link(d.vertex, d.fragment);
        }
        if (program != 0) GLES20.glDeleteProgram(program);
        program = p;
        linear = d.linear;
        aPos = GLES20.glGetAttribLocation(p, "a_position");
        aTex = GLES20.glGetAttribLocation(p, "a_texcoord0");
        uTexel = GLES20.glGetUniformLocation(p, "u_texelDelta");
        uPixel = GLES20.glGetUniformLocation(p, "u_pixelDelta");
        uSampler = GLES20.glGetUniformLocation(p, "sampler0");
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        if (ok[0] == 0) {
            Log.w(TAG, "Shader link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "Shader compile: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
