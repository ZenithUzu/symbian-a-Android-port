# symbian-a-Android-port

**j2a** convierte juegos Java ME (Symbian S60, Series 40 y cualquier `.jar`/`.jad` de MIDP) en
**proyectos nativos de Android Studio**: código Java editable, recursos originales y una
implementación de J2ME sobre Android con mejoras (escalado, teclado virtual, mandos, límite de FPS...).

No es un emulador: el juego se compila como una app Android normal con su propio icono y paquete.

```
MiJuego.jar ──► j2a analyze ──► j2a port ──► proyecto Android Studio ──► Run ▶ / APK
                 (qué APIs usa)   (parche + descompilación + verificación)
```

## Requisitos

- **Python 3.8+** (sin dependencias; Pillow es opcional para agrandar el icono).
- **Android Studio** (ya lo tienes). j2a usa su JDK (`jbr`) y su Android SDK automáticamente.
  Si no los encuentra, define `JAVA_HOME` y `ANDROID_HOME`.
- Internet la primera vez, para descargar Vineflower/CFR (se guardan en `~/.j2a/tools`).

```bash
git clone https://github.com/ZenithUzu/symbian-a-Android-port
cd symbian-a-Android-port
python tool/j2a.py doctor      # comprueba Java, SDK y descompiladores
```

## Uso con interfaz (lo más fácil)

Doble clic en **`j2a-gui.pyw`** (en Linux/macOS: `python3 j2a-gui.pyw`). En Windows, si no abre o sale un error de "Python Launcher", usa **`j2a-gui.bat`**: muestra el error en vez de cerrarse.

1. **Juegos (.jar) → Elegir…** (con Ctrl o Mayús eliges varios) o **arrastra** uno o varios `.jar`
   a la ventana. También puedes soltarlos sobre `j2a-gui.bat` y la ventana se abre con ellos cargados.
2. **Guardar en**: vacío = cada proyecto queda junto a su `.jar` (`Juego-android`); o elige una carpeta
   para todos.
3. Ajusta resolución, rotación, shader, FPS y teclado si quieres (se aplican a todos). Lo automático suele bastar.
4. **Generar port**: se generan uno tras otro; si uno falla, los demás siguen y al final ves el resumen.
5. **Abrir carpeta** y ábrela en Android Studio (File → Open) → Run ▶.

Arrastrar dentro de la ventana usa el paquete `tkinterdnd2`: `j2a-gui.bat` lo instala solo la primera vez
(o `python -m pip install tkinterdnd2`). Sin él, todo lo demás funciona igual.

La ventana usa los mismos comandos de abajo, así que el resultado es idéntico.

## Uso rápido por comandos

```bash
# 1. ¿Qué hay dentro y qué tan portable es?
python tool/j2a.py analyze MiJuego.jar

# 2. Generar el proyecto (código fuente editable)
python tool/j2a.py port MiJuego.jar -o MiJuego-android

# 3. Android Studio → File → Open → MiJuego-android → Run ▶
```

Ejemplos con opciones:

```bash
# Juego de Nokia N95 / E-series en horizontal, escalado pixel-perfect y a 30 FPS
python tool/j2a.py port Juego_320x240.jar --screen 320x240 --orientation landscape --scale integer --fps 30

# Máxima fidelidad: bytecode original, sin descompilar
python tool/j2a.py port Juego.jar --mode jar

# Solo quiero modificar dos clases; el resto queda como bytecode original
python tool/j2a.py port Juego.jar --mode hybrid --edit com.estudio.GameCanvas,com.estudio.Menu

# Solo descompilar para leer el código
python tool/j2a.py decompile Juego.jar -o Juego-src
```

### Modos de `port`

| Modo | Qué genera | Cuándo usarlo |
|---|---|---|
| `source` (defecto) | Todo el juego descompilado en `app/src/main/java`. Las clases que no se descompilan limpias o no compilan se quedan automáticamente en `app/libs/game.jar`. | Quieres modificar/mejorar el juego. |
| `jar` | El bytecode original (parcheado) en `app/libs/game.jar`. | Solo quieres jugarlo en Android con la máxima fidelidad. |
| `hybrid` | Solo las clases de `--edit` como código; el resto como bytecode. | Juegos ofuscados grandes donde solo tocas unas pocas clases. |

### Opciones principales

| Opción | Descripción |
|---|---|
| `--screen 240x320` | Resolución virtual. Normalmente no hace falta: j2a la deduce del manifiesto, del nombre del archivo (`Juego_176x208.jar`, `Juego_N95.jar`), de las imágenes y de los números fijos en el código. Además, el APK ajusta solo el área visible y la rotación si el juego dibuja menos de lo esperado. |
| `--orientation portrait/landscape/auto` | Orientación de la app. |
| `--scale fit/integer/stretch` | Escalado. |
| `--shader sharp` | Shader por defecto: `nearest`, `smooth`, `sharp`, `scanlines`, `lcd`, `crt`, `mono`. |
| `--shaders carpeta/` | Incluye shaders en formato PPSSPP (`.ini` + `.fsh` [+ `.vsh`]), p. ej. los de [jdgleaver/ppsspp_shaders](https://github.com/jdgleaver/ppsspp_shaders). Ojo: esos son GPL. |
| `--fps-mode vsync/dynamic/unlimited` | Sincronizado con la pantalla, dinámico (la pantalla adapta sus Hz a los FPS del juego) o sin límite. |
| `--keep-j2me-packages` | No renombrar las APIs al paquete del motor (ver "Port nativo"). |
| `--keypad full/minimal/none` | Teclado virtual en pantalla. |
| `--fps 30` | Limita los FPS (muchos juegos van demasiado rápido en móviles actuales). |
| `--package com.yo.juego`, `--name "Mi Juego"` | ID y nombre de la app. |
| `--midlet 2` / `--midlet-class X` | Qué MIDlet lanzar si el JAR trae varios o no los declara. |
| `--engine cfr` | Usar CFR en vez de Vineflower. |
| `--keep-bytecode A,B` | Forzar que ciertas clases queden como bytecode. |
| `--jad Juego.jad` | JAD explícito (si está junto al `.jar` con el mismo nombre se usa solo). |

Todas las opciones de ejecución se pueden cambiar después en
`app/src/main/assets/j2a/port.properties`.

### Opciones dentro del APK

El jugador abre el **menú del port** con el **gesto o botón Atrás** de Android (o Menú/Escape) y puede cambiar:
resolución (128x160 … 640x360 y **widescreen** con la proporción del móvil), rotación
(automática/vertical/horizontal), escalado (proporcional, entero, estirar), **shader**
(nítido, suavizado, sharp bilinear, scanlines, rejilla LCD, CRT, LCD verde monocromo y los PPSSPP que
incluyas), **modo de FPS** (vsync, dinámico, sin límite), límite de FPS (15–120), contador de FPS,
teclado virtual, opacidad, vibración, sonido, **captura de pantalla** y restablecer opciones.
Se guardan entre sesiones.

## Cómo funciona

1. **Análisis** — lee `MANIFEST.MF`/JAD y el *constant pool* de cada `.class` para saber qué APIs
   J2ME usa el juego y comprobar si el runtime las implementa (incluye métodos).
2. **Parche de bytecode** — reescribe `Class.getResourceAsStream` (que en Android falla con el
   típico `"".getClass()`), y descarta copias de `javax.microedition.*` que algunos JAR traen dentro.
3. **Descompilación** — Vineflower (o CFR) con el runtime como biblioteca para resolver tipos.
4. **Verificación** — compila el código con `javac` contra tu `android.jar`; lo que no compila
   vuelve a bytecode. El proyecto generado compila siempre.
5. **Proyecto** — plantilla Gradle + módulo `engine` + recursos + icono del MIDlet.

### Port nativo, sin emulador

No hay intérprete ni máquina virtual de J2ME dentro del APK: el código del juego se compila a DEX
y corre directo en ART, como cualquier app. Las llamadas que antes iban al teléfono
(`Canvas`, `Graphics`, `RecordStore`, `Player`...) van al módulo `engine`, que está escrito con APIs
de Android (OpenGL ES, MediaPlayer, AudioTrack, archivos, táctil). Además j2a **renombra** esas APIs
al paquete propio del port (`com.tu.juego.engine.lcdui.Canvas`, `...engine.rms.RecordStore`), tanto
en el código descompilado como en el bytecode, así que el APK final no contiene ninguna clase
`javax.microedition` ni `com.nokia`.

Más detalle:

- [docs/01-decompilacion.md](docs/01-decompilacion.md) — estructura de JAR/JAD, ofuscación, Vineflower/CFR.
- [docs/02-traduccion-a-android.md](docs/02-traduccion-a-android.md) — la capa de traducción J2ME → Android y la tabla de APIs.
- [docs/03-mejoras-y-limitaciones.md](docs/03-mejoras-y-limitaciones.md) — mejoras, límites y cómo añadir APIs.

## APIs soportadas

MIDP 2.0 completo para juegos (Canvas, GameCanvas, Graphics, Image, Font, Sprite, TiledLayer,
LayerManager, Form/List/TextBox/Alert y sus Items), RMS, MMAPI (MIDI/WAV/MP3/AMR/OGG + tonos y
ToneControl), HTTP, Nokia UI (FullCanvas, DirectGraphics, DirectUtils, DeviceControl, Sound) y
Samsung (AudioClip, Vibration, LCDLight). **3D**: M3G (JSR-184) con el motor nativo de Nokia en C y
MascotCapsule V3 (y las APIs 3D de JBlend/Vodafone/Motorola) con el renderizador de J2ME Loader; solo
se compilan en los juegos 3D (NDK, que Android Studio instala). **Además** (de J2ME Loader, en
`runtime-extra/`): APIs de Siemens, Motorola, Sony Ericsson, Samsung, Vodafone/J-Phone/KDDI,
**sensores JSR-256** (acelerómetro), FileConnection/PIM, ubicación, SMS, Bluetooth, AMMS y todos los
controles MMAPI, para que ningún juego se cierre por una clase que falta.

## Estructura del repositorio

```
tool/j2a.py              CLI (analyze, decompile, port, patch, verify, doctor)
tool/j2a_tool/           parser de .class, parche de bytecode, descompilación, generador
runtime/                 motor del port (se copia como módulo "engine" y se renombra al paquete del juego)
runtime-m3g/             3D M3G: motor de Nokia en C + bindings Java (EPL 1.0)
runtime-extra/           APIs de fabricante, sensores, JSR extra y MascotCapsule (de J2ME Loader, Apache 2.0)
template/                plantilla del proyecto Android Studio (Gradle 9.3.1, AGP 9.1.1, compileSdk/targetSdk 37)
samples/demo-midlet/     juego J2ME de prueba (GameCanvas, Sprite, RMS, Forms, sonido)
docs/                    documentación
```

## Probar con el juego de ejemplo

```bash
python samples/demo-midlet/build.py -o demo.jar     # genera demo.jar + demo.jad
python tool/j2a.py port demo.jar -o demo-android
python tool/j2a.py verify demo-android              # compila con javac sin abrir Gradle
```

## Aviso legal

Usa j2a con juegos de los que tengas los derechos o copias propias para uso personal.
