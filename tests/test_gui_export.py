"""Exercise GUI export commands without requiring a display or importing Tk."""
import ast
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tool'))
from j2a_tool import cli, generate, paths


class GuiExportTests(unittest.TestCase):
    def helpers(self):
        source = ast.parse((ROOT / 'j2a-gui.pyw').read_text(encoding='utf-8'))
        functions = [n for n in source.body if isinstance(n, ast.FunctionDef) and n.name in ('build_args', 'project_dir')]
        scope = dict(os=os, generate=generate, paths=paths)
        exec(compile(ast.Module(body=functions, type_ignores=[]), 'GUI helpers', 'exec'), scope)
        return scope

    def test_gui_exports_without_overriding_runtime_settings(self):
        argv = self.helpers()['build_args']({'jar': 'game.jar', 'out': 'proyectos/game',
                    'mode': 'source', 'apk': False, 'apk_output': 'apk'})
        with patch.object(generate, 'port') as port:
            cli.main(argv)
        args = port.call_args.args[0]
        self.assertIsNone(args.keypad)
        self.assertIsNone(args.shader)
        self.assertIsNone(args.screen)
        self.assertIsNone(args.orientation)
        self.assertFalse(args.apk)

    def test_gui_uses_projects_instead_of_input_jar_directory(self):
        result = self.helpers()['project_dir'](os.path.join('elsewhere', 'Game.jar'), '', 'Game')
        self.assertEqual(result, os.path.join(paths.projects_dir(), 'Game-android'))

    def test_gui_passes_separate_apk_destination_and_adb_device(self):
        argv = self.helpers()['build_args']({'jar': 'game.jar', 'out': 'proyectos/game',
                    'mode': 'jar', 'apk': True, 'apk_output': 'apk', 'install': True, 'device': 'phone'})
        with patch.object(generate, 'port', return_value='proyectos/game'), patch.object(cli.build, 'build_apk', return_value=['apk/game.apk']) as build, patch.object(cli.adb, 'install') as install:
            cli.main(argv)
        build.assert_called_once_with('proyectos/game', 'apk')
        install.assert_called_once_with(['apk/game.apk'], 'phone')
