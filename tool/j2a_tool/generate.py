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


def sanitize_package(text):
    parts = []
    for seg in re.split(r"[^A-Za-z0-9]+", text.lower()):
        if not seg:
            continue
        if seg[0].isdigit():
            seg = "g" + seg
        if seg in JAVA_KEYWORDS:
            seg += "_"
        parts.append(seg)
    return ".".join(parts[:3]) or "game"


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
    orientation = args.orientation or report["orientation_hint"] or ("landscape" if sw > sh else "portrait")
    package = args.package or "port." + sanitize_package(suite.name)
    if not re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$", package):
        raise SystemExit("Nombre de paquete inválido: %s" % package)

    out = os.path.abspath(args.output or sanitize_package(suite.name).replace(".", "_") + "-android")
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
        if n_fixed:
            log("  Ajustes automáticos de sintaxis en %d archivo(s)" % n_fixed)
        if args.no_verify:
            log("  Verificación de compilación omitida (--no-verify)")
        elif not rt_jar:
            log("  Aviso: no encontré el Android SDK/javac; no puedo verificar que el código compile.")
            log("         Si Android Studio marca errores en una clase, vuelve a generar con")
            log("         --mode hybrid o --keep-bytecode Clase para dejarla como bytecode.")
        else:
            for attempt in range(6):
                check_dir = os.path.join(work, "check-src")
                shutil.rmtree(check_dir, ignore_errors=True)
                for c in source_classes:
                    dst = os.path.join(check_dir, c.replace("/", os.sep) + ".java")
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(files[c], dst)
                bad = decompile.compile_check(check_dir, android_jar, [rt_jar, patched_all])
                shutil.rmtree(check_dir, ignore_errors=True)
                if not bad:
                    log("  Verificación: las %d clases en código fuente compilan" % len(source_classes))
                    break
                log("  Verificación %d: %d clase(s) con errores de compilación -> bytecode (%s)" % (
                    attempt + 1, len(bad), ", ".join(sorted(bad)[:8])))
                source_classes -= bad
        if args.keep_bytecode:
            keep = set(c.strip().replace(".", "/") for c in args.keep_bytecode.split(",") if c.strip())
            source_classes -= keep

    # ------------------------------------------------------- template + runtime
    tokens = {
        "@@APP_NAME@@": xml_escape(app_name),
        "@@PROJECT_NAME@@": re.sub(r"[^A-Za-z0-9 _-]", "", app_name) or "J2ME Port",
        "@@PACKAGE@@": package,
        "@@PACKAGE_PATH@@": package.replace(".", os.sep),
        "@@VERSION_NAME@@": suite.version,
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
    rt_out = os.path.join(out, "j2me-runtime")
    shutil.copytree(paths.runtime_dir(), rt_out, ignore=shutil.ignore_patterns("build", ".gradle"))

    app = os.path.join(out, "app")
    # --------------------------------------------------------------- game code
    libs_dir = os.path.join(app, "libs")
    os.makedirs(libs_dir, exist_ok=True)
    if args.mode == "jar":
        shutil.copyfile(patched_all, os.path.join(libs_dir, "game.jar"))
    else:
        java_root = os.path.join(app, "src", "main", "java")
        files = decompile.java_files(src_tmp)
        for c in sorted(source_classes):
            dst = os.path.join(java_root, c.replace("/", os.sep) + ".java")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(files[c], dst)
        if game_jar_needed:
            st = decompile.patch_jar(suite.jar_path, os.path.join(libs_dir, "game.jar"), exclude=source_classes)
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
    write_props(os.path.join(assets, "port.properties"), _port_items(midlet_class, sw, sh, args),
                "Configuración del port (editable). El jugador puede cambiar algunas opciones\n"
                "desde el menú del port (botón ☰ del teclado virtual).")
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
        ("# fit = mantener proporción | integer = escalado entero (pixel perfect) | stretch = llenar", None),
        ("scale.mode", args.scale),
        ("scale.filter", "true" if args.filter else "false"),
        ("# full = cruceta + teclado numérico | minimal = cruceta + OK | none", None),
        ("keypad", args.keypad),
        ("keypad.alpha", 150),
        ("keypad.haptics", "true"),
        ("# 0 = sin límite. Juegos antiguos suelen ir demasiado rápido: prueba 30", None),
        ("fps.limit", args.fps),
        ("show.fps", "false"),
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
        ("# softkey = Atrás actúa como tecla derecha | menu = Atrás abre el menú del port", None),
        ("back.action", "softkey"),
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
        "- `j2me-runtime/` — la implementación de J2ME sobre Android (javax.microedition.*, Nokia, Samsung).",
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
