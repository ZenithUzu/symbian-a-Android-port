"""'j2a analyze': what is inside the JAR and how portable it is."""
import os
import re
from collections import Counter, defaultdict

from . import classfile, paths
from .jarinfo import MidletSuite

PLATFORM_PREFIXES = (
    "javax/microedition/", "com/nokia/", "com/samsung/", "com/siemens/", "com/motorola/",
    "com/sonyericsson/", "com/mascotcapsule/", "javax/wireless/", "javax/bluetooth/", "com/vodafone/",
    "com/sprintpcs/", "com/lge/", "mmpp/", "com/jblend/", "com/sun/midp/",
)

FAMILIES = [
    ("javax/microedition/lcdui/game/", "MIDP 2.0 Game API"),
    ("javax/microedition/lcdui/", "MIDP LCDUI (pantalla, teclado, formularios)"),
    ("javax/microedition/midlet/", "MIDlet (ciclo de vida)"),
    ("javax/microedition/rms/", "RMS (partidas guardadas)"),
    ("javax/microedition/media/", "MMAPI JSR-135 (sonido)"),
    ("javax/microedition/m3g/", "M3G JSR-184 (3D)"),
    ("javax/microedition/io/file/", "FileConnection JSR-75"),
    ("javax/microedition/io/", "GCF (red)"),
    ("javax/microedition/pim/", "PIM JSR-75"),
    ("javax/microedition/location/", "Location JSR-179"),
    ("javax/microedition/sensor/", "Sensores JSR-256"),
    ("javax/microedition/amms/", "AMMS JSR-234"),
    ("javax/wireless/messaging/", "SMS JSR-120"),
    ("javax/bluetooth/", "Bluetooth JSR-82"),
    ("com/nokia/mid/ui/", "Nokia UI API"),
    ("com/nokia/mid/sound/", "Nokia Sound API"),
    ("com/nokia/", "Otras APIs de Nokia"),
    ("com/mascotcapsule/", "MascotCapsule 3D"),
    ("com/samsung/", "Samsung API"),
    ("com/siemens/", "Siemens API"),
    ("com/motorola/", "Motorola API"),
    ("com/sonyericsson/", "Sony Ericsson API"),
    ("com/vodafone/", "Vodafone API"),
]


OBJECT_METHODS = {"<init>", "<clinit>", "hashCode", "equals", "toString", "getClass", "notify",
                  "notifyAll", "wait", "clone", "finalize"}


def family(cls):
    for prefix, label in FAMILIES:
        if cls.startswith(prefix):
            return label
    return "Otras APIs del fabricante"


def runtime_index():
    """Maps internal class name -> Java source text for the bundled runtime."""
    idx = {}
    root = os.path.join(paths.runtime_dir(), "src", "main", "java")
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                full = os.path.join(d, f)
                rel = os.path.relpath(full, root)[:-5].replace(os.sep, "/")
                with open(full, encoding="utf-8") as fh:
                    idx[rel] = fh.read()
    return idx


def analyze(jar, jad=None):
    suite = MidletSuite(jar, jad)
    rt = runtime_index()
    own = set(c[:-6] for c in suite.classes)
    versions = Counter()
    used = defaultdict(set)          # platform class -> classes using it
    missing_members = defaultdict(set)
    resource_calls = 0
    touch_classes = set()
    bundled_platform = []
    bad = []
    short_names = 0
    for entry in suite.classes:
        name = entry[:-6]
        if name.startswith(PLATFORM_PREFIXES):
            bundled_platform.append(name)
        simple = name.rsplit("/", 1)[-1].split("$")[0]
        if len(simple) <= 2:
            short_names += 1
        try:
            cf = classfile.ClassFile(suite.read(entry))
        except Exception as e:  # noqa: BLE001
            bad.append("%s (%s)" % (entry, e))
            continue
        versions["%d.%d" % (cf.major, cf.minor)] += 1
        if any(e and e[0] == 1 and e[1] == "pointerPressed" for e in cf.cp):
            touch_classes.add(name)
        for ref in cf.referenced_classes():
            top = ref.split("$")[0]
            if top.startswith(PLATFORM_PREFIXES) and top not in own:
                used[top].add(name)
        for ref in cf.method_refs():
            if not ref or not ref[0]:
                continue
            if ref == classfile.RESOURCE_TARGET:
                resource_calls += 1
            cls, meth, _desc = ref
            top = cls.split("$")[0]
            if top in rt and meth not in OBJECT_METHODS and top not in own:
                if not re.search(r"\b%s\s*\(" % re.escape(meth), rt[top]) and not inherited(rt, top, meth):
                    missing_members[top].add(meth)

    supported, unsupported = {}, {}
    for cls, users in sorted(used.items()):
        (supported if cls in rt else unsupported)[cls] = sorted(users)

    report = {
        "jar": suite.jar_path,
        "jad": suite.jad_path,
        "name": suite.name,
        "vendor": suite.vendor,
        "version": suite.version,
        "midlets": suite.midlets(),
        "profile": suite.attrs.get("MicroEdition-Profile", "?"),
        "configuration": suite.attrs.get("MicroEdition-Configuration", "?"),
        "classes": len(suite.classes),
        "resources": len(suite.resources),
        "class_versions": dict(versions),
        "obfuscated": bool(suite.classes) and short_names / float(len(suite.classes)) > 0.5,
        "supported": supported,
        "unsupported": unsupported,
        "missing_members": {k: sorted(v) for k, v in missing_members.items()},
        "resource_calls": resource_calls,
        "bundled_platform_classes": bundled_platform,
        "unreadable_classes": bad,
        "screen_hints": suite.screen_hints(),
        "orientation_hint": suite.orientation_hint(),
        "uses_network": any(c.startswith("javax/microedition/io/") for c in used),
        "touch": bool(touch_classes),
        "keypad_hint": keypad_hint(suite.attrs, bool(touch_classes)),
    }
    return report


def keypad_hint(attrs, touch):
    """(keypad mode or None, ask the player on first launch?) from the manifest and the code."""
    v = (attrs.get("Nokia-MIDlet-On-Screen-Keypad") or "").strip().lower()
    if v == "no":
        return "none", False  # the game draws its own touch controls
    if v in ("gameactions", "navigationkeys"):
        return "minimal", False
    if (attrs.get("MIDlet-Touch-Support") or "").strip().lower() == "true" or touch:
        return None, True  # touch-aware: may or may not need keys -> let the player choose
    return None, False


def inherited(rt, cls, meth):
    """Very small superclass walk inside the runtime sources (extends X)."""
    seen = set()
    while cls in rt and cls not in seen:
        seen.add(cls)
        m = re.search(r"\bextends\s+([\w.]+)", rt[cls])
        if not m:
            return False
        parent = m.group(1)
        pkg = cls.rsplit("/", 1)[0]
        candidates = [parent.replace(".", "/"), pkg + "/" + parent]
        imp = re.search(r"import\s+([\w.]+\.%s);" % re.escape(parent), rt[cls])
        if imp:
            candidates.insert(0, imp.group(1).replace(".", "/"))
        nxt = next((c for c in candidates if c in rt), None)
        if not nxt:
            return False
        if re.search(r"\b%s\s*\(" % re.escape(meth), rt[nxt]):
            return True
        cls = nxt
    return False


def print_report(r):
    p = print
    p("")
    p("=== %s %s (%s) ===" % (r["name"], r["version"], r["vendor"] or "sin fabricante"))
    p("JAR: %s" % r["jar"])
    if r["jad"]:
        p("JAD: %s" % r["jad"])
    p("Perfil: %s / %s" % (r["profile"], r["configuration"]))
    p("Clases: %d   Recursos: %d   Versiones de bytecode: %s" % (
        r["classes"], r["resources"], ", ".join("%s x%d" % kv for kv in sorted(r["class_versions"].items()))))
    p("Ofuscado: %s" % ("sí (nombres tipo a, b, c)" if r["obfuscated"] else "no parece"))
    p("")
    p("MIDlets:")
    for i, m in enumerate(r["midlets"], 1):
        p("  %d. %s -> %s" % (i, m["name"], m["class"]))
    if not r["midlets"]:
        p("  (ninguno declarado: usa --midlet-class)")
    p("")
    p("APIs de plataforma usadas:")
    by_family = defaultdict(lambda: [[], []])
    for c in r["supported"]:
        by_family[family(c)][0].append(c)
    for c in r["unsupported"]:
        by_family[family(c)][1].append(c)
    for fam, (ok, ko) in sorted(by_family.items()):
        status = "OK" if not ko else ("PARCIAL" if ok else "NO SOPORTADA")
        p("  [%s] %s (%d clases)" % (status, fam, len(ok) + len(ko)))
        for c in ko:
            p("        falta: %s" % c.replace("/", "."))
    if r["missing_members"]:
        p("")
        p("Métodos usados que el runtime todavía no implementa:")
        for c, ms in sorted(r["missing_members"].items()):
            p("  %s: %s" % (c.replace("/", "."), ", ".join(ms)))
    p("")
    if r["resource_calls"]:
        p("Clases que llaman a getResourceAsStream: %d (se parchean automáticamente)" % r["resource_calls"])
    if r["bundled_platform_classes"]:
        p("El JAR trae %d clases de plataforma propias (se descartan, usa el runtime): %s" % (
            len(r["bundled_platform_classes"]), ", ".join(r["bundled_platform_classes"][:5])))
    if r["unreadable_classes"]:
        p("Clases ilegibles: %s" % ", ".join(r["unreadable_classes"][:5]))
    if r["screen_hints"]:
        p("Pistas de resolución: " + "; ".join("%dx%d (%s)" % h for h in r["screen_hints"]))
    else:
        p("Sin pistas de resolución: se usará 240x320 (cámbialo con --screen)")
    mode, ask = r["keypad_hint"]
    if mode == "none":
        p("Táctil: el juego trae sus propios controles en pantalla -> sin teclado virtual")
    elif mode:
        p("Táctil: el juego pide solo cruceta en pantalla -> teclado mínimo")
    elif ask:
        p("Táctil: el juego acepta toques -> el APK preguntará la primera vez si mostrar el teclado")
    if r["uses_network"]:
        p("Usa red (javax.microedition.io): se añadirá el permiso INTERNET")
    p("")
    verdict = "Portable" if not r["unsupported"] else (
        "Portable con limitaciones" if r["supported"] else "Requiere implementar APIs")
    p("Veredicto: %s" % verdict)
