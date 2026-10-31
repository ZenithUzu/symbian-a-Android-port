from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tool'))
from j2a_tool import jarinfo, paths


class ResolutionTests(unittest.TestCase):
    def test_phone_catalog_matches_android(self):
        source = (ROOT / 'runtime/src/main/java/j2a/core/ResolutionProfiles.java').read_text(encoding='utf-8')
        pairs = [tuple(map(int, pair)) for pair in re.findall(r'\{(\d+),(\d+)\}', source)]
        self.assertEqual(jarinfo.COMMON_SCREENS, pairs)

    def test_small_filename_resolution_and_manifest_priority(self):
        with tempfile.TemporaryDirectory() as folder:
            game = Path(folder) / 'Game_96x65.jar'
            with zipfile.ZipFile(game, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'MIDlet-Name: Test\nNokia-MIDlet-Original-Display-Size: 176,208\n')
            suite = jarinfo.MidletSuite(str(game))
            try:
                hints = suite.screen_hints()
                self.assertEqual(hints[0][:2], (176,208))
                self.assertIn((96,65), [hint[:2] for hint in hints])
            finally:
                suite.zip.close()

    def test_real_java_panorama_calculations(self):
        java, javac = paths.java_bin(), paths.java_bin('javac')
        if not java or not javac:
            self.skipTest('Needs a JDK')
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run([javac, '-encoding', 'UTF-8', '-d', folder,
                str(ROOT / 'runtime/src/main/java/j2a/core/ResolutionProfiles.java'),
                str(ROOT / 'tests/ResolutionProfilesTest.java')], check=True, capture_output=True)
            result = subprocess.run([java, '-cp', folder, 'ResolutionProfilesTest'], check=True, capture_output=True, text=True)
            self.assertIn('Resolution profiles OK', result.stdout)
