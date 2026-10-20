"""Bytecode patching, decompilation (Vineflower / CFR) and compile checks."""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

from . import classfile, paths

DECOMPILERS = {
    "vineflower": ("https://github.com/Vineflower/vineflower/releases/download/1.12.0/vineflower-1.12.0.jar",
                   "vineflower-1.12.0.jar"),
    "cfr": ("https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar", "cfr-0.152.jar"),
}

# Packages the runtime provides: copies bundled inside a game JAR are dropped.
RUNTIME_PACKAGES = ("javax/microedition/", "com/nokia/mid/", "com/samsung/util/", "j2a/")

FAILURE_MARKERS = ("$VF: Couldn't be decompiled", "$FF: Couldn't be decompiled", "<undefinedtype>",
                   "<unrepresentable>", "<unknown>", "This method has failed to decompile",
                   "Exception decompiling", "$VF: Unable to")


def log(msg):
    print(msg, flush=True)


def ensure_tool(name):
    url, fname = DECOMPILERS[name]
    d = os.path.join(paths.cache_dir(), "tools")
    os.makedirs(d, exist_ok=True)
    dest = os.path.join(d, fname)
    if os.path.isfile(dest) and os.path.getsize(dest) > 100000:
        return dest
    log("Descargando %s ..." % fname)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)
    return dest


def require_java(tool="java"):
    exe = paths.java_bin(tool)
    if not exe:
        sys.exit("No encontré '%s'. Instala Android Studio (trae su propio JDK en la carpeta jbr) "
                 "o define JAVA_HOME." % tool)
    return exe


# ---------------------------------------------------------------- patching

def patch_jar(src_jar, out_jar, exclude=()):
    """Writes a classes-only JAR with resource calls patched.

    exclude: set of internal class names (without .class) to leave out.
    Returns dict with statistics.
    """
    stats = {"patched": [], "dropped_platform": [], "excluded": [], "classes": 0}
    with zipfile.ZipFile(src_jar) as zin, zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zout:
        seen = set()
        for info in zin.infolist():
            name = info.filename
            if not name.endswith(".class") or name in seen:
                continue
            seen.add(name)
            internal = name[:-6]
            if internal.startswith(RUNTIME_PACKAGES):
                stats["dropped_platform"].append(internal)
                continue
            top = internal.split("$")[0]
            if internal in exclude or top in exclude:
                stats["excluded"].append(internal)
                continue
            data = zin.read(name)
            try:
                patched = classfile.ClassFile(data).patch_resource_calls()
            except Exception:  # noqa: BLE001 - leave odd classes untouched
                patched = None
            if patched:
                data = patched
                stats["patched"].append(internal)
            zout.writestr(name, data)
            stats["classes"] += 1
    return stats


def extract_resources(src_jar, out_dir):
    """Copies non-class entries (images, sounds, levels...) and returns their names."""
    names = []
    with zipfile.ZipFile(src_jar) as z:
        for info in z.infolist():
            n = info.filename
            if info.is_dir() or n.endswith(".class") or n.upper().startswith("META-INF/"):
                continue
            safe = os.path.normpath(n)
            if safe.startswith("..") or os.path.isabs(safe):
                continue
            dest = os.path.join(out_dir, safe)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(z.read(n))
            names.append(n)
    return names


# ---------------------------------------------------------- runtime classes

def runtime_classes_jar(android_jar):
    """Compiles the j2a runtime once (cached) so decompilers and javac can resolve J2ME types."""
    if not android_jar:
        return None
    javac = paths.java_bin("javac")
    if not javac:
        return None
    src_root = os.path.join(paths.runtime_dir(), "src", "main", "java")
    files = []
    h = hashlib.sha1(android_jar.encode())
    for d, _, fs in os.walk(src_root):
        for f in sorted(fs):
            if f.endswith(".java"):
                p = os.path.join(d, f)
                files.append(p)
                with open(p, "rb") as fh:
                    h.update(fh.read())
    out = os.path.join(paths.cache_dir(), "runtime-%s.jar" % h.hexdigest()[:12])
    if os.path.isfile(out):
        return out
    tmp = tempfile.mkdtemp(prefix="j2a-rt-")
    try:
        argfile = os.path.join(tmp, "sources.txt")
        with open(argfile, "w", encoding="utf-8") as f:
            f.write("\n".join('"%s"' % p.replace("\\", "/") for p in files))
        cls_dir = os.path.join(tmp, "classes")
        cmd = [javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
               "-bootclasspath", android_jar, "-d", cls_dir, "@" + argfile]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            log("Aviso: no pude compilar el runtime para la verificación:\n" + r.stderr[-2000:])
            return None
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for d, _, fs in os.walk(cls_dir):
                for f in fs:
                    p = os.path.join(d, f)
                    z.write(p, os.path.relpath(p, cls_dir).replace(os.sep, "/"))
        return out
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------------------- decompiling

def _subset_jar(jar, only, dest):
    """JAR with only the requested top-level classes (and their inner classes)."""
    wanted = set(only)
    found = set()
    with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in zin.namelist():
            if n.endswith(".class"):
                top = n[:-6].split("$")[0]
                if top in wanted:
                    zout.writestr(n, zin.read(n))
                    found.add(top)
    missing = wanted - found
    if missing:
        log("Aviso: no existen en el JAR: %s" % ", ".join(sorted(missing)))
    return dest


def decompile(jar, out_dir, engine="vineflower", libs=(), only=None):
    """Decompiles 'jar' into out_dir (a source tree). 'only' = iterable of internal class names."""
    java = require_java()
    tool = ensure_tool(engine)
    os.makedirs(out_dir, exist_ok=True)
    libs = list(libs)
    tmp = None
    if only:
        # Decompile a subset JAR; the full JAR is still given as a library for type resolution.
        tmp = tempfile.mkdtemp(prefix="j2a-only-")
        libs.append(jar)
        jar = _subset_jar(jar, only, os.path.join(tmp, "subset.jar"))
    if engine == "vineflower":
        cmd = [java, "-jar", tool, "--folder", "--silent", "--variable-renaming=jad",
               "--decompile-generics=false", "--log-level=error", "--include-runtime=current"]
        for lib in libs:
            cmd.append("-e=" + lib)
        cmd += [jar, out_dir]
    else:
        cmd = [java, "-jar", tool, jar, "--outputdir", out_dir, "--silent", "true", "--comments", "false"]
        if libs:
            cmd += ["--extraclasspath", os.pathsep.join(libs)]
    log("Descompilando con %s ..." % engine)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True)
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)
    if r.returncode != 0:
        log(r.stdout[-3000:])
        log(r.stderr[-3000:])
        raise RuntimeError("El descompilador terminó con error (%d)" % r.returncode)
    # CFR writes a summary file; Vineflower may copy resources: keep only .java
    for d, _, fs in os.walk(out_dir):
        for f in fs:
            if not f.endswith(".java"):
                os.remove(os.path.join(d, f))
    return out_dir


def java_files(src_dir):
    out = {}
    for d, _, fs in os.walk(src_dir):
        for f in fs:
            if f.endswith(".java"):
                p = os.path.join(d, f)
                out[os.path.relpath(p, src_dir)[:-5].replace(os.sep, "/")] = p
    return out


def failed_classes(src_dir):
    """Top-level classes whose source contains decompiler failure markers."""
    bad = set()
    for cls, p in java_files(src_dir).items():
        with open(p, encoding="utf-8", errors="replace") as f:
            text = f.read()
        if any(m in text for m in FAILURE_MARKERS):
            bad.add(cls)
    return bad


# ---------------------------------------------------------------- fixups

def fix_sources(src_dir):
    """Small textual fixes for code written for Java 1.1-1.4 compilers."""
    changed = 0
    for cls, p in java_files(src_dir).items():
        with open(p, encoding="utf-8", errors="replace") as f:
            text = f.read()
        orig = text
        # identifiers that became keywords after Java 1.4 (e.g. a variable called 'enum')
        for kw in ("enum", "assert"):
            text = re.sub(r"(?<![\w.\"])\b%s\b(?=\s*(=|;|\)|,|\.|\[))" % kw, kw + "_", text)
        if text != orig:
            changed += 1
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)
    return changed


def compile_check(src_dir, android_jar, classpath):
    """Compiles the sources; returns the set of top-level classes with errors (or None if no javac)."""
    javac = paths.java_bin("javac")
    if not javac or not android_jar:
        return None
    files = java_files(src_dir)
    if not files:
        return set()
    tmp = tempfile.mkdtemp(prefix="j2a-check-")
    try:
        argfile = os.path.join(tmp, "sources.txt")
        with open(argfile, "w", encoding="utf-8") as f:
            f.write("\n".join('"%s"' % p.replace("\\", "/") for p in files.values()))
        cmd = [javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xmaxerrs", "100000",
               "-bootclasspath", android_jar, "-cp", os.pathsep.join(classpath),
               "-d", os.path.join(tmp, "out"), "@" + argfile]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0:
            return set()
        bad = set()
        by_path = {os.path.normcase(os.path.abspath(p)): c for c, p in files.items()}
        for line in r.stderr.splitlines():
            m = re.match(r"(.+?\.java):\d+: error", line)
            if m:
                c = by_path.get(os.path.normcase(os.path.abspath(m.group(1))))
                if c:
                    bad.add(c)
        if not bad:
            log(r.stderr[-2000:])
            bad = set(files)  # unknown failure: be safe
        return bad
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
