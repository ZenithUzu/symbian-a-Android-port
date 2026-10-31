"""Build installable debug APKs with the generated project's pinned Gradle wrapper."""
import json
import os
from pathlib import Path
import shutil
import subprocess

from . import paths


def build_apk(project, output=None, log=print):
    project = Path(project).resolve()
    wrapper = project / 'gradle' / 'wrapper' / 'gradle-wrapper.jar'
    java = paths.java_bin()
    sdk = paths.find_android_sdk()
    if not wrapper.is_file() or not (project / 'settings.gradle').is_file():
        raise RuntimeError('La carpeta no contiene un proyecto j2a con Gradle Wrapper.')
    if not java or not paths.java_bin('javac'):
        raise RuntimeError('Instala Android Studio o un JDK compatible y configura JAVA_HOME.')
    if not sdk and not (project / 'local.properties').is_file():
        raise RuntimeError('No encuentro Android SDK. Instálalo con Android Studio o define ANDROID_HOME.')
    env = os.environ.copy()
    env['JAVA_HOME'] = paths.find_java_home()
    if sdk:
        env['ANDROID_HOME'] = sdk
    command = [java, '-classpath', str(wrapper), 'org.gradle.wrapper.GradleWrapperMain',
               '--no-daemon', '--console=plain', ':app:assembleDebug']
    log('Compilando APK instalable. La primera compilación descarga Gradle y sus dependencias…')
    build_log = project / 'apk-build.log'
    with build_log.open('w', encoding='utf-8') as saved:
        process = subprocess.Popen(command, cwd=str(project), env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace',
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for line in process.stdout:
                saved.write(line)
                log(line.rstrip())
            code = process.wait()
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.terminate()
                process.wait()
    if code:
        raise RuntimeError('Gradle falló (código %s). Registro: %s. '
                           'Revisa las licencias y los paquetes del SDK indicados en el registro.'
                           % (code, build_log))
    apk_dir = project / 'app' / 'build' / 'outputs' / 'apk' / 'debug'
    metadata = apk_dir / 'output-metadata.json'
    if not metadata.is_file():
        raise RuntimeError('Gradle terminó sin los metadatos de la APK: %s' % metadata)
    data = json.loads(metadata.read_text(encoding='utf-8'))
    sources = []
    for element in data.get('elements', []):
        source = (apk_dir / element['outputFile']).resolve()
        if source.parent != apk_dir.resolve() or source.suffix != '.apk' or not source.is_file():
            raise RuntimeError('APK ausente o ruta inválida en los metadatos: %s' % source)
        sources.append(source)
    if not sources:
        raise RuntimeError('La compilación no produjo ninguna APK.')
    destination = Path(output).resolve() if output else project / 'apk'
    destination.mkdir(parents=True, exist_ok=True)
    result = []
    for source in sources:
        target = destination / source.name
        if source != target:
            temporary = target.with_suffix('.apk.tmp')
            shutil.copy2(str(source), str(temporary))
            os.replace(str(temporary), str(target))
        result.append(str(target))
        log('APK lista: %s' % target)
    log('Firmada con la clave de depuración local: puedes instalarla en tu teléfono.')
    return result
