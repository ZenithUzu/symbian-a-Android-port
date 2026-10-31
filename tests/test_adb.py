import subprocess
import unittest
from unittest.mock import patch
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tool'))
from j2a_tool import adb, cli


class AdbTests(unittest.TestCase):
    def test_device_states(self):
        with patch.object(adb, 'run', return_value='List of devices attached\na device model:Phone\nb unauthorized\nc offline\n'):
            self.assertEqual([d['state'] for d in adb.devices()], ['device', 'unauthorized', 'offline'])

    def test_requires_selected_device(self):
        with self.assertRaisesRegex(RuntimeError, 'Selecciona'):
            adb.install(['game.apk'], '')

    def test_rejects_unauthorized_device(self):
        with patch.object(adb, 'devices', return_value=[{'serial': 'a', 'state': 'unauthorized'}]), patch.object(adb, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'autorización'):
                adb.install(['game.apk'], 'a')
            run.assert_not_called()

    def test_install_targets_selected_serial_and_keeps_data(self):
        with patch.object(adb, 'devices', return_value=[{'serial': 'a', 'state': 'device'}]), patch.object(adb, 'run', return_value='Success\n') as run:
            adb.install(['game.apk'], 'a', log=lambda _: None)
            self.assertEqual(run.call_args.args[0][:4], ['-s', 'a', 'install', '-r'])

    def test_non_success_install_is_failure(self):
        with patch.object(adb, 'devices', return_value=[{'serial': 'a', 'state': 'device'}]), patch.object(adb, 'run', return_value='Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'):
            with self.assertRaisesRegex(RuntimeError, 'no confirmó'):
                adb.install(['game.apk'], 'a', log=lambda _: None)

    def test_install_builds_then_installs(self):
        with patch.object(cli.build, 'build_apk', return_value=['new.apk']), patch.object(adb, 'install') as install:
            cli.main(['build', 'project', '--install', '--device', 'phone'])
            install.assert_called_once_with(['new.apk'], 'phone')
