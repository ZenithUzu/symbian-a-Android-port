#!/usr/bin/env python3
"""Builds demo.jar + demo.jad, a tiny MIDP 2.0 game used to test j2a.

It compiles against the j2a runtime itself (javac + an android.jar are needed),
so you do not need the old Sun Wireless Toolkit.

    python samples/demo-midlet/build.py [--android-jar path/to/android.jar]
"""
import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "tool"))

from j2a_tool import decompile, paths  # noqa: E402


def png(width, height, pixels):
    """pixels: list of rows of (r, g, b, a) tuples."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in pixels)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def tiles_png():
    rows = []
    for y in range(16):
        row = []
        for x in range(32):
            if x < 16:
                row.append((40, 120, 40, 255) if (x // 4 + y // 4) % 2 else (50, 140, 50, 255))
            else:
                row.append((70, 60, 40, 255) if (x + y) % 5 else (90, 80, 50, 255))
        rows.append(row)
    return png(32, 16, rows)


def hero_png():
    rows = []
    for y in range(16):
        row = []
        for x in range(32):
            fx = x % 16
            frame = x // 16
            inside = (fx - 8) ** 2 + (y - 8) ** 2 < 36
            if not inside:
                row.append((0, 0, 0, 0))
            elif frame == 0:
                row.append((255, 210, 40, 255) if fx < 11 else (200, 60, 30, 255))
            else:
                row.append((255, 240, 90, 255))
        rows.append(row)
    return png(32, 16, rows)


def icon_png():
    return png(16, 16, [[(90, 150, 255, 255) if 2 < x < 13 and 2 < y < 13 else (0, 0, 0, 0)
                         for x in range(16)] for y in range(16)])


def beep_wav():
    import math
    rate, n = 8000, 1200
    data = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 880 * i / rate))) for i in range(n))
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate,
            rate * 2, 2, 16) + b"data" + struct.pack("<I", len(data)) + data)


MANIFEST = """Manifest-Version: 1.0
MIDlet-Name: Demo j2a
MIDlet-Vendor: ZenithUzu
MIDlet-Version: 1.0.0
MIDlet-Icon: /icon.png
MIDlet-1: Demo j2a, /icon.png, demo.DemoMIDlet
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
Nokia-MIDlet-Original-Display-Size: 240,320
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--android-jar", default=paths.find_android_jar())
    ap.add_argument("-o", "--output", default=os.path.join(HERE, "demo.jar"))
    a = ap.parse_args()
    if not a.android_jar:
        sys.exit("Necesito un android.jar (--android-jar) para compilar contra el runtime")
    rt = decompile.runtime_classes_jar(a.android_jar)
    javac = paths.java_bin("javac")
    tmp = tempfile.mkdtemp()
    try:
        srcs = []
        for d, _, fs in os.walk(os.path.join(HERE, "src")):
            srcs += [os.path.join(d, f) for f in fs if f.endswith(".java")]
        out = os.path.join(tmp, "classes")
        cmd = [javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-g:none",
               "-bootclasspath", a.android_jar, "-cp", rt, "-d", out] + srcs
        subprocess.check_call(cmd)
        with zipfile.ZipFile(a.output, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("META-INF/MANIFEST.MF", MANIFEST)
            for d, _, fs in os.walk(out):
                for f in fs:
                    p = os.path.join(d, f)
                    z.write(p, os.path.relpath(p, out).replace(os.sep, "/"))
            z.writestr("tiles.png", tiles_png())
            z.writestr("hero.png", hero_png())
            z.writestr("icon.png", icon_png())
            z.writestr("beep.wav", beep_wav())
        size = os.path.getsize(a.output)
        with open(os.path.splitext(a.output)[0] + ".jad", "w") as f:
            f.write(MANIFEST.replace("Manifest-Version: 1.0\n", "")
                    + "MIDlet-Jar-URL: demo.jar\nMIDlet-Jar-Size: %d\n" % size)
        print("OK: %s (%d bytes)" % (a.output, size))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
