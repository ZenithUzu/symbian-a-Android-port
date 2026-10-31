"""ADB discovery and explicit installation on one selected device."""
import os
import shutil
import subprocess
from . import paths


def executable():
    sdk = paths.find_android_sdk()
    candidate = os.path.join(sdk, 'platform-tools', 'adb.exe' if os.name == 'nt' else 'adb') if sdk else ''
    result = candidate if os.path.isfile(candidate) else shutil.which('adb')
    if not result:
        raise RuntimeError('No encuentro ADB. Instala Android SDK Platform-Tools con SDK Manager.')
    return result


def run(arguments, timeout=30):
    try:
        result = subprocess.run([executable()] + arguments, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    except subprocess.TimeoutExpired:
        raise RuntimeError('ADB agotó el tiempo de espera. Revisa la conexión y la autorización del dispositivo.') from None
    if result.returncode:
        raise RuntimeError('ADB: ' + (result.stderr or result.stdout).strip())
    return result.stdout


def devices():
    found = []
    for line in run(['devices', '-l']).splitlines():
        parts = line.split()
        if len(parts) < 2 or parts[0] == 'List' or line.startswith('*'):
            continue
        if parts[1] in ('device', 'offline', 'unauthorized'):
            found.append({'serial': parts[0], 'state': parts[1], 'details': ' '.join(parts[2:])})
    return found


def install(apks, serial, log=print):
    if not serial:
        raise RuntimeError('Selecciona un dispositivo ADB antes de instalar.')
    target = next((d for d in devices() if d['serial'] == serial), None)
    if not target or target['state'] != 'device':
        raise RuntimeError('Dispositivo desconectado, sin autorización o fuera de línea. '
                           'Activa depuración USB y acepta la autorización en Android.')
    if len(apks) != 1:
        raise RuntimeError('Se esperaba una APK universal para instalar por ADB.')
    log('Instalando en %s…' % serial)
    result = run(['-s', serial, 'install', '-r', os.path.abspath(apks[0])], timeout=180)
    if 'Success' not in result.split():
        raise RuntimeError('ADB no confirmó la instalación: ' + result.strip())
    log('Instalación completada en %s.' % serial)
