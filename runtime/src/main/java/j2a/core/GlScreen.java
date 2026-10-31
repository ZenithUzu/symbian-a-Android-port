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
    private Bitmap staging, previous;
    private Canvas stagingCanvas, previousCanvas;
    private final android.graphics.Paint half = new android.graphics.Paint();
    { half.setAlpha(128); }
    private boolean dirty, pending;
    private final Rect dst = new Rect();
    private final Rect drawRect = new Rect();
    private int viewW, viewH;

    private int texture = -1, texW, texH;
    private String loadedShader;
    /** One compiled filter of the chain (see Shaders): j2a/PPSSPP and RetroArch names both looked up. */
    private static final class Pass {
        int program, aPos, aTex, uTexel, uPixel, uMvp, uTexSize, uInSize, uOutSize, uFrame, uDir, uStrength;
        boolean linear;
    }
    private final java.util.ArrayList<Pass> passes = new java.util.ArrayList<Pass>();
    /** Ping-pong render targets between passes (output size), recreated when the size changes. */
    private final int[] fbo = new int[2], fboTex = new int[2];
    private int fboW, fboH, frameCount;
    private boolean targetsOk;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private final FloatBuffer quad;
    /** Quad for reading a render target: v = 0 is its bottom row. */
    private final FloatBuffer fboQuad;

    private final Object paceLock = new Object();
    private long nextFrameNs;
    private int presented, throttled; // frames shown / frames a game thread was already paced for
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
        float[] f = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};
        fboQuad = ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fboQuad.put(f).position(0);
    }

    /** Where (in view pixels) the game image goes; set by the keypad overlay layout. */
    void setDestination(Rect r) {
        synchronized (lock) { dst.set(r); }
        requestRender();
    }

    /** Copy of the last shown frame (screenshots). */
    Bitmap snapshot() {
        synchronized (lock) {
            if (staging == null) return null;
            Rect a = AutoFit.area();
            a.intersect(0, 0, staging.getWidth(), staging.getHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(Host.rotation());
            return Bitmap.createBitmap(staging, a.left, a.top, a.width(), a.height(), m, false);
        }
    }

    /** Called with every finished game frame, from any thread. */
    public void present(Bitmap frame) {
        Config c = Host.cfg;
        // The J2ME event thread (Canvas.paint) also delivers key/touch events: never make it
        // wait for the GPU, or input queues up behind frames and controls feel laggy.
        boolean gameThread = Looper.myLooper() != Looper.getMainLooper() && !Host.events.isEventThread();
        if (gameThread) {
            synchronized (paceLock) {
                if (c.fpsLimit > 0) pace(c.fpsLimit);
                if (!"unlimited".equals(c.fpsMode)) waitShown();
                throttled = presented + 1; // this frame is paced: repaint()/serviceRepaints() don't wait again
            }
        }
        synchronized (lock) {
            presented++;
            if (staging == null || staging.getWidth() != frame.getWidth() || staging.getHeight() != frame.getHeight()) {
                staging = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
                stagingCanvas = new Canvas(staging);
            }
            // copy, so the game can keep drawing into its buffer while the GPU uploads this one
            if (c.frameBlend) {
                // average of this frame and the last one: lines/dots a game flickers every other frame
                // to fake transparency (old LCDs blended them) look see-through instead of flashing
                if (previous == null || previous.getWidth() != frame.getWidth() || previous.getHeight() != frame.getHeight()) {
                    previous = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
                    previousCanvas = new Canvas(previous);
                    previousCanvas.drawBitmap(frame, 0, 0, null);
                }
                stagingCanvas.drawBitmap(previous, 0, 0, null);
                stagingCanvas.drawBitmap(frame, 0, 0, half);
                previousCanvas.drawBitmap(frame, 0, 0, null);
            } else {
                stagingCanvas.drawBitmap(frame, 0, 0, null);
            }
            dirty = true;
            pending = true;
        }
        countFrame();
        AutoFit.frameShown();
        requestRender();
    }

    /**
     * Pacing for games whose frames are painted by the event thread (repaint(), serviceRepaints(),
     * callSerially loops): the game thread asking for frames waits here, once per frame shown.
     * The event thread itself is only paced inside a callSerially loop, which is the game loop.
     */
    void throttle() {
        Config c = Host.cfg;
        synchronized (paceLock) {
            synchronized (lock) {
                if (presented == throttled) return; // nothing shown since the last wait
                throttled = presented;
            }
            if (c.fpsLimit > 0) pace(c.fpsLimit);
            if (!"unlimited".equals(c.fpsMode) && !Host.events.isEventThread()) waitShown();
        }
    }

    /** vsync: waits until the previous frame reached the display (at most 100 ms). */
    private void waitShown() {
        synchronized (lock) {
            long until = System.currentTimeMillis() + 100; // never block a game forever
            while (pending && System.currentTimeMillis() < until) {
                try { lock.wait(20); } catch (InterruptedException e) { return; }
            }
        }
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
            else if (requestedRate != 0) setRefreshRate(0); // left dynamic mode: give the display back
        }
    }

    /** Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT) on Android 11+ (reflection: old SDKs). */
    private void matchRefreshRate(int gameFps) {
        if (Build.VERSION.SDK_INT < 30 || gameFps <= 0) return;
        // never below 60 Hz: at 30/48 Hz touch and the on-screen keypad visibly lag
        float rate = gameFps <= 64 ? 60f : gameFps <= 95 ? 90f : 120f;
        if (rate != requestedRate) setRefreshRate(rate);
    }

    /** rate 0 = no preference. */
    private void setRefreshRate(float rate) {
        requestedRate = rate;
        try {
            Surface s = getHolder().getSurface();
            if (Build.VERSION.SDK_INT >= 31) {
                // FIXED_SOURCE + CHANGE_FRAME_RATE_ALWAYS: also when the switch isn't seamless (most phones)
                Surface.class.getMethod("setFrameRate", float.class, int.class, int.class).invoke(s, rate, 1, 1);
            } else {
                Surface.class.getMethod("setFrameRate", float.class, int.class).invoke(s, rate, 1);
            }
        } catch (Throwable ignored) {}
        Host.preferRefreshRate(rate); // Xiaomi/Samsung often ignore setFrameRate but honor the window's mode
    }

    // ------------------------------------------------------------------ renderer

    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        texW = texH = 0;
        loadedShader = null;
        passes.clear(); // the old context's programs and targets are gone
        fboW = fboH = 0;
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
        if (!Host.cfg.shader.equals(loadedShader)) {
            try {
                loadShader(Host.cfg.shader);
            } catch (RuntimeException e) { // a broken filter must never kill the drawing thread (black screen)
                Log.w(TAG, "Filters '" + Host.cfg.shader + "' failed", e);
                passes.clear();
                loadShader("nearest");
            }
        }
        Rect r = drawRect;
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
        if (r.isEmpty() || passes.isEmpty()) return;
        frameCount++;
        setTexCoords(AutoFit.area());
        int n = passes.size(), first = 0;
        if (n > 1) ensureTargets(r.width(), r.height());
        if (n > 1 && !targetsOk) first = n - 1; // no render targets on this GPU: just the last filter
        // game pixels in screen orientation: what effects after the first pass line up with
        Rect a = AutoFit.area();
        boolean turned = Host.rotation() == 90 || Host.rotation() == 270;
        float gw = turned ? a.height() : a.width(), gh = turned ? a.width() : a.height();
        for (int i = first; i < n; i++) {
            Pass p = passes.get(i);
            boolean last = i == n - 1;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, last ? 0 : fbo[i % 2]);
            if (last) GLES20.glViewport(r.left, viewH - r.bottom, r.width(), r.height());
            else GLES20.glViewport(0, 0, r.width(), r.height());
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, i == first ? texture : fboTex[(i - 1) % 2]);
            int filter = p.linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glUseProgram(p.program);
            if (p.uStrength >= 0) GLES20.glUniform1f(p.uStrength, Host.cfg.effectStrength / 100f);
            // first pass reads the game texture; later ones the previous pass, on the game's pixel grid
            float tw = i == first ? texW : gw, th = i == first ? texH : gh;
            if (p.uTexel >= 0) GLES20.glUniform2f(p.uTexel, 1f / tw, 1f / th);
            if (p.uPixel >= 0) GLES20.glUniform2f(p.uPixel, 1f / r.width(), 1f / r.height());
            if (p.uTexSize >= 0) GLES20.glUniform2f(p.uTexSize, tw, th);
            if (p.uInSize >= 0) GLES20.glUniform2f(p.uInSize, tw, th);
            if (p.uOutSize >= 0) GLES20.glUniform2f(p.uOutSize, r.width(), r.height());
            if (p.uMvp >= 0) GLES20.glUniformMatrix4fv(p.uMvp, 1, false, IDENTITY, 0);
            if (p.uFrame >= 0) GLES20.glUniform1i(p.uFrame, frameCount);
            if (p.uDir >= 0) GLES20.glUniform1i(p.uDir, 1);
            FloatBuffer q = i == first ? quad : fboQuad;
            q.position(0);
            GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 16, q);
            GLES20.glEnableVertexAttribArray(p.aPos);
            if (p.aTex >= 0) {
                q.position(2);
                GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 16, q);
                GLES20.glEnableVertexAttribArray(p.aTex);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    /** Two textures of the output size to render the passes before the last one into. */
    private void ensureTargets(int w, int h) {
        if (w == fboW && h == fboH) return;
        if (fboW != 0) {
            GLES20.glDeleteFramebuffers(2, fbo, 0);
            GLES20.glDeleteTextures(2, fboTex, 0);
        }
        fboW = w;
        fboH = h;
        GLES20.glGenFramebuffers(2, fbo, 0);
        GLES20.glGenTextures(2, fboTex, 0);
        targetsOk = true;
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, null);
            // Mali/Adreno reject a target whose texture still has the default mipmap filter
            // (incomplete framebuffer: nothing is drawn and the screen stays black)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTex[i], 0);
            int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "Render target not supported (" + st + "): only the last filter is drawn");
                targetsOk = false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Shows only the given part of the game texture (AutoFit). */
    private void setTexCoords(Rect a) {
        float u0 = a.left / (float) texW, u1 = a.right / (float) texW;
        float v0 = a.top / (float) texH, v1 = a.bottom / (float) texH;
        // corners of the game image: top-left, top-right, bottom-left, bottom-right
        float[] tl = {u0, v0}, tr = {u1, v0}, bl = {u0, v1}, br = {u1, v1};
        float[][] c; // what goes at screen BL, BR, TL, TR
        if (Host.rotation() == 90) c = new float[][]{br, tr, bl, tl};        // clockwise
        else if (Host.rotation() == 270) c = new float[][]{tl, bl, tr, br};  // counter-clockwise
        else c = new float[][]{bl, br, tl, tr};
        for (int i = 0; i < 4; i++) quad.put(i * 4 + 2, c[i][0]).put(i * 4 + 3, c[i][1]);
    }

    private void loadShader(String spec) {
        loadedShader = spec;
        for (Pass p : passes) GLES20.glDeleteProgram(p.program);
        passes.clear();
        for (Shaders.Def d : Shaders.chain(getContext(), spec)) {
            int prog = link(d.vertex, d.fragment);
            if (prog == 0) {
                Log.w(TAG, "Shader '" + d.id + "' failed, skipped");
                continue;
            }
            Pass p = new Pass();
            p.program = prog;
            p.linear = d.linear;
            p.aPos = attrib(prog, "a_position", "VertexCoord");
            p.aTex = attrib(prog, "a_texcoord0", "TexCoord");
            p.uTexel = GLES20.glGetUniformLocation(prog, "u_texelDelta");
            p.uPixel = GLES20.glGetUniformLocation(prog, "u_pixelDelta");
            p.uMvp = GLES20.glGetUniformLocation(prog, "MVPMatrix");
            p.uTexSize = GLES20.glGetUniformLocation(prog, "TextureSize");
            p.uInSize = GLES20.glGetUniformLocation(prog, "InputSize");
            p.uOutSize = GLES20.glGetUniformLocation(prog, "OutputSize");
            p.uFrame = GLES20.glGetUniformLocation(prog, "FrameCount");
            p.uDir = GLES20.glGetUniformLocation(prog, "FrameDirection");
            p.uStrength = GLES20.glGetUniformLocation(prog, "u_effectStrength");
            passes.add(p); // samplers (sampler0, Texture, decal...) default to texture unit 0
        }
        if (passes.isEmpty() && !"nearest".equals(spec)) loadShader("nearest");
    }

    private static int attrib(int prog, String a, String b) {
        int l = GLES20.glGetAttribLocation(prog, a);
        return l >= 0 ? l : GLES20.glGetAttribLocation(prog, b);
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
