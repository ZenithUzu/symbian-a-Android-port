"""Run with python -m unittest discover -s tests."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tool'))
from j2a_tool import build, cli


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='j2a test ')
        self.addCleanup(self.tmp.cleanup)
        self.project = Path(self.tmp.name)
        wrapper = self.project / 'gradle/wrapper/gradle-wrapper.jar'
        wrapper.parent.mkdir(parents=True)
        wrapper.touch()
        (self.project / 'settings.gradle').touch()
        self.apk_dir = self.project / 'app/build/outputs/apk/debug'
        self.apk_dir.mkdir(parents=True)
        self.metadata('game-debug.apk')
        (self.apk_dir / 'game-debug.apk').write_bytes(b'APK fixture')
        for name, result in [('java_bin', 'java'), ('find_java_home', str(self.project)),
                             ('find_android_sdk', str(self.project))]:
            mock = patch.object(build.paths, name, return_value=result)
            mock.start()
            self.addCleanup(mock.stop)

    def metadata(self, filename):
        (self.apk_dir / 'output-metadata.json').write_text(json.dumps({
            'elements': [{'outputFile': filename}]}), encoding='utf-8')

    def process(self, code=0):
        from unittest.mock import Mock
        return Mock(stdout=io.StringIO('build output\n'), wait=lambda: code, poll=lambda: code)

    def test_success_delivers_metadata_apk_and_log(self):
        with patch.object(build.subprocess, 'Popen', return_value=self.process()) as popen:
            result = build.build_apk(self.project, log=lambda _: None)
        self.assertEqual(Path(result[0]).read_bytes(), b'APK fixture')
        self.assertIn('build output', (self.project / 'apk-build.log').read_text())
        self.assertIn(':app:assembleDebug', popen.call_args.args[0])
        self.assertEqual(popen.call_args.kwargs['cwd'], str(self.project))

    def test_failed_gradle_never_copies_old_apk(self):
        with patch.object(build.subprocess, 'Popen', return_value=self.process(1)):
            with self.assertRaisesRegex(RuntimeError, 'Gradle falló'):
                build.build_apk(self.project, log=lambda _: None)
        self.assertFalse((self.project / 'apk').exists())

    def test_rejects_metadata_path_traversal(self):
        self.metadata('../outside.apk')
        with patch.object(build.subprocess, 'Popen', return_value=self.process()):
            with self.assertRaisesRegex(RuntimeError, 'ruta inválida'):
                build.build_apk(self.project, log=lambda _: None)

    def test_missing_metadata_is_failure(self):
        (self.apk_dir / 'output-metadata.json').unlink()
        with patch.object(build.subprocess, 'Popen', return_value=self.process()):
            with self.assertRaisesRegex(RuntimeError, 'metadatos'):
                build.build_apk(self.project, log=lambda _: None)

    def test_port_only_keeps_existing_behavior(self):
        with patch.object(cli.generate, 'port', return_value='project'), patch.object(build, 'build_apk') as apk:
            self.assertEqual(cli.main(['port', 'game.jar']), 0)
            apk.assert_not_called()

    def test_port_apk_compiles_generated_directory(self):
        with patch.object(cli.generate, 'port', return_value='project'), patch.object(build, 'build_apk') as apk:
            cli.main(['port', 'game.jar', '--apk', '--apk-output', 'delivery'])
            apk.assert_called_once_with('project', 'delivery')

    def test_build_existing_project(self):
        with patch.object(build, 'build_apk') as apk:
            cli.main(['build', 'existing project'])
            apk.assert_called_once_with('existing project', None)


if __name__ == '__main__':
    unittest.main()
