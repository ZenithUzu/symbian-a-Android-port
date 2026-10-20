"""Reads a J2ME MIDlet suite: JAR entries, MANIFEST.MF and optional JAD."""
import os
import re
import struct
import zipfile
from collections import Counter

COMMON_SCREENS = [
    (128, 128), (128, 160), (176, 208), (176, 220), (208, 208), (240, 320), (320, 240),
    (352, 416), (240, 400), (400, 240), (360, 640), (640, 360), (320, 480), (480, 320), (480, 800),
]


def parse_attributes(text):
    """Parses MANIFEST.MF / JAD 'Key: Value' text (with manifest continuation lines)."""
    attrs = {}
    last = None
    for raw in text.splitlines():
        if raw.startswith(" ") and last:
            attrs[last] += raw[1:]
            continue
        if ":" not in raw:
            continue
        k, v = raw.split(":", 1)
        k = k.strip()
        if not k:
            continue
        attrs[k] = v.strip()
        last = k
    return attrs


def decode(data):
    for enc in ("utf-8", "cp1251", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            pass
    return data.decode("latin-1", "replace")


def png_size(data):
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    return None


class MidletSuite:
    def __init__(self, jar_path, jad_path=None):
        self.jar_path = os.path.abspath(jar_path)
        self.zip = zipfile.ZipFile(self.jar_path)
        self.entries = [i for i in self.zip.infolist() if not i.is_dir()]
        self.classes = [i.filename for i in self.entries if i.filename.endswith(".class")]
        self.resources = [i.filename for i in self.entries
                          if not i.filename.endswith(".class") and not i.filename.upper().startswith("META-INF/")]
        manifest = {}
        for name in ("META-INF/MANIFEST.MF", "meta-inf/manifest.mf"):
            try:
                manifest = parse_attributes(decode(self.zip.read(name)))
                break
            except KeyError:
                pass
        self.manifest = manifest
        if jad_path is None:
            guess = os.path.splitext(self.jar_path)[0] + ".jad"
            jad_path = guess if os.path.exists(guess) else None
        self.jad_path = jad_path
        self.jad = {}
        if jad_path:
            with open(jad_path, "rb") as f:
                self.jad = parse_attributes(decode(f.read()))
        self.attrs = dict(manifest)
        self.attrs.update(self.jad)  # JAD wins, like on a real phone

    # ------------------------------------------------------------- attributes
    def midlets(self):
        out = []
        i = 1
        while ("MIDlet-%d" % i) in self.attrs:
            parts = [p.strip() for p in self.attrs["MIDlet-%d" % i].split(",")]
            while len(parts) < 3:
                parts.append("")
            out.append({"name": parts[0], "icon": parts[1], "class": parts[2]})
            i += 1
        return out

    @property
    def name(self):
        return self.attrs.get("MIDlet-Name") or os.path.splitext(os.path.basename(self.jar_path))[0]

    @property
    def vendor(self):
        return self.attrs.get("MIDlet-Vendor", "")

    @property
    def version(self):
        return self.attrs.get("MIDlet-Version", "1.0")

    def read(self, name):
        return self.zip.read(name)

    def icon_data(self):
        candidates = [self.attrs.get("MIDlet-Icon", "")]
        candidates += [m["icon"] for m in self.midlets()]
        for c in candidates:
            c = (c or "").strip().lstrip("/")
            if not c:
                continue
            for e in self.entries:
                if e.filename.lower() == c.lower():
                    return self.zip.read(e.filename)
        return None

    # --------------------------------------------------------------- screens
    def screen_hints(self):
        """Returns a list of (width, height, reason) ordered by confidence."""
        hints = []
        for key in ("Nokia-MIDlet-Original-Display-Size", "Nokia-MIDlet-Target-Display-Size",
                    "MIDlet-Screen-Size", "J2A-Screen-Size"):
            v = self.attrs.get(key)
            if v:
                m = re.search(r"(\d{2,4})\s*[,x*]\s*(\d{2,4})", v)
                if m:
                    hints.append((int(m.group(1)), int(m.group(2)), key))
        base = os.path.basename(self.jar_path)
        m = re.search(r"(?<!\d)(\d{3})\s*[xX*_-]\s*(\d{3})(?!\d)", base)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
            if 96 <= w <= 1024 and 96 <= h <= 1024:
                hints.append((w, h, "nombre del archivo"))
        sizes = Counter()
        for e in self.entries:
            if e.filename.lower().endswith(".png") and e.file_size < 400000:
                try:
                    s = png_size(self.zip.read(e.filename)[:32])
                except Exception:
                    s = None
                if s and s in COMMON_SCREENS:
                    sizes[s] += 1
        for (w, h), n in sizes.most_common(2):
            hints.append((w, h, "%d imagen(es) del tamaño de pantalla" % n))
        return hints

    def orientation_hint(self):
        v = (self.attrs.get("Nokia-MIDlet-App-Orientation") or "").lower()
        if v in ("landscape", "portrait"):
            return v
        return None
