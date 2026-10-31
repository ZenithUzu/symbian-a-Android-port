package j2a.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-processing filters, chained: one scaler ("nearest", "xbr"...) then any number of effects
 * ("crt", "scanlines"...), written in Config.shader as "xbr+crt-lottes".
 *
 * Three shader formats:
 *  - built-in (original code) and PPSSPP (.ini + .fsh [+ .vsh] in assets/shaders/): attributes
 *    a_position, a_texcoord0; uniforms sampler0, u_texelDelta (1/game size), u_pixelDelta (1/output size);
 *  - RetroArch single-file .glsl (github.com/libretro/glsl-shaders; bundled ones in assets/shaders-retro/,
 *    more can be dropped into assets/shaders/): VertexCoord, TexCoord, MVPMatrix, Texture, TextureSize,
 *    InputSize, OutputSize, FrameCount, compiled once with VERTEX and once with FRAGMENT defined.
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

    /** id, menu label, needs linear filtering, "s" scaler / "e" effect, fragment body. */
    static final String[][] BUILT_IN = {
            {"vivid", "Color vivo (contraste y saturación)", "0", "e",
                    "void main() { vec4 c = texture2D(sampler0, v_texcoord0); float l = dot(c.rgb, vec3(0.299,0.587,0.114)); gl_FragColor = vec4(clamp((mix(vec3(l), c.rgb, 1.2) - 0.5) * 1.06 + 0.5, 0.0, 1.0), c.a); }"},
            {"warm", "Ámbar (pantalla cálida)", "0", "e",
                    "void main() { vec4 c = texture2D(sampler0, v_texcoord0); gl_FragColor = vec4(c.rgb * vec3(1.0,0.94,0.82), c.a); }"},
            {"nearest", "Nítido (píxeles tal cual)", "0", "s",
                    "void main() { gl_FragColor = texture2D(sampler0, v_texcoord0); }"},
            // Integer prescale + bilinear only on pixel edges: sharp at any (non-integer) scale.
            {"sharp", "Nítido sin deformar (sharp bilinear)", "1", "s",
                    "void main() {\n"
                    + "  vec2 scale = max(floor(u_texelDelta / u_pixelDelta), vec2(1.0));\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec2 range = 0.5 - 0.5 / scale;\n"
                    + "  vec2 d = fract(texel) - 0.5;\n"
                    + "  vec2 f = (d - clamp(d, -range, range)) * scale + 0.5;\n"
                    + "  gl_FragColor = texture2D(sampler0, (floor(texel) + f) * u_texelDelta);\n"
                    + "}"},
            {"smooth", "Suavizado (bilineal)", "1", "s",
                    "void main() { gl_FragColor = texture2D(sampler0, v_texcoord0); }"},
            {"crt", "CRT (curvatura y máscara)", "1", "e",
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
            // Thin dark gap between game rows, wider only when each game row is several screen pixels tall.
            {"scanlines", "Scanlines suaves", "0", "e",
                    "void main() {\n"
                    + "  vec3 c = texture2D(sampler0, v_texcoord0).rgb;\n"
                    + "  float rows = u_texelDelta.y / u_pixelDelta.y;\n"
                    + "  float y = fract(v_texcoord0.y / u_texelDelta.y);\n"
                    + "  float w = clamp(1.0 / rows, 0.08, 0.5);\n"
                    + "  float gap = smoothstep(1.0 - w, 1.0, y) + 1.0 - smoothstep(0.0, w, y);\n"
                    + "  gl_FragColor = vec4(c * (1.0 - 0.35 * min(gap, 1.0)) * 1.06, 1.0);\n"
                    + "}"},
            {"lcd", "Rejilla LCD (pantalla de teléfono)", "0", "e",
                    "void main() {\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec2 px = u_texelDelta / u_pixelDelta;\n"
                    + "  vec2 d = abs(fract(texel) - 0.5) * 2.0;\n"
                    + "  vec2 edge = smoothstep(1.0 - 1.6 / px, vec2(1.0), d);\n"
                    + "  vec3 c = texture2D(sampler0, v_texcoord0).rgb;\n"
                    + "  gl_FragColor = vec4(c * (1.0 - 0.28 * max(edge.x, edge.y)), 1.0);\n"
                    + "}"},
            {"mono", "LCD verde (teléfono monocromo)", "0", "e",
                    "void main() {\n"
                    + "  vec2 texel = v_texcoord0 / u_texelDelta;\n"
                    + "  vec3 c = texture2D(sampler0, v_texcoord0).rgb;\n"
                    + "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
                    + "  vec3 g = mix(vec3(0.10, 0.19, 0.10), vec3(0.66, 0.76, 0.42), l);\n"
                    + "  vec2 d = abs(fract(texel) - 0.5) * 2.0;\n"
                    + "  vec2 edge = smoothstep(1.0 - 1.6 / (u_texelDelta / u_pixelDelta), vec2(1.0), d);\n"
                    + "  gl_FragColor = vec4(g * (1.0 - 0.2 * max(edge.x, edge.y)), 1.0);\n"
                    + "}"},
    };

    /** RetroArch filters bundled in assets/shaders-retro/: id, label, file, needs linear, "s"/"e". */
    static final String[][] RETRO = {
            {"xbr", "xBR (redondea el pixel art)", "xbr-lv2.glsl", "0", "s"},
            {"mmpx", "MMPX (pixel art nítido, bordes limpios)", "mmpx.glsl", "0", "s"},
            {"aann", "AANN (nítido sin dientes de sierra)", "aann.glsl", "1", "s"},
            {"crt-lottes", "CRT Lottes (televisión)", "crt-lottes.glsl", "1", "e"},
            {"dot", "Matriz de puntos (consola portátil)", "dot.glsl", "1", "e"},
    };

    /** A loaded shader program description. */
    static final class Def {
        String id, label, vertex = VERTEX, fragment;
        boolean linear, scaler;
    }

    static Def builtIn(String id) {
        for (String[] s : BUILT_IN) {
            if (s[0].equals(id)) {
                Def d = new Def();
                d.id = s[0];
                d.label = s[1];
                d.linear = s[2].equals("1");
                d.scaler = s[3].equals("s");
                d.fragment = HEAD + s[4];
                if (!d.scaler) {
                    d.fragment = HEAD + "uniform float u_effectStrength;\n"
                            + s[4].replace("void main()", "void effectMain()")
                            + "\nvoid main() { effectMain(); gl_FragColor = mix(texture2D(sampler0, v_texcoord0), gl_FragColor, u_effectStrength); }\n";
                }
                return d;
            }
        }
        return null;
    }

    /** Every filter: built-ins, bundled RetroArch ones, and whatever is in assets/shaders/. */
    static List<Def> all(Context ctx) {
        List<Def> out = new ArrayList<Def>();
        for (String[] s : BUILT_IN) out.add(builtIn(s[0]));
        for (String[] r : RETRO) {
            Def d = retro(ctx, "shaders-retro/" + r[2], r[0], r[1]);
            if (d == null) continue;
            d.linear = r[3].equals("1");
            d.scaler = r[4].equals("s");
            out.add(d);
        }
        try {
            String[] files = ctx.getAssets().list("shaders");
            if (files == null) return out;
            for (String f : files) {
                if (f.toLowerCase().endsWith(".glsl")) {
                    Def d = retro(ctx, "shaders/" + f, "retro:" + f, f.substring(0, f.length() - 5));
                    if (d != null) {
                        d.linear = true;
                        out.add(d);
                    }
                }
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

    /** RetroArch single-file shader; the "#version 130" some declare is dropped (GLES 2.0 is version 100). */
    private static Def retro(Context ctx, String asset, String id, String label) {
        try {
            String src = read(ctx, asset).replaceAll("(?m)^\\s*#version.*$", "");
            Def d = new Def();
            d.id = id;
            d.label = label;
            d.vertex = "#define VERTEX\n" + src;
            d.fragment = "#define FRAGMENT\n" + src;
            return d;
        } catch (Exception e) {
            return null;
        }
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

    /** "xbr+crt-lottes" -> the filters in order (unknown ids skipped; never empty). */
    static List<Def> chain(Context ctx, String spec) {
        List<Def> all = null, out = new ArrayList<Def>();
        for (String id : spec.split("\\+")) {
            Def d = builtIn(id.trim());
            if (d == null) {
                if (all == null) all = all(ctx);
                for (Def x : all) if (x.id.equals(id.trim())) d = x;
            }
            if (d != null) out.add(d);
        }
        if (out.isEmpty()) out.add(builtIn("nearest"));
        return out;
    }

    /** Menu text for a chain: "xBR + CRT Lottes". */
    static String label(Context ctx, String spec) {
        StringBuilder b = new StringBuilder();
        for (Def d : chain(ctx, spec)) b.append(b.length() > 0 ? " + " : "").append(d.label.split(" \\(")[0]);
        return b.toString();
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
