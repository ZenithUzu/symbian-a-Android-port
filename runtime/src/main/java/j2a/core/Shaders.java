package j2a.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-processing shaders. The built-in ones are original code; extra shaders in
 * PPSSPP's format (.ini + .fsh [+ .vsh], e.g. github.com/jdgleaver/ppsspp_shaders)
 * can be dropped into assets/shaders/ and show up in the port menu.
 *
 * Interface (same as PPSSPP): attributes a_position, a_texcoord0; varying v_texcoord0;
 * uniforms sampler0, u_texelDelta (1/game size), u_pixelDelta (1/output size).
 */
public final class Shaders {
    private Shaders() {}

    public static final String VERTEX =
            "attribute vec4 a_position;\n"
            + "attribute vec2 a_texcoord0;\n"
            + "varying vec2 v_texcoord0;\n"
            + "void main() { v_texcoord0 = a_texcoord0; gl_Position = a_position; }\n";

    private static final String HEAD =
            "#ifdef GL_ES\nprecision highp float;\n#endif\n"
            + "uniform sampler2D sampler0;\nuniform vec2 u_texelDelta;\nuniform vec2 u_pixelDelta;\n"
            + "varying vec2 v_texcoord0;\n";

    /** id, menu label, needs linear filtering, fragment body. */
    static final String[][] BUILT_IN = {
            {"nearest", "Píxeles nítidos", "0",
                    "void main() { gl_FragColor = texture2D(sampler0, v_texcoord0); }"},
            {"smooth", "Suavizado", "1",
                    "void main() { gl_FragColor = texture2D(sampler0, v_texcoord0); }"},
            // Integer prescale + bilinear only on pixel edges: sharp at any (non-integer) scale.
            {"sharp", "Nítido sin deformar (sharp bilinear)", "1",
                    "void main() {\n"
                    + "  vec2 scale = max(floor(u_texelDelta / u_pixelDelta), vec2(1.0));\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec2 range = 0.5 - 0.5 / scale;\n"
                    + "  vec2 d = fract(texel) - 0.5;\n"
                    + "  vec2 f = (d - clamp(d, -range, range)) * scale + 0.5;\n"
                    + "  gl_FragColor = texture2D(sampler0, (floor(texel) + f) * u_texelDelta);\n"
                    + "}"},
            {"scanlines", "Scanlines", "0",
                    "void main() {\n"
                    + "  float y = fract(v_texcoord0.y / u_texelDelta.y);\n"
                    + "  vec3 c = texture2D(sampler0, v_texcoord0).rgb;\n"
                    + "  gl_FragColor = vec4(c * (0.68 + 0.32 * sin(y * 3.14159)) * 1.12, 1.0);\n"
                    + "}"},
            {"lcd", "Rejilla LCD", "0",
                    "void main() {\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec2 d = abs(fract(texel) - 0.5);\n"
                    + "  float edge = smoothstep(0.34, 0.5, max(d.x, d.y));\n"
                    + "  vec3 c = texture2D(sampler0, (floor(texel) + 0.5) * u_texelDelta).rgb;\n"
                    + "  gl_FragColor = vec4(c * (1.0 - 0.42 * edge), 1.0);\n"
                    + "}"},
            {"crt", "CRT", "1",
                    "void main() {\n"
                    + "  vec2 uv = v_texcoord0 * 2.0 - 1.0;\n"
                    + "  uv *= 1.0 + vec2(uv.y * uv.y, uv.x * uv.x) * 0.045;\n"
                    + "  vec2 tc = uv * 0.5 + 0.5;\n"
                    + "  if (tc.x < 0.0 || tc.y < 0.0 || tc.x > 1.0 || tc.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }\n"
                    + "  vec3 c = texture2D(sampler0, tc).rgb;\n"
                    + "  float scan = 0.72 + 0.28 * sin(fract(tc.y / u_texelDelta.y) * 3.14159);\n"
                    + "  float m = mod(gl_FragCoord.x, 3.0);\n"
                    + "  vec3 mask = m < 1.0 ? vec3(1.0, 0.82, 0.82) : m < 2.0 ? vec3(0.82, 1.0, 0.82) : vec3(0.82, 0.82, 1.0);\n"
                    + "  float vig = 1.0 - 0.25 * dot(uv, uv);\n"
                    + "  gl_FragColor = vec4(c * scan * mask * vig * 1.25, 1.0);\n"
                    + "}"},
            {"mono", "LCD monocromo (verde)", "0",
                    "void main() {\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec3 c = texture2D(sampler0, (floor(texel) + 0.5) * u_texelDelta).rgb;\n"
                    + "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
                    + "  vec3 g = mix(vec3(0.06, 0.15, 0.06), vec3(0.62, 0.74, 0.32), l);\n"
                    + "  vec2 d = abs(fract(texel) - 0.5);\n"
                    + "  gl_FragColor = vec4(g * (1.0 - 0.3 * smoothstep(0.38, 0.5, max(d.x, d.y))), 1.0);\n"
                    + "}"},
    };

    /** A loaded shader program description. */
    static final class Def {
        String id, label, vertex = VERTEX, fragment;
        boolean linear;
    }

    static Def builtIn(String id) {
        for (String[] s : BUILT_IN) {
            if (s[0].equals(id)) {
                Def d = new Def();
                d.id = s[0];
                d.label = s[1];
                d.linear = s[2].equals("1");
                d.fragment = HEAD + s[3];
                return d;
            }
        }
        return null;
    }

    /** Built-ins plus every [section] of every assets/shaders/*.ini (PPSSPP format). */
    static List<Def> all(Context ctx) {
        List<Def> out = new ArrayList<Def>();
        for (String[] s : BUILT_IN) out.add(builtIn(s[0]));
        try {
            String[] files = ctx.getAssets().list("shaders");
            if (files == null) return out;
            for (String f : files) {
                if (!f.toLowerCase().endsWith(".ini")) continue;
                String section = null, name = null, frag = null, vert = null;
                for (String raw : read(ctx, "shaders/" + f).split("\n")) {
                    String line = raw.trim();
                    if (line.startsWith("[")) {
                        add(ctx, out, section, name, frag, vert);
                        section = line.substring(1, line.indexOf(']') > 0 ? line.indexOf(']') : line.length());
                        name = frag = vert = null;
                    } else if (line.startsWith("Name=")) {
                        name = line.substring(5).trim();
                    } else if (line.startsWith("Fragment=")) {
                        frag = line.substring(9).trim();
                    } else if (line.startsWith("Vertex=")) {
                        vert = line.substring(7).trim();
                    }
                }
                add(ctx, out, section, name, frag, vert);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void add(Context ctx, List<Def> out, String section, String name, String frag, String vert) {
        if (section == null || frag == null) return;
        try {
            Def d = new Def();
            d.id = "file:" + section;
            d.label = name != null ? name : section;
            d.fragment = read(ctx, "shaders/" + frag);
            if (vert != null) d.vertex = read(ctx, "shaders/" + vert);
            d.linear = true;
            out.add(d);
        } catch (Exception ignored) {} // missing file referenced by the .ini
    }

    static Def find(Context ctx, String id) {
        Def d = builtIn(id);
        if (d != null) return d;
        for (Def x : all(ctx)) if (x.id.equals(id)) return x;
        return builtIn("nearest");
    }

    private static String read(Context ctx, String asset) throws java.io.IOException {
        InputStream in = ctx.getAssets().open(asset);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
