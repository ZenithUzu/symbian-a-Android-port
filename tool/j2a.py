#!/usr/bin/env python3
"""j2a — J2ME (Symbian / Series 40 / .jar) to Android Studio porting tool.

Usage:  python tool/j2a.py port MiJuego.jar
        python tool/j2a.py --help
"""
import os
import sys

if sys.version_info < (3, 8):
    sys.exit("j2a necesita Python 3.8 o superior")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from j2a_tool.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
