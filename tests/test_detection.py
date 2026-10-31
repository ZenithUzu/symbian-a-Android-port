from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tool'))
from j2a_tool.jarinfo import MidletSuite
from j2a_tool import generate, paths


def png(w, h):
    return b'\x89PNG\r\n\x1a\n' + b'\x00\x00\x00\rIHDR' + struct.pack('>II', w, h)


class DetectionTests(unittest.TestCase):
    def detect(self, name='Game.jar', manifest='', files=None, jad=None):
        with tempfile.TemporaryDirectory() as folder:
            game = Path(folder) / name
            with zipfile.ZipFile(game, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'MIDlet-Name: Game\n' + manifest)
                for path, data in (files or {}).items():
                    archive.writestr(path, data)
            if jad is not None:
                game.with_suffix('.jad').write_text(jad, encoding='utf-8')
            suite = MidletSuite(str(game))
            try:
                return suite.screen_detection()
            finally:
                suite.zip.close()

    def test_original_manifest_beats_stale_filename(self):
        d = self.detect('Game_320x240.jar', 'Nokia-MIDlet-Original-Display-Size: 176,208\n')
        self.assertEqual((d['width'], d['height'], d['confidence']), (176,208,'alta'))

    def test_invalid_manifest_size_is_ignored(self):
        d = self.detect('Game_128x160.jar', 'MIDlet-Screen-Size: 9999x9999\n')
        self.assertEqual((d['width'],d['height']), (128,160))

    def test_jad_overrides_jar_declaration(self):
        d = self.detect(manifest='MIDlet-Screen-Size: 176x208\n', jad='MIDlet-Screen-Size: 320x240\n')
        self.assertEqual((d['width'],d['height']), (320,240))

    def test_atlas_and_midlet_icon_are_not_screen_evidence(self):
        d = self.detect(manifest='MIDlet-Icon: /large.png\n', files={
            'large.png':png(480,800), 'atlas.png':png(320,240), 'menu.png':png(176,208)})
        self.assertEqual((d['width'],d['height']), (176,208))

    def test_png_content_detected_without_extension(self):
        d = self.detect(files={'loading.dat':png(352,416)})
        self.assertEqual((d['width'],d['height']), (352,416))

    def test_gif_screen_detected(self):
        d = self.detect(files={'menu.gif':b'GIF89a' + struct.pack('<HH',240,320)})
        self.assertEqual((d['width'],d['height']), (240,320))

    def test_jpeg_frame_dimensions(self):
        from j2a_tool.resolution import image_size
        header = b'\xff\xd8\xff\xc0\x00\x11\x08' + struct.pack('>HH',208,176) + b'\x03'
        self.assertEqual(image_size(header), (176,208))

    def test_description_advertising_other_phones_is_ignored(self):
        d = self.detect(manifest='MIDlet-Description: Compatible N95 480x800 and N70\n', files={'menu.png':png(128,160)})
        self.assertEqual((d['width'],d['height']), (128,160))

    def test_conflicting_filenames_have_low_confidence(self):
        self.assertEqual(self.detect('Game_176x208_320x240.jar')['confidence'], 'baja')

    def test_no_evidence_is_explicit_fallback(self):
        d = self.detect(files={'broken.class':b'broken'})
        self.assertEqual((d['width'],d['height'],d['confidence']), (240,320,'baja'))
        self.assertFalse(d['candidates'])

    def test_model_name_matches_complete_token(self):
        d = self.detect('Game_N950.jar')
        self.assertFalse(d['candidates'])
        d = self.detect('Game_N95.jar')
        self.assertIn('model',d['candidates'][0]['sources'])
        d = self.detect('Game_NokiaN95.jar')
        self.assertEqual((d['width'],d['height']), (240,320))

    def test_explicit_screen_override_still_wins(self):
        from types import SimpleNamespace
        d = self.detect('Game_176x208.jar')
        self.assertEqual(generate.pick_screen(SimpleNamespace(screen='320x240'), {'screen_detection':d}), (320,240,'--screen'))

    def test_actual_drawing_bytecode_beats_stale_filename(self):
        javac = paths.java_bin('javac')
        if not javac:
            self.skipTest('Needs JDK')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            api = root / 'javax/microedition/lcdui/Graphics.java'
            api.parent.mkdir(parents=True)
            api.write_text('package javax.microedition.lcdui; public class Graphics { public void fillRect(int x,int y,int w,int h) {} }')
            game = root / 'Game.java'
            game.write_text('import javax.microedition.lcdui.Graphics; public class Game { public void draw(Graphics g) { g.fillRect(0,0,176,208); } }')
            subprocess.run([javac,'-d',str(root),str(api),str(game)],check=True,capture_output=True)
            d = self.detect('Game_320x240.jar', files={'Game.class':(root/'Game.class').read_bytes(), 'background.png':png(176,208)})
        self.assertEqual((d['width'],d['height']), (176,208))
        self.assertIn('draw', d['candidates'][0]['sources'])
        self.assertEqual(d['confidence'],'alta')
