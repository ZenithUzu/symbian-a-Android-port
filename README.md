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

1. **Arrastra** uno o varios `.jar` a la lista (o **Añadir juegos…**, o suéltalos sobre `j2a-gui.bat`).
   Cada juego muestra su nombre, su paquete y dónde se creará su proyecto, todo automático.
2. Si quieres cambiar algo de un juego (nombre, paquete o carpeta), haz **doble clic** en él.
3. **Guardar**: *junto a cada juego* (`Juego-android` al lado de su `.jar`) o *en esta carpeta*, que
   queda como carpeta por defecto la próxima vez.
4. **Crear ports**. Se crean uno tras otro; si uno falla, los demás siguen. Si un proyecto ya existe,
   la ventana pregunta si reemplazarlo.
5. **Abrir carpeta** y ábrela en Android Studio (File → Open) → Run ▶. Ese proyecto es el código ya
   descompilado: lo puedes corregir o mejorar ahí mismo.

**Opciones** (plegadas, todo automático por defecto): resolución, rotación, pantalla, shader, FPS y teclado,
que se aplican a todos los juegos de la lista.

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
| `--orientation portrait/landscape` | Fija la orientación. Por defecto la app gira con el teléfono. |
| `--scale fit/integer/stretch` | Escalado. |
| `--shader xbr+crt-lottes` | Filtros: uno de escalado (`nearest`, `sharp`, `smooth`, `xbr`, `mmpx`, `aann`) y efectos encima con `+` (`crt`, `crt-lottes`, `scanlines`, `lcd`, `dot`, `mono`). |
| `--shaders carpeta/` | Incluye filtros de RetroArch (`.glsl`, [libretro/glsl-shaders](https://github.com/libretro/glsl-shaders)) o PPSSPP (`.ini` + `.fsh`). Revisa su licencia: muchos son GPL. |
| `--fps-mode vsync/dynamic/unlimited` | Sincronizado con la pantalla, dinámico (la pantalla adapta sus Hz a los FPS del juego) o sin límite. |
| `--keep-j2me-packages` | No renombrar las APIs al paquete del motor (ver "Port nativo"). |
| `--keypad gamepad/full/minimal/none`, `--pad-style xbox/switch/play` | Controles en pantalla: mando (por defecto), teclado del teléfono, cruceta + OK u ocultos. |
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
(la del teléfono por defecto, o fija vertical/horizontal), centrar la imagen, escalado (proporcional, entero,
16:9, estirar), **filtros** (uno de escalado: nítido, sharp, suavizado, **xBR**, **MMPX**, **AANN**; y los efectos
que quieras encima a la vez: **CRT**, **CRT Lottes**, scanlines, rejilla LCD, matriz de puntos, LCD verde, más
los `.glsl` de RetroArch o PPSSPP que incluyas), **modo de FPS** (vsync, dinámico, sin límite), límite de FPS,
contador de FPS, anti-parpadeo, sonido, **captura de pantalla** y restablecer opciones.

En **Controles…**: mando en pantalla con skin **Xbox**, **Switch** o **PlayStation** (cruceta **y** joystick
independientes, 4 botones, L/R, **Select/Start** = las teclas izquierda/derecha del teléfono, y **123** para el
teclado numérico), teclado de teléfono, **asignar la tecla del juego** a cada control (también los del mando
físico: L2/R2, L3/R3, stick derecho), controles visibles, **moverlos arrastrándolos**, tamaño, opacidad y
vibración. Un mando físico se mapea solo con las mismas teclas.
Todo se guarda entre sesiones.

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
