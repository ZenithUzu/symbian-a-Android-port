"""Locations of the repo pieces, the user cache, Java and the Android SDK."""
import glob
import os
import platform
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))


def runtime_dir():
    return os.path.join(REPO, "runtime")


def m3g_dir():
    """Optional native 3D module (Nokia M3G), only copied into ports of 3D games."""
    return os.path.join(REPO, "runtime-m3g")


def extra_dir():
    """Extra device APIs (vendor, sensors, MascotCapsule...) imported from J2ME Loader."""
    return os.path.join(REPO, "runtime-extra")


def java_source_roots():
    """All Java source trees of the engine (runtime + M3G bindings + extra APIs)."""
    return [os.path.join(runtime_dir(), "src", "main", "java"), os.path.join(m3g_dir(), "java"),
            os.path.join(extra_dir(), "java")]


def template_dir():
    return os.path.join(REPO, "template")


def cache_dir():
    d = os.environ.get("J2A_HOME") or os.path.join(os.path.expanduser("~"), ".j2a")
    os.makedirs(d, exist_ok=True)
    return d


def _exe(name):
    return name + (".exe" if platform.system() == "Windows" else "")


def studio_jbr_candidates():
    home = os.path.expanduser("~")
    c = []
    system = platform.system()
    if system == "Windows":
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"),
                     os.environ.get("LOCALAPPDATA", ""), r"C:\Program Files (x86)"):
            if base:
                c += glob.glob(os.path.join(base, "Android", "Android Studio*", "jbr"))
                c += glob.glob(os.path.join(base, "Programs", "Android Studio*", "jbr"))
        c += glob.glob(os.path.join(home, "AppData", "Local", "JetBrains", "Toolbox", "apps", "*android*", "*", "*", "jbr"))
    elif system == "Darwin":
        c += glob.glob("/Applications/Android Studio*.app/Contents/jbr/Contents/Home")
        c += glob.glob(os.path.join(home, "Applications", "Android Studio*.app", "Contents", "jbr", "Contents", "Home"))
    else:
        c += glob.glob(os.path.join(home, "android-studio*", "jbr"))
        c += glob.glob("/opt/android-studio*/jbr")
        c += glob.glob("/usr/local/android-studio*/jbr")
        c += glob.glob("/snap/android-studio/current/jbr")
    return c


def find_java_home():
    """Prefers JAVA_HOME, then Android Studio's bundled JBR, then PATH."""
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.environ["JAVA_HOME"])
    candidates += studio_jbr_candidates()
    for home in candidates:
        if os.path.isfile(os.path.join(home, "bin", _exe("java"))):
            return home
    java = shutil.which("java")
    if java:
        real = os.path.realpath(java)
        return os.path.dirname(os.path.dirname(real))
    return None


def java_bin(tool="java"):
    home = find_java_home()
    if home:
        p = os.path.join(home, "bin", _exe(tool))
        if os.path.isfile(p):
            return p
    return shutil.which(tool)


def find_android_sdk():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(var)
        if v and os.path.isdir(v):
            return v
    home = os.path.expanduser("~")
    for c in (os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
              os.path.join(home, "Library", "Android", "sdk"),
              os.path.join(home, "Android", "Sdk"),
              os.path.join(home, "Android", "sdk")):
        if c and os.path.isdir(c):
            return c
    return None


def find_android_jar():
    """android.jar of the newest installed platform (used to compile-check sources)."""
    if os.environ.get("J2A_ANDROID_JAR") and os.path.isfile(os.environ["J2A_ANDROID_JAR"]):
        return os.environ["J2A_ANDROID_JAR"]
    sdk = find_android_sdk()
    if not sdk:
        return None
    jars = glob.glob(os.path.join(sdk, "platforms", "android-*", "android.jar"))

    def level(p):
        name = os.path.basename(os.path.dirname(p)).split("-", 1)[1]
        digits = "".join(ch for ch in name if ch.isdigit())
        return int(digits) if digits else 0

    jars.sort(key=level)
    return jars[-1] if jars else None
