"""'j2a port': builds a complete Android Studio project from a J2ME JAR."""
import io
import os
import re
import shutil
import zipfile

from . import analyze, decompile, paths
from .jarinfo import MidletSuite

TEXT_EXT = (".gradle", ".xml", ".java", ".properties", ".md", ".pro", ".txt", ".kts", ".gitignore")

JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null var record yield""".split())


def log(msg):
    print(msg, flush=True)


def _segment(text, words=None):
    """One package segment from free text: 'Gameloft S.A.' -> 'gameloft'."""
    ws = re.findall(r"[a-z0-9]+", (text or "").lower())
    if words:
        ws = ws[:words]
    seg = "".join(ws)
    if not seg:
        return ""
    if seg[0].isdigit():
        seg = "g" + seg
    return seg + "_" if seg in JAVA_KEYWORDS else seg


def official_package(suite, midlet_class):
    """applicationId from the game itself: the MIDlet's own package (com.gameloft.sonic) when it
    has a real one, otherwise com.<publisher>.<game> from MIDlet-Vendor / MIDlet-Name."""
    if midlet_class and "." in midlet_class:
        pkg = midlet_class.rsplit(".", 1)[0].lower()
        segs = pkg.split(".")
        if len(segs) >= 2 and all(re.match(r"^[a-z][a-z0-9_]{1,}$", x) and x not in JAVA_KEYWORDS for x in segs) \
                and not pkg.startswith(("javax.", "java.", "com.nokia.", "com.sun.")):
            # Publishers reuse one package for all their games ('com.glu', EA's 'com.ea.sdk'):
            # same app id = installing one game replaces the other. Add the game name unless
            # the package already names it (com.sega.sonic for "Sonic Unleashed").
            name = _segment(suite.name) or "game"
            return pkg if segs[-1] in name or name in segs[-1] else pkg + "." + name
    vendor = _segment(suite.vendor, 1)
    if len(vendor) <= 2:
        vendor = _segment(suite.vendor, 2)
    name = _segment(suite.name) or "game"
    return "com.%s.%s" % (vendor or "j2me", name)


def display_file_name(text):
    """Folder/APK name from the official game name, safe on Windows."""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", text or "").strip().strip(".")
    return re.sub(r"\s+", "_", name) or "game"


def prop_escape(s, key=False):
    out = []
    for i, ch in enumerate(s):
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch in "=:#!" and key:
            out.append("\\" + ch)
        elif ch == " " and (key or i == 0):
            out.append("\\ ")
        else:
            out.append(ch)
    return "".join(out)


def write_props(path, items, header=""):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        if header:
            for line in header.strip().splitlines():
                f.write("# " + line + "\n")
        for k, v in items:
            if k.startswith("#"):
                f.write(k + "\n")
                continue
            f.write("%s=%s\n" % (prop_escape(k, True), prop_escape(str(v))))


def xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "\\'"))


def copy_tree(src, dst, tokens):
    for d, dirs, files in os.walk(src):
        dirs[:] = [x for x in dirs if x not in ("build", ".gradle", ".idea")]
        rel = os.path.relpath(d, src)
        target_dir = os.path.join(dst, rel) if rel != "." else dst
        target_dir = target_dir.replace("__PACKAGE_PATH__", tokens["@@PACKAGE_PATH@@"])
        os.makedirs(target_dir, exist_ok=True)
        for f in files:
            s = os.path.join(d, f)
            t = os.path.join(target_dir, f)
            if f.endswith(TEXT_EXT) or f in ("gradlew",):
                with io.open(s, encoding="utf-8") as fh:
                    text = fh.read()
                for k, v in tokens.items():
                    text = text.replace(k, v)
                with io.open(t, "w", encoding="utf-8", newline="\n" if not f.endswith(".bat") else "\r\n") as fh:
                    fh.write(text)
            else:
                shutil.copyfile(s, t)
            if f == "gradlew":
                os.chmod(t, 0o755)


def pick_screen(args, report):
    if args.screen:
        m = re.match(r"^(\d+)\s*[xX*,]\s*(\d+)$", args.screen.strip())
        if not m:
            raise SystemExit("--screen debe tener el formato ANCHOxALTO, por ejemplo 240x320")
        return int(m.group(1)), int(m.group(2)), "--screen"
    if report["screen_hints"]:
        return report["screen_hints"][0]
    return 240, 320, "valor por defecto"


def jni_mangle(name):
    """Java name -> JNI symbol form ('_' -> '_1', '.' -> '_')."""
    return name.replace("_", "_1").replace(".", "_")


# Native libraries: (name, source dir, Java package with natives, packages that need it).
NATIVE_MODULES = [
    ("m3g", lambda: paths.m3g_dir(), "javax/microedition/m3g", ("javax/microedition/m3g/",)),
    ("micro3d", lambda: paths.extra_dir(), "ru/woesss/j2me/micro3d",
     ("com/mascotcapsule/", "com/jblend/graphics/j3d/", "com/motorola/graphics/j3d/",
      "com/motorola/iden/micro3d/", "com/vodafone/v10/graphics/j3d/")),
]


def needed_native_modules(used_classes):
    return [m for m in NATIVE_MODULES if any(c.startswith(m[3]) for c in used_classes)]


def _write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def copy_engine(dst, engine_pkg, renamed, modules):
    """Writes the 'engine' module: runtime + M3G bindings + extra APIs (Java, packages renamed to
    <engine_pkg>.*), plus the native libraries listed in 'modules' (built with the NDK)."""
    pkg_map = decompile.package_remapper(engine_pkg) if renamed else None
    ren = (lambda t: pkg_map[0].sub(pkg_map[1], t)) if renamed else (lambda t: t)
    # module files (build.gradle, manifest) from runtime/, Java from every source root
    rt = paths.runtime_dir()
    for f in ("build.gradle", os.path.join("src", "main", "AndroidManifest.xml")):
        with io.open(os.path.join(rt, f), encoding="utf-8") as fh:
            text = fh.read()
        if f == "build.gradle":
            text = text.replace("namespace = 'j2a.runtime'", "namespace = '%s'" % (engine_pkg if renamed else "j2a.runtime"))
        _write_text(os.path.join(dst, f), text)
    for root in paths.java_source_roots():
        for d, _, files in os.walk(root):
            for f in files:
                if not f.endswith(".java"):
                    continue
                rel = os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")
                with io.open(os.path.join(d, f), encoding="utf-8") as fh:
                    text = fh.read()
                _write_text(os.path.join(dst, "src", "main", "java", ren(rel)), ren(text))
    shutil.copytree(os.path.join(rt, "src", "main", "assets"), os.path.join(dst, "src", "main", "assets"),
                    dirs_exist_ok=True)  # bundled filters (shaders-retro/)
    for name in ("NOTICE-M3G.md",):
        shutil.copyfile(os.path.join(paths.m3g_dir(), name), os.path.join(dst, name))
    shutil.copyfile(os.path.join(paths.extra_dir(), "NOTICE-EXTRA.md"), os.path.join(dst, "NOTICE-EXTRA.md"))
    if not modules:
        return
    cpp_dst = os.path.join(dst, "src", "main", "cpp")
    for name, root_fn, java_pkg, _ in modules:
        # JNI function names encode the Java package: rename them along with it
        jni_old = "Java_%s_" % jni_mangle(java_pkg.replace("/", "."))
        new_pkg = decompile.engine_package(java_pkg, engine_pkg) if renamed else java_pkg
        jni_new = "Java_%s_" % jni_mangle(new_pkg.replace("/", "."))
        src_dir = os.path.join(root_fn(), "cpp", name)
        for d, _, files in os.walk(src_dir):
            for f in files:
                src = os.path.join(d, f)
                target = os.path.join(cpp_dst, name, os.path.relpath(src, src_dir))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(src, "rb") as fh:
                    text = fh.read().decode("latin-1")  # byte-exact round trip for any encoding
                with open(target, "wb") as fh:
                    fh.write(text.replace(jni_old, jni_new).encode("latin-1"))
        assets = os.path.join(root_fn(), "assets", name)
        if os.path.isdir(assets):
            shutil.copytree(assets, os.path.join(dst, "src", "main", "assets", name), dirs_exist_ok=True)
    _write_text(os.path.join(cpp_dst, "Android.mk"), "LOCAL_PATH := $(call my-dir)\n" + "".join(
        "include $(LOCAL_PATH)/%s/Android.mk\n" % m[0] for m in modules))
    _write_text(os.path.join(cpp_dst, "Application.mk"),
                "APP_STL := c++_shared\nAPP_ABI := arm64-v8a armeabi-v7a x86_64 x86\nAPP_PLATFORM := android-21\n")
    gradle = os.path.join(dst, "build.gradle")
    with io.open(gradle, encoding="utf-8") as fh:
        text = fh.read()
    text = text.replace("    lint {", """    // 3D engines in C/C++ (%s), built with the NDK (Android Studio installs it)
    externalNativeBuild {
        ndkBuild {
            path = file('src/main/cpp/Android.mk')
        }
    }

    lint {""" % ", ".join(m[0] for m in modules), 1)
    _write_text(gradle, text)


def rename_game_source(text, remap, pkg_remap):
    """Game sources: exact engine classes, plus 'import javax.microedition.lcdui.*;' lines."""
    if not remap:
        return text
    text = remap[0].sub(remap[1], text)
    return re.sub(r"(?m)^import\s+[\w.]+\.\*;", lambda m: pkg_remap[0].sub(pkg_remap[1], m.group(0)), text)


def rename_sources(root, remap):
    if not remap:
        return
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                p = os.path.join(d, f)
                with io.open(p, encoding="utf-8") as fh:
                    text = fh.read()
                with io.open(p, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(remap[0].sub(remap[1], text))


def copy_shaders(src_dir, assets_dir):
    """Copies PPSSPP (.ini/.fsh/.vsh) and RetroArch (.glsl) shaders into the APK assets."""
    if not src_dir:
        return 0
    os.makedirs(assets_dir, exist_ok=True)
    n = 0
    for f in os.listdir(src_dir):
        if f.lower().endswith((".ini", ".fsh", ".vsh", ".glsl")):
            shutil.copyfile(os.path.join(src_dir, f), os.path.join(assets_dir, f))
            n += f.lower().endswith((".ini", ".glsl"))
    return n


def write_icon(suite, res_dir):
    data = suite.icon_data()
    if data and data[:8] == b"\x89PNG\r\n\x1a\n":
        d = os.path.join(res_dir, "drawable-nodpi")
        os.makedirs(d, exist_ok=True)
        try:
            from PIL import Image  # optional: nicer, upscaled icon
            img = Image.open(io.BytesIO(data)).convert("RGBA")
            scale = max(1, 192 // max(img.size))
            img = img.resize((img.size[0] * scale, img.size[1] * scale), Image.NEAREST)
            img.save(os.path.join(d, "ic_launcher.png"))
        except Exception:  # noqa: BLE001 - Pillow missing or bad PNG
            with open(os.path.join(d, "ic_launcher.png"), "wb") as f:
                f.write(data)
        return True
    d = os.path.join(res_dir, "drawable")
    os.makedirs(d, exist_ok=True)
    with io.open(os.path.join(d, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write("""<?xml version="1.0" encoding="utf-8"?>
<!-- Generic icon: the MIDlet did not declare a PNG icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#1E2A3A" android:pathData="M0,0h108v108h-108z"/>
    <path android:fillColor="#5A96FF" android:pathData="M34,22h40a6,6 0 0 1 6,6v52a6,6 0 0 1 -6,6h-40a6,6 0 0 1 -6,-6v-52a6,6 0 0 1 6,-6z"/>
    <path android:fillColor="#0E1520" android:pathData="M36,30h36v28h-36z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M40,64h8v4h-8zM50,64h8v4h-8zM60,64h8v4h-8zM40,72h8v4h-8zM50,72h8v4h-8zM60,72h8v4h-8z"/>
</vector>
""")
    return False


def port(args):
    suite = MidletSuite(args.jar, args.jad)
    report = analyze.analyze(args.jar, args.jad)
    analyze.print_report(report)

    midlets = report["midlets"]
    midlet_class = args.midlet_class
    if not midlet_class:
        if not midlets:
            raise SystemExit("El JAR no declara MIDlet-1: indica la clase con --midlet-class")
        idx = max(1, args.midlet) - 1
        if idx >= len(midlets):
            raise SystemExit("Solo hay %d MIDlet(s) en el JAR" % len(midlets))
        midlet_class = midlets[idx]["class"]
        app_name = args.name or midlets[idx]["name"] or suite.name
    else:
        app_name = args.name or suite.name

    sw, sh, why = pick_screen(args, report)
    # the phone's own rotation unless asked: the image adapts to either (--orientation to lock it)
    orientation = args.orientation or "auto"
    package = args.package or official_package(suite, midlet_class)
    if not re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$", package):
        raise SystemExit("Nombre de paquete inválido: %s" % package)

    out = os.path.abspath(args.output or display_file_name(app_name) + "-android")
    if os.path.exists(out) and os.listdir(out):
        if not args.force:
            raise SystemExit("La carpeta %s ya existe y no está vacía (usa --force para reemplazarla)" % out)
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)

    log("")
    log("Generando proyecto Android en %s" % out)
    log("  MIDlet: %s   Pantalla: %dx%d (%s)   Orientación: %s   Modo: %s" % (
        midlet_class, sw, sh, why, orientation, args.mode))

    work = os.path.join(out, "j2a-work")
    os.makedirs(work, exist_ok=True)
    patched_all = os.path.join(work, "game-patched.jar")
    stats = decompile.patch_jar(suite.jar_path, patched_all)
    log("  Clases: %d, llamadas a recursos parcheadas en %d, clases de plataforma descartadas: %d" % (
        stats["classes"], len(stats["patched"]), len(stats["dropped_platform"])))

    # ------------------------------------------------ decide what becomes source
    source_classes = set()
    src_tmp = os.path.join(work, "decompiled")
    if args.mode in ("source", "hybrid") and not args.no_verify \
            and not decompile.runtime_classes_jar(paths.find_android_jar()):
        # Unverified decompiled code often doesn't compile (obfuscators reuse names in ways Java
        # source can't express), so never hand out a project that won't build.
        log("  Aviso: no encontré el Android SDK o javac (instala Android Studio y la plataforma del SDK),")
        log("         así que no puedo comprobar el código descompilado. Uso el bytecode original (modo jar),")
        log("         que siempre compila. Vuelve a generar cuando el SDK esté instalado para tener código editable.")
        args.mode = "jar"
    if args.mode in ("source", "hybrid"):
        only = None
        if args.mode == "hybrid":
            if not args.edit:
                raise SystemExit("El modo hybrid necesita --edit Clase1,Clase2 (clases a editar como código)")
            only = set(c.strip().replace(".", "/") for c in args.edit.split(",") if c.strip())
        android_jar = paths.find_android_jar()
        rt_jar = decompile.runtime_classes_jar(android_jar)
        libs = [rt_jar] if rt_jar else []
        decompile.decompile(patched_all, src_tmp, args.engine, libs=libs, only=only)
        n_fixed = decompile.fix_sources(src_tmp)
        files = decompile.java_files(src_tmp)
        source_classes = set(files)
        failed = decompile.failed_classes(src_tmp)
        if failed:
            log("  %d clase(s) no se descompilaron limpias: se quedan en bytecode (%s)" % (
                len(failed), ", ".join(sorted(failed)[:8])))
        source_classes -= failed
        with zipfile.ZipFile(patched_all) as z:
            clash = {c.lower() for c in decompile.case_clashes(
                set(n[:-6].split("$")[0] for n in z.namelist() if n.endswith(".class")))}
        clash = {c for c in source_classes if c.lower() in clash}
        if clash:  # their .java files overwrite each other on Windows/macOS: keep the original bytecode
            log("  %d clase(s) solo se distinguen por mayúsculas: se quedan en bytecode (%s)" % (
                len(clash), ", ".join(sorted(clash)[:8])))
            source_classes -= clash
        if n_fixed:
            log("  Ajustes automáticos de sintaxis en %d archivo(s)" % n_fixed)
        if args.no_verify:
            log("  Verificación de compilación omitida (--no-verify)")
        else:
            attempt = 0
            while source_classes:  # each round removes >= 1 class, so this always ends
                attempt += 1
                check_dir = os.path.join(work, "check-src")
                shutil.rmtree(check_dir, ignore_errors=True)
                for c in source_classes:
                    dst = os.path.join(check_dir, c.replace("/", os.sep) + ".java")
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(files[c], dst)
                out_cls = os.path.join(work, "check-out")
                shutil.rmtree(out_cls, ignore_errors=True)
                bad = decompile.compile_check(check_dir, android_jar, [rt_jar, patched_all], out_cls) & source_classes
                why = "errores de compilación"
                if not bad:  # compiles: now make sure nothing the original had was lost on the way
                    bad = decompile.lost_members(out_cls, patched_all, source_classes)
                    why = "métodos o campos perdidos al descompilar"
                shutil.rmtree(check_dir, ignore_errors=True)
                shutil.rmtree(out_cls, ignore_errors=True)
                if not bad:
                    log("  Verificación: las %d clases en código fuente compilan" % len(source_classes))
                    break
                log("  Verificación %d: %d clase(s) con %s -> bytecode (%s)" % (
                    attempt, len(bad), why, ", ".join(sorted(bad)[:8])))
                source_classes -= bad
        if args.keep_bytecode:
            keep = set(c.strip().replace(".", "/") for c in args.keep_bytecode.split(",") if c.strip())
            source_classes -= keep

    # ------------------------------------------------------- template + runtime
    engine_pkg = package + ".engine"
    tokens = {
        "@@APP_NAME@@": xml_escape(app_name),
        "@@PROJECT_NAME@@": re.sub(r"[^A-Za-z0-9 _-]", "", app_name) or "J2ME Port",
        "@@PACKAGE@@": package,
        "@@PACKAGE_PATH@@": package.replace(".", os.sep),
        "@@VERSION_NAME@@": suite.version,
        "@@APK_NAME@@": display_file_name(app_name) + "-" + re.sub(r"[^0-9A-Za-z.]", "", suite.version or "1.0"),
        "@@VERSION_CODE@@": str(max(1, int(re.sub(r"\D", "", suite.version.split(".")[0]) or 1))),
        "@@ORIENTATION@@": {"portrait": "userPortrait", "landscape": "userLandscape"}.get(orientation, "fullUser"),
        "@@PERMISSIONS@@": ("<uses-permission android:name=\"android.permission.INTERNET\" />"
                            if report["uses_network"] else "<!-- sin permisos de red -->"),
    }
    with zipfile.ZipFile(patched_all) as z:
        all_top = set(n[:-6].split("$")[0] for n in z.namelist() if n.endswith(".class"))
    game_jar_needed = args.mode == "jar" or bool(all_top - source_classes)
    tokens["@@GAME_DEPENDENCY@@"] = ("implementation files('libs/game.jar')" if game_jar_needed
                                     else "// todo el juego está como código fuente en src/main/java")
    copy_tree(paths.template_dir(), out, tokens)
    # Native port: the engine lives in the app's own package (<package>.engine.lcdui.Canvas...),
    # so the APK contains no javax.microedition / com.nokia classes at all.
    remap = None if args.keep_j2me_packages else decompile.native_remapper(engine_pkg)
    pkg_remap = None if args.keep_j2me_packages else decompile.package_remapper(engine_pkg)
    modules = needed_native_modules(set(report["supported"]))
    copy_engine(os.path.join(out, "engine"), engine_pkg, remap is not None, modules)
    if modules:
        log("  3D nativo incluido (%s, engine/src/main/cpp): Android Studio usará el NDK"
            % ", ".join(m[0] for m in modules))

    app = os.path.join(out, "app")
    java_root = os.path.join(app, "src", "main", "java")
    rename_sources(java_root, remap)  # MainActivity imports the engine
    # --------------------------------------------------------------- game code
    libs_dir = os.path.join(app, "libs")
    os.makedirs(libs_dir, exist_ok=True)
    if args.mode == "jar":
        st = decompile.patch_jar(suite.jar_path, os.path.join(libs_dir, "game.jar"), remap=remap)
    else:
        files = decompile.java_files(src_tmp)
        for c in sorted(source_classes):
            dst = os.path.join(java_root, c.replace("/", os.sep) + ".java")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with io.open(files[c], encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            with io.open(dst, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(rename_game_source(text, remap, pkg_remap))
        if game_jar_needed:
            st = decompile.patch_jar(suite.jar_path, os.path.join(libs_dir, "game.jar"),
                                     exclude=source_classes, remap=remap)
            log("  Código fuente: %d clases | bytecode (libs/game.jar): %d clases" % (
                len(source_classes), st["classes"]))
        else:
            log("  Código fuente: %d clases (todo el juego)" % len(source_classes))

    # ---------------------------------------------------------------- resources
    res_root = os.path.join(app, "src", "main", "resources")
    os.makedirs(res_root, exist_ok=True)
    names = decompile.extract_resources(suite.jar_path, res_root)
    assets = os.path.join(app, "src", "main", "assets", "j2a")
    os.makedirs(assets, exist_ok=True)
    with io.open(os.path.join(assets, "resources.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(names) + "\n")
    write_props(os.path.join(assets, "app.properties"), sorted(suite.attrs.items()),
                "Atributos del MANIFEST.MF/JAD (MIDlet.getAppProperty los lee de aquí)")
    hint_mode, hint_ask = report["keypad_hint"]
    args.keypad_ask = not args.keypad and hint_ask
    args.keypad = args.keypad or hint_mode or "gamepad"
    write_props(os.path.join(assets, "port.properties"), _port_items(midlet_class, sw, sh, args),
                "Configuración del port (editable). El jugador puede cambiar algunas opciones\n"
                "desde el menú del port (gesto o botón Atrás).")
    n_shader_sets = copy_shaders(args.shaders, os.path.join(app, "src", "main", "assets", "shaders"))
    if n_shader_sets:
        log("  Shaders externos: %d archivo(s) .ini copiados (revisa su licencia: los de PPSSPP suelen ser GPL)"
            % n_shader_sets)
    has_png_icon = write_icon(suite, os.path.join(app, "src", "main", "res"))

    sdk = paths.find_android_sdk()
    if sdk:
        with io.open(os.path.join(out, "local.properties"), "w", encoding="utf-8", newline="\n") as f:
            f.write("sdk.dir=%s\n" % sdk.replace("\\", "\\\\").replace(":", "\\:"))

    write_port_info(out, suite, report, midlet_class, sw, sh, why, orientation, args, package,
                    source_classes, has_png_icon)
    if not args.keep_work:
        shutil.rmtree(work, ignore_errors=True)
    log("")
    log("Listo. Abre la carpeta en Android Studio (File > Open > %s) y pulsa Run." % out)
    return out


def _port_items(midlet_class, sw, sh, args):
    return [
        ("midlet.class", midlet_class),
        ("# Resolución virtual del juego (lo que devuelven getWidth/getHeight)", None),
        ("screen.width", sw),
        ("screen.height", sh),
        ("# fit = proporción original | integer = pixel perfect | wide = 16:9 (deforma poco) | stretch = llenar", None),
        ("scale.mode", args.scale),
        ("# Si el juego solo dibuja una parte de la pantalla (p. ej. 176x208), mostrar solo esa parte y girar", None),
        ("auto.fit", "true"),
        ("# -1 = detectar al jugar | 0 = no girar | 90 / 270 = el juego dibuja de lado", None),
        ("rotate", -1 if args.rotate is None else args.rotate),
        ("# Filtros: uno de escalado (nearest | sharp | smooth | xbr | mmpx | aann) y efectos encima con +", None),
        ("# (crt | crt-lottes | scanlines | lcd | dot | mono | file:<sección .ini> | retro:<archivo .glsl>), p. ej. xbr+crt-lottes", None),
        ("shader", args.shader or ("smooth" if args.filter else "nearest")),
        ("# vsync = sincronizado | dynamic = la pantalla adapta su Hz a los FPS del juego | unlimited", None),
        ("fps.mode", args.fps_mode),
        ("# gamepad = mando (cruceta + botones + Select/Start) | full = teclado del teléfono | minimal | none", None),
        ("keypad", args.keypad),
        ("# Tamaño del mando en pantalla en % (su aspecto: imágenes de engine/src/main/assets/skin/;", None),
        ("# para cambiarlo pon las tuyas con el mismo nombre en app/src/main/assets/skin/)", None),
        ("pad.scale", 100),
        ("# Tecla del juego que manda cada control (-5 = OK, 53 = tecla 5, 48 = 0, 42 = *, 35 = #, -6/-7 = teclas", None),
        ("# izquierda/derecha, -1..-4 = flechas, 0 = nada). a b x y l r select start, cruceta du dd dl dr, joystick", None),
        ("# su sd sl sr; solo mando físico: l2 r2 l3 r3 y stick derecho ru rd rl rr", None),
        ("pad.map", "a=-5,b=55,x=57,y=48,l=42,r=35,select=-6,start=-7,du=-1,dd=-2,dl=-3,dr=-4,su=-1,sd=-2,sl=-3,sr=-4,"
                    "l2=49,r2=51,l3=53,r3=48,ru=50,rd=56,rl=52,rr=54"),
        ("# Controles ocultos: dpad,stick,a,b,x,y,l,r,select,start,toggle", None),
        ("pad.hide", ""),
        ("# true = imagen centrada en toda la pantalla (los controles quedan encima de los bordes)", None),
        ("center", "true"),
        ("# true = la primera vez el APK pregunta si mostrar el teclado (juegos con controles táctiles)", None),
        ("keypad.ask", "true" if args.keypad_ask else "false"),
        ("keypad.alpha", 150),
        ("keypad.haptics", "true"),
        ("# 0 = sin límite. Juegos antiguos suelen ir demasiado rápido: prueba 30", None),
        ("fps.limit", args.fps),
        ("show.fps", "false"),
        ("# true = mezcla cada fotograma con el anterior (juegos que parpadean para simular transparencias)", None),
        ("frame.blend", "false"),
        ("sound", "true"),
        ("vibration", "true"),
        ("text.antialias", "true"),
        ("# Tamaños de fuente en px (0 = automático)", None),
        ("font.small", 0),
        ("font.medium", 0),
        ("font.large", 0),
        ("microedition.platform", args.platform),
        ("# Códigos de las soft keys: Nokia/SE -6/-7, Motorola -21/-22, Siemens -1/-4, LG -202/-203", None),
        ("softkey.left", -6),
        ("softkey.right", -7),
        ("microedition.locale", "es-MX"),
        ("# menu = el gesto/botón Atrás abre el menú del port | softkey = Atrás es la tecla derecha del juego", None),
        ("back.action", "menu"),
    ]


def write_port_info(out, suite, report, midlet_class, sw, sh, why, orientation, args, package,
                    source_classes, has_png_icon):
    lines = [
        "# %s — port Android" % suite.name,
        "",
        "Generado por **j2a** (symbian-a-Android-port) a partir de `%s`." % os.path.basename(suite.jar_path),
        "",
        "| | |",
        "|---|---|",
        "| MIDlet | `%s` |" % midlet_class,
        "| Paquete Android | `%s` |" % package,
        "| Pantalla virtual | %dx%d (%s) |" % (sw, sh, why),
        "| Orientación | %s |" % orientation,
        "| Modo | %s |" % args.mode,
        "| Clases como código fuente | %d |" % len(source_classes),
        "| Icono | %s |" % ("PNG del MIDlet" if has_png_icon else "genérico"),
        "",
        "## Dónde está cada cosa",
        "",
        "- `app/src/main/java/` — código del juego descompilado (modo source/hybrid) y `MainActivity`.",
        "- `app/libs/game.jar` — clases que siguen como bytecode original (parcheado).",
        "- `app/src/main/resources/` — imágenes, sonidos y datos del JAR original.",
        "- `app/src/main/assets/j2a/port.properties` — resolución, escalado, teclado, FPS...",
        "- `engine/` — motor del port (gráficos OpenGL, audio, entrada, guardado) en el paquete `%s.engine`."
        % package,
        "",
        "## APIs",
        "",
    ]
    if report["unsupported"]:
        lines.append("Clases de plataforma **sin implementar** (el juego fallará si llega a usarlas):")
        lines.append("")
        for c in report["unsupported"]:
            lines.append("- `%s`" % c.replace("/", "."))
    else:
        lines.append("Todas las clases de plataforma que usa el juego están implementadas en el runtime.")
    if report["missing_members"]:
        lines += ["", "Métodos usados que faltan en el runtime:", ""]
        for c, ms in sorted(report["missing_members"].items()):
            lines.append("- `%s`: %s" % (c.replace("/", "."), ", ".join(ms)))
    lines += [
        "",
        "## Compilar",
        "",
        "1. Android Studio → File → Open → esta carpeta.",
        "2. Espera la sincronización de Gradle (si ofrece actualizar el Android Gradle Plugin, acepta).",
        "3. Run ▶ sobre un dispositivo o emulador. Para un APK: Build → Build APK(s).",
    ]
    with io.open(os.path.join(out, "PORT-INFO.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
