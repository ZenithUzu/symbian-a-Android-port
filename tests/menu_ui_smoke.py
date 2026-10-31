"""ADB integration test against a running demo port on a disposable emulator."""
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path('menu-screenshots')
OUT.mkdir(exist_ok=True)


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/menu.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/menu.xml')).iter('node')


def find(label):
    for node in nodes():
        if (node.get('text') or node.get('content-desc', '')).split(':')[0].casefold() == label.casefold():
            return node
    raise AssertionError('Missing menu option: ' + label)


def tap(label):
    import re
    values = list(map(int, re.findall(r'\d+', find(label).get('bounds'))))
    adb('shell', 'input', 'tap', str((values[0] + values[2]) // 2), str((values[1] + values[3]) // 2))
    time.sleep(.7)


def screenshot(name):
    adb('shell', 'screencap', '-p', '/sdcard/menu.png')
    adb('pull', '/sdcard/menu.png', str(OUT / (name + '.png')))


adb('logcat', '-c')
print('INITIAL_UI', [(n.get('text'), n.get('content-desc')) for n in nodes()])
screenshot('initial')
if any(n.get('text', '').casefold() == 'sí, mostrar teclado' for n in nodes()):
    tap('Sí, mostrar teclado')
# Android's first immersive-mode hint intercepts Back until acknowledged.
if any(n.get('text', '').casefold() == 'got it' for n in nodes()):
    tap('Got it')
if any(n.get('text') == 'Jugar' for n in nodes()):
    tap('Jugar')
adb('shell', 'input', 'keyevent', '82')
time.sleep(1)
print('MENU_UI', [(n.get('text'), n.get('content-desc')) for n in nodes()])
screenshot('menu-open')
required = ['Pantalla', 'Filtros', 'Controles', 'Sonido', 'Rendimiento', 'Más opciones']
for name in required:
    find(name)
assert not any('PLAYER CONSOLE' in n.get('text', '') or 'Centro de juego' in n.get('text', '') for n in nodes())
screenshot('ajustes-vertical')
for label, filename in [('Pantalla', 'pantalla'), ('Filtros', 'filtros'), ('Sonido', 'sonido'), ('Controles', 'controles')]:
    tap(label)
    assert any(n.get('text') == label for n in nodes()), 'Submenu did not open: ' + label
    screenshot(filename)
    adb('shell', 'input', 'keyevent', '4')
    find('Pantalla')
tap('Pantalla')
tap('Rotación')
tap('Siempre horizontal')
time.sleep(2)
adb('shell', 'input', 'keyevent', '82')
find('Pantalla')
screenshot('ajustes-horizontal')
adb('shell', 'input', 'keyevent', '4')
assert not any(n.get('text') == 'Ajustes' for n in nodes()), 'Back did not dismiss settings'
crashes = adb('logcat', '-d', '-s', 'AndroidRuntime:E')
assert 'FATAL EXCEPTION' not in crashes, crashes
print('MENU_UI_SUCCESS: root categories, submenus, back navigation and rotation')







