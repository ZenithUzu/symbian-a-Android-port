"""Command line interface of j2a."""
import argparse
import json
import os
import platform
import sys

from . import analyze, decompile, generate, paths

DESCRIPTION = """j2a — convierte juegos J2ME (Symbian / Series 40 / .jar) en proyectos nativos de Android Studio.

Comandos:
  analyze    Muestra qué hay en el JAR y qué APIs usa (y si están soportadas)
  decompile  Descompila el JAR a código Java legible (Vineflower o CFR)
  port       Genera el proyecto Android Studio completo
  patch      Solo parchea el bytecode (getResourceAsStream) y escribe un JAR nuevo
  verify     Compila un proyecto generado con javac para detectar errores rápido
  doctor     Comprueba Java, Android SDK y descompiladores
"""


def cmd_analyze(a):
    r = analyze.analyze(a.jar, a.jad)
    if a.json:
        print(json.dumps(r, indent=2, ensure_ascii=False))
    else:
        analyze.print_report(r)


def cmd_decompile(a):
    out = os.path.abspath(a.output or os.path.splitext(os.path.basename(a.jar))[0] + "-src")
    tmp_jar = os.path.join(paths.cache_dir(), "last-patched.jar")
    src = a.jar
    if not a.no_patch:
        decompile.patch_jar(a.jar, tmp_jar)
        src = tmp_jar
    libs = []
    if not a.no_runtime:
        rt = decompile.runtime_classes_jar(paths.find_android_jar())
        if rt:
            libs.append(rt)
    decompile.decompile(src, out, a.engine, libs=libs)
    res_dir = os.path.join(out, "resources")
    os.makedirs(res_dir, exist_ok=True)
    n = len(decompile.extract_resources(a.jar, res_dir))
    failed = decompile.failed_classes(out)
    print("Código en %s (%d clases, %d recursos en resources/)" % (
        out, len(decompile.java_files(out)), n))
    if failed:
        print("Clases con fallos de descompilación: %s" % ", ".join(sorted(failed)))


def cmd_patch(a):
    out = a.output or os.path.splitext(a.jar)[0] + "-patched.jar"
    st = decompile.patch_jar(a.jar, out)
    print("%s: %d clases, %d parcheadas, %d de plataforma descartadas" % (
        out, st["classes"], len(st["patched"]), len(st["dropped_platform"])))


def cmd_port(a):
    generate.port(a)


def cmd_verify(a):
    """Compiles a generated project with javac (no Gradle) to catch errors quickly."""
    import shutil
    import subprocess
    import tempfile
    aj = a.android_jar or paths.find_android_jar()
    javac = paths.java_bin("javac")
    if not aj or not javac:
        sys.exit("Necesito javac y un android.jar (instala una plataforma en el SDK o usa --android-jar)")
    def sources(sub):
        out = []
        for d, _, fs in os.walk(os.path.join(a.project, sub)):
            out += [os.path.join(d, f) for f in fs if f.endswith(".java")]
        return out

    def javac_run(files, args, tmp, name):
        argfile = os.path.join(tmp, name + ".txt")
        with open(argfile, "w", encoding="utf-8") as f:
            f.write("\n".join('"%s"' % s.replace("\\", "/") for s in files))
        r = subprocess.run([javac, "-nowarn", "-encoding", "UTF-8"] + args + ["@" + argfile],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stderr[-6000:])
            sys.exit("Fallo al compilar %s: %d errores" % (name, sum("error" in l for l in r.stderr.splitlines())))

    engine, app = sources("engine/src/main/java"), sources("app/src/main/java")
    game_jar = [p for p in [os.path.join(a.project, "app", "libs", "game.jar")] if os.path.isfile(p)]
    tmp = tempfile.mkdtemp(prefix="j2a-verify-")
    try:
        eng_out = os.path.join(tmp, "engine")
        # the engine module is Java 17, the game (app module) Java 8, as in the Gradle build
        javac_run(engine, ["--release", "17", "-cp", aj, "-d", eng_out], tmp, "engine")
        javac_run(app, ["-source", "8", "-target", "8", "-bootclasspath", aj,
                        "-cp", os.pathsep.join([eng_out] + game_jar), "-d", os.path.join(tmp, "app")], tmp, "app")
        print("OK: %d archivos Java compilan contra %s" % (len(engine) + len(app), aj))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def cmd_doctor(a):
    ok = True
    print("Python: %s (%s)" % (platform.python_version(), sys.executable))
    home = paths.find_java_home()
    java, javac = paths.java_bin("java"), paths.java_bin("javac")
    print("Java:   %s" % (java or "NO ENCONTRADO"))
    print("javac:  %s" % (javac or "NO ENCONTRADO (necesario para verificar el código fuente)"))
    if home:
        print("        JAVA_HOME detectado: %s" % home)
    ok &= bool(java)
    sdk = paths.find_android_sdk()
    print("Android SDK: %s" % (sdk or "NO ENCONTRADO (define ANDROID_HOME)"))
    aj = paths.find_android_jar()
    print("android.jar: %s" % (aj or "NO ENCONTRADO (instala una plataforma con el SDK Manager)"))
    for name, (_url, fname) in decompile.DECOMPILERS.items():
        p = os.path.join(paths.cache_dir(), "tools", fname)
        print("%-11s %s" % (name + ":", p if os.path.isfile(p) else "se descargará al usarlo"))
    print("Runtime J2ME: %s" % paths.runtime_dir())
    print("3D (M3G):     %s (los juegos 3D necesitan el NDK: Android Studio > SDK Manager > SDK Tools)"
          % paths.m3g_dir())
    print("")
    print("Todo listo." if ok else "Falta Java: instala Android Studio o un JDK 17+.")


def main(argv=None):
    p = argparse.ArgumentParser(prog="j2a", description=DESCRIPTION,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd")

    s = sub.add_parser("analyze", help="analiza un JAR")
    s.add_argument("jar")
    s.add_argument("--jad")
    s.add_argument("--json", action="store_true", help="salida en JSON")
    s.set_defaults(func=cmd_analyze)

    s = sub.add_parser("decompile", help="descompila un JAR a Java")
    s.add_argument("jar")
    s.add_argument("-o", "--output")
    s.add_argument("--engine", choices=sorted(decompile.DECOMPILERS), default="vineflower")
    s.add_argument("--no-patch", action="store_true", help="no parchear getResourceAsStream antes")
    s.add_argument("--no-runtime", action="store_true", help="no pasar las clases del runtime al descompilador")
    s.set_defaults(func=cmd_decompile)

    s = sub.add_parser("patch", help="parchea el bytecode para Android")
    s.add_argument("jar")
    s.add_argument("-o", "--output")
    s.set_defaults(func=cmd_patch)

    s = sub.add_parser("port", help="genera el proyecto Android Studio")
    s.add_argument("jar")
    s.add_argument("--jad", help="archivo .jad (se busca automáticamente junto al .jar)")
    s.add_argument("-o", "--output", help="carpeta del proyecto (por defecto <nombre>-android)")
    s.add_argument("--mode", choices=["source", "jar", "hybrid"], default="source",
                   help="source: código editable (lo que no compile queda en bytecode); "
                        "jar: bytecode original (máxima fidelidad); hybrid: solo --edit como código")
    s.add_argument("--edit", help="modo hybrid: clases a descompilar, separadas por comas")
    s.add_argument("--keep-bytecode", help="clases que deben quedarse como bytecode aunque compilen")
    s.add_argument("--engine", choices=sorted(decompile.DECOMPILERS), default="vineflower")
    s.add_argument("--no-verify", action="store_true", help="no compilar para verificar el código descompilado")
    s.add_argument("--package", help="applicationId, p. ej. com.miestudio.juego")
    s.add_argument("--name", help="nombre visible de la app")
    s.add_argument("--midlet", type=int, default=1, help="qué MIDlet-N lanzar si el JAR trae varios")
    s.add_argument("--midlet-class", help="clase MIDlet si el manifiesto no la declara")
    s.add_argument("--screen", help="resolución virtual, p. ej. 240x320, 176x208, 360x640")
    s.add_argument("--orientation", choices=["portrait", "landscape", "auto"])
    s.add_argument("--rotate", type=int, choices=[0, 90, 270],
                   help="girar la imagen (juegos que dibujan de lado); por defecto se detecta al jugar")
    s.add_argument("--scale", choices=["fit", "integer", "wide", "stretch"], default="fit",
                   help="fit: proporción original; integer: pixel perfect; wide: 16:9 (llena casi toda la "
                        "pantalla deformando poco); stretch: llenar todo")
    s.add_argument("--filter", action="store_true", help="atajo de --shader smooth")
    s.add_argument("--shader", help="shader por defecto: nearest, smooth, sharp, scanlines, lcd, crt, mono "
                                    "o file:<sección .ini de --shaders>")
    s.add_argument("--shaders", help="carpeta con shaders formato PPSSPP (.ini/.fsh/.vsh) para incluir en el APK")
    s.add_argument("--fps-mode", choices=["vsync", "dynamic", "unlimited"], default="vsync",
                   help="vsync: sincronizado; dynamic: la pantalla adapta sus Hz a los FPS del juego")
    s.add_argument("--keep-j2me-packages", action="store_true",
                   help="no renombrar javax.microedition.* al paquete propio del motor del port")
    s.add_argument("--keypad", choices=["full", "minimal", "none"],
                   help="por defecto se decide según el juego (los táctiles preguntan al jugador)")
    s.add_argument("--fps", type=int, default=0, help="límite de FPS (0 = sin límite)")
    s.add_argument("--platform", default="Nokia6300/07.21", help="valor de microedition.platform")
    s.add_argument("--force", action="store_true", help="sobrescribir la carpeta de salida")
    s.add_argument("--keep-work", action="store_true", help="conservar j2a-work/ (JAR parcheado y descompilado)")
    s.set_defaults(func=cmd_port)

    s = sub.add_parser("verify", help="compila un proyecto generado con javac (sin Gradle)")
    s.add_argument("project")
    s.add_argument("--android-jar")
    s.set_defaults(func=cmd_verify)

    s = sub.add_parser("doctor", help="comprueba el entorno")
    s.set_defaults(func=cmd_doctor)

    a = p.parse_args(argv)
    if not getattr(a, "func", None):
        p.print_help()
        return 1
    a.func(a)
    return 0
