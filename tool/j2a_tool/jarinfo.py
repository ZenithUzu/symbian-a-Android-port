"""Reads a J2ME MIDlet suite: JAR entries, MANIFEST.MF and optional JAD."""
import os
import re
import struct
import zipfile
from collections import Counter

# Same list as the APK's resolution menu (j2a.core.Host.RESOLUTIONS)
COMMON_SCREENS = [
    (96, 65), (101, 80), (128, 128), (128, 160), (132, 176), (176, 204), (176, 208), (176, 220),
    (208, 208), (240, 260), (240, 320), (320, 240), (240, 400), (400, 240), (240, 432), (352, 416),
    (320, 480), (480, 320), (360, 640), (640, 360), (480, 640), (640, 480), (480, 800), (800, 480),
]

# Phone models / platform names often found in JAR/JAD names ("Juego_N95.jar", "s60v3").
DEVICE_SCREENS = [
    (128, 128, "3100 3200 3220 3300 5100 5140 6020 6100 6220 6230 6610 7210 7250 2600"),
    (128, 160, "6101 6103 6070 6080 6085 2610 2626 2630 2760 3110c 5070 5200 7360 k310 k510 s40v2"),
    (176, 208, "3230 3650 3660 6260 6600 6620 6630 6670 6680 6681 7610 7650 n70 n72 ngage n-gage qd s60v2"),
    (176, 220, "k700 k750 w800 w810 k610 w550 w700 z520"),
    (208, 208, "6230i 6822 7270 8800"),
    (240, 320, "n73 n76 n77 n78 n79 n81 n82 n85 n86 n95 n96 e65 e66 5310 5320 5610 5630 5700 6120 6121 "
               "6124 6210 6290 6300 6303 6500 6700 2700 3120 k800 w580 w910 k850 s60v3 s40v5 s40v6"),
    (320, 240, "e61 e62 e63 e71 e72 e5 c3"),
    (352, 416, "n80 n90 n92 e60 e70"),
    (360, 640, "5800 5230 5530 n97 n8 x6 c6 c7 s60v5 symbian3 anna belle"),
]


def int_constants(code_bytes, ranges, cp):
    """Integer literals used in the methods of a class (bipush/sipush/ldc of an Integer)."""
    import struct as _s
    from .classfile import _walk
    out = set()
    for start, length in ranges:
        for pc, op in _walk(code_bytes, start, length):
            p = start + pc
            if op == 0x10:
                out.add(_s.unpack(">b", code_bytes[p + 1:p + 2])[0])
            elif op == 0x11:
                out.add(_s.unpack(">h", code_bytes[p + 1:p + 3])[0])
            elif op in (0x12, 0x13):
                idx = code_bytes[p + 1] if op == 0x12 else _s.unpack(">H", code_bytes[p + 1:p + 3])[0]
                e = cp[idx] if idx < len(cp) else None
                if e and e[0] == 3:
                    out.add(_s.unpack(">i", e[1])[0])
    return out


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
        """Returns a list of (width, height, reason), most reliable first."""
        hints = []
        for key in ("Nokia-MIDlet-Original-Display-Size", "Nokia-MIDlet-Target-Display-Size",
                    "MIDlet-Screen-Size", "J2A-Screen-Size"):
            v = self.attrs.get(key)
            if v:
                m = re.search(r"(\d{2,4})\s*[,x*]\s*(\d{2,4})", v)
                if m:
                    hints.append((int(m.group(1)), int(m.group(2)), key))
        names = [os.path.basename(self.jar_path)] + ([os.path.basename(self.jad_path)] if self.jad_path else [])
        names += [self.attrs.get(k, "") for k in ("MIDlet-Jar-URL", "MIDlet-Name", "MIDlet-Description")]
        text = " ".join(names).lower()
        m = re.search(r"(?<!\d)(\d{3})\s*[x*_-]\s*(\d{3})(?!\d)", text)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
            if 96 <= w <= 1024 and 96 <= h <= 1024:
                hints.append((w, h, "nombre del archivo"))
        squashed = re.sub(r"[\s_.]+", "", text)
        for w, h, models in DEVICE_SCREENS:
            for model in models.split():
                pat = r"(?<![a-z0-9])%s(?![0-9])" % re.escape(model)
                if re.search(pat, text) or (len(model) > 4 and re.search(pat, squashed)):
                    hints.append((w, h, "modelo de teléfono '%s'" % model))
                    break
        png_screens, widths = self.png_stats()
        for (w, h), n in png_screens.most_common(2):
            hints.append((w, h, "%d imagen(es) del tamaño de pantalla" % n))
        pair = self.bytecode_screen()
        if pair:
            w, h, n = pair
            if (w, h) != (h, w) and widths[h] > widths[w]:
                w, h = h, w  # more images as wide as the long side -> landscape
            hints.append((w, h, "constantes %dx%d en %d clase(s) del código" % (w, h, n)))
        return hints

    def png_stats(self):
        """(Counter of screen-sized PNGs, Counter of PNG widths)."""
        screens, widths = Counter(), Counter()
        for e in self.entries:
            if e.filename.lower().endswith(".png") and e.file_size < 400000:
                try:
                    s = png_size(self.zip.read(e.filename)[:32])
                except Exception:  # noqa: BLE001 - broken image entries happen
                    s = None
                if s:
                    widths[s[0]] += 1
                    if s in COMMON_SCREENS:
                        screens[s] += 1
        return screens, widths

    def bytecode_screen(self):
        """Screen size hard-coded in the game: the (w, h) pair whose two numbers appear together
        in the most classes (e.g. fillRect(0, 0, 176, 208)). Returns (w, h, classes) or None."""
        from .classfile import ClassFile
        consts = []
        for name in self.classes:
            try:
                cf = ClassFile(self.zip.read(name))
                consts.append(int_constants(cf.data, cf.code_ranges, cf.cp))
            except Exception:  # noqa: BLE001 - unreadable class: just skip it
                pass
        best = None
        for w, h in COMMON_SCREENS:
            if w >= h and (h, w) in COMMON_SCREENS:
                continue  # each pair counted once (portrait form); orientation decided elsewhere
            if w == h:
                continue  # 128 / 208 alone are too common (bit masks...) to mean anything
            n = sum(1 for c in consts if w in c and h in c)
            # in a big code base two numbers meet by chance; demand a share of the classes
            if n >= max(1, len(consts) // 20) and (best is None or n > best[2]):
                best = (w, h, n)
        return best

    def orientation_hint(self):
        v = (self.attrs.get("Nokia-MIDlet-App-Orientation") or "").lower()
        if v in ("landscape", "portrait"):
            return v
        return None
