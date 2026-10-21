# 3. Mejoras incluidas y limitaciones

## Mejoras respecto al teléfono original

Todas se configuran en `app/src/main/assets/j2a/port.properties` del proyecto generado
(o desde código en `MainActivity.onConfigLoaded`). Algunas las puede cambiar el jugador
desde el **menú del port**, que se abre con el **gesto o botón Atrás** de Android (o las teclas
Menú/Escape). El menú también tiene accesos a las teclas izquierda y derecha del juego.

| Mejora | Opción |
|---|---|
| Pantalla: proporción original, pixel perfect, **16:9** (ensancha el juego hacia la pantalla sin pasar de 16:9: llena casi todo y deforma poco) o completa (estirar) | `scale.mode=fit|integer|wide|stretch`, menú |
| **Resolución y rotación automáticas al generar**: atributos del JAD/manifiesto, `240x320` en el nombre, modelo de teléfono en el nombre (N95, 6600, E71, 5800, K750…), imágenes del tamaño de pantalla y números de pantalla fijos en el código (p. ej. `fillRect(0,0,176,208)`) | automático |
| **Ajuste automático dentro del APK**: si el juego solo dibuja una parte de la pantalla (p. ej. 176x208 dentro de 240x320), muestra solo esa parte a pantalla completa y gira el móvil a su orientación. Se recuerda para la próxima vez | `auto.fit`, menú |
| Shaders por GPU (OpenGL ES 2.0): nítido, suavizado, *sharp bilinear*, scanlines, rejilla LCD, CRT, LCD verde | `shader=...`, menú |
| Shaders externos en formato PPSSPP (`.ini/.fsh/.vsh`), p. ej. jdgleaver/ppsspp_shaders | `--shaders carpeta/` |
| Modo de FPS: **vsync** (como el `flushGraphics` bloqueante de un teléfono), **dinámico** (Android 11+ cambia la tasa de refresco de la pantalla a la de los FPS del juego: 30/48/60/90/120 Hz, más fluido y menos batería) o sin límite. En el modo dinámico la app pide la tasa a la pantalla de dos formas (`setFrameRate` y el modo de pantalla de la ventana), porque Xiaomi y Samsung suelen ignorar la primera | `fps.mode`, menú |
| Captura de pantalla escalada sin borrosidad a la galería | menú |
| El teclado táctil se oculta solo al usar mando o teclado y vuelve al tocar la pantalla | automático |
| Botones de volumen controlan el volumen del juego | automático |
| Un hilo del juego que lanza una excepción ya no cierra la app (como en el teléfono, solo termina ese hilo); se avisa en pantalla con el error, para que una carga infinita no quede sin explicación | automático |
| En segundo plano el juego no consume CPU aunque su bucle no tenga `sleep()` | automático |
| Teclado virtual multitáctil: cruceta con diagonales (1/3/7/9), teclado numérico, soft keys con el nombre del comando | `keypad=full|minimal|none`, `keypad.alpha`, `keypad.haptics` |
| Mandos y teclados físicos (cruceta, A/B/X/Y, L1/R1, Start/Select, WASD, números) | automático |
| Pantalla táctil **multitáctil**: cada dedo llega al juego (`pointerPressed/Dragged/Released` + `com.nokia.pointer.number` como en la API multipunto de Nokia), así se puede mover y saltar a la vez en los controles táctiles del propio juego | automático |
| Juegos táctiles: si el manifiesto dice `Nokia-MIDlet-On-Screen-Keypad: no` no se muestra teclado; si el juego acepta toques, la primera vez pregunta "¿mostrar teclado?". El menú se abre con el gesto Atrás | `keypad`, `keypad.ask`, menú |
| Limitador de FPS de cadencia fija (30 da 30, no ~27) para juegos que van demasiado rápido. Funciona en los tres tipos de bucle de juego: `flushGraphics()`, `repaint()`/`serviceRepaints()` y `callSerially()` | `fps.limit=30` |
| Resolución elegible en el APK: la original del juego, 24 resoluciones clásicas (96x65 … 800x480, vertical y horizontal), **widescreen real** con la proporción del móvil o **personalizada**; el juego se reinicia solo para aplicarla | menú del port |
| Rotación elegible en el APK (automática/vertical/horizontal) | menú del port |
| **Girar imagen 90°/270°** para juegos que se juegan de lado en un teléfono vertical: la app pasa a horizontal de verdad (barra de notificaciones y gestos correctos) y el táctil se gira también. **Automático por defecto**: si casi todo lo que dibuja el juego son imágenes giradas (`TRANS_ROT90/270`, `DirectGraphics.ROTATE_90/270`), gira solo y lo recuerda | `rotate=-1|0|90|270`, `--rotate`, menú |
| Zonas que el juego nunca dibuja se ven negras (no blancas); el ajuste automático también recorta contenido centrado (p. ej. en la resolución widescreen de un juego que no se adapta) | automático |
| Entrada con baja latencia: toques sin esperar al siguiente frame (Android 11+), el hilo de eventos del juego nunca espera a la GPU y tiene prioridad de pantalla; el modo de FPS dinámico no baja de 60 Hz | automático |
| Nombre oficial: el paquete sale del propio juego (`com.sega.sonic`) o de su editor (`com.gameloft.sonicunleashed`); la carpeta y el APK se llaman como el juego (`Sonic_Unleashed-1.0.0-debug.apk`) | automático, `--package` |
| Soft keys de otros fabricantes (Motorola -21/-22, Siemens -1/-4, LG -202/-203) | `softkey.left`, `softkey.right` |
| Contador de FPS | `show.fps=true` |
| **Anti-parpadeo**: mezcla cada fotograma con el anterior. Arregla los juegos que oscurecen o hacen transparencias con líneas o puntos que alternan cada fotograma (en las pantallas lentas de antes se veían como un velo; en una moderna, como rayas que parpadean) | `frame.blend=true`, menú |
| Formularios, listas y cajas de texto con controles nativos de Android y teclado del sistema | automático |
| Pantalla completa inmersiva, soporte de notch, pantalla siempre encendida | automático |
| Pausa/reanudación correctas (`pauseApp`/`startApp`, música pausada) | automático |
| Partidas guardadas persistentes (RMS) | automático |
| Fuentes nítidas y escalables | `font.small/medium/large`, `text.antialias` |
| Valor de `microedition.platform` (algunos juegos cambian el control según el modelo) | `microedition.platform` |

## Rendimiento

- El código del juego no se interpreta: Android (ART) lo compila a código máquina, igual que haría
  un compilador de C++. Traducirlo a C++ no lo haría más rápido; los tirones venían del motor:
  - **Sonidos**: preparar un `MediaPlayer` tarda decenas de ms y antes congelaba el juego cada vez
    que sonaba un efecto. Ahora todo el audio va en su propio hilo.
  - **Basura por frame**: `drawRegion` con giro, `drawRGB` y `DirectGraphics` creaban objetos en cada
    llamada (pausas del recolector = tirones). Ahora reutilizan los suyos.
  - **APK de depuración**: un APK "debuggable" corre más lento en Android. La plantilla genera el
    APK de depuración sin modo depuración (cámbialo a `true` en `app/build.gradle` solo para usar
    breakpoints).

## Android 16/17 (targetSdk 37)

- En tablets y plegables (ancho ≥ 600dp) Android 17 ignora el bloqueo de orientación y de proporción.
  El port reescala la pantalla J2ME a cualquier ventana, así que solo cambia el tamaño o las bandas negras.
- El botón Atrás usa `OnBackInvokedCallback` además del opt-out `enableOnBackInvokedCallback=false`.
- El audio se pausa al ir a segundo plano y los sonidos que el juego lance en segundo plano esperan
  a que vuelvas (Android 17 bloquea el audio en segundo plano sin servicio en primer plano).

## Limitaciones conocidas

- **3D**: M3G (JSR-184) con el motor original de Nokia en C (`runtime-m3g/`) y MascotCapsule V3
  (también usado por las APIs 3D de JBlend, Vodafone y Motorola) con el renderizador de J2ME Loader
  (`runtime-extra/cpp/micro3d`). Solo se compilan en los ports que los usan (NDK).
- **APIs de fabricante y JSR extra** (`runtime-extra/`, de J2ME Loader): Siemens, Motorola, Sony
  Ericsson, Samsung, Vodafone/J-Phone/KDDI/JBlend, Nokia (notificaciones, pagos, M3D), sensores
  JSR-256 (acelerómetro), FileConnection/PIM JSR-75, ubicación JSR-179, SMS JSR-120, Bluetooth JSR-82,
  AMMS JSR-234, controles MMAPI. Están todas incluidas en cada port para que ningún juego se cierre por
  una clase que falta; algunas son implementaciones parciales (p. ej. las notificaciones se ignoran).
- Tonos Nokia en formato *Smart Messaging* (`Sound.FORMAT_TONE` con bytes) se ignoran en silencio.
- `CustomItem` dentro de formularios es básico.
- El runtime está verificado en compilación contra la API de Android y el parche de bytecode contra
  el verificador de la JVM; cada juego real puede destapar comportamientos que haya que ajustar en
  `engine/` — por eso el motor se copia dentro de cada proyecto, para que puedas tocarlo.
- No hay "save states" (guardar en cualquier momento): un port nativo no puede congelar el estado
  de la memoria como un emulador. El juego guarda con su propio sistema (RMS), que sí funciona.
- Los shaders de jdgleaver/ppsspp_shaders son GPL v2+: si los incluyes con `--shaders`, el APK
  que distribuyas queda sujeto a esa licencia. Los shaders integrados son código propio.

## Cómo añadir una API que falta

1. `j2a analyze juego.jar` te dice qué clase o método falta.
2. Crea la clase con el mismo paquete y nombre en `runtime/src/main/java/` (por ejemplo
   `com/siemens/mp/game/Light.java`) usando APIs de Android.
3. Vuelve a ejecutar `j2a analyze`: la clase aparecerá como soportada, y todos los ports que generes
   a partir de ahí la incluirán.

## Aviso legal

Porta únicamente juegos de los que tengas los derechos, o para uso personal con copias que poseas.
Descompilar y redistribuir juegos comerciales sin permiso puede infringir derechos de autor.
