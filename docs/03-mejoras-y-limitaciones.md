# 3. Mejoras incluidas y limitaciones

## Mejoras respecto al teléfono original

Todas se configuran en `app/src/main/assets/j2a/port.properties` del proyecto generado
(o desde código en `MainActivity.onConfigLoaded`). Algunas las puede cambiar el jugador
desde el **menú del port** (botón ☰ del teclado virtual, o tecla Escape).

| Mejora | Opción |
|---|---|
| Escalado a cualquier pantalla: proporcional, entero (pixel-perfect) o estirado | `scale.mode=fit|integer|stretch` |
| Shaders por GPU (OpenGL ES 2.0): nítido, suavizado, *sharp bilinear*, scanlines, rejilla LCD, CRT, LCD verde | `shader=...`, menú |
| Shaders externos en formato PPSSPP (`.ini/.fsh/.vsh`), p. ej. jdgleaver/ppsspp_shaders | `--shaders carpeta/` |
| Modo de FPS: **vsync** (como el `flushGraphics` bloqueante de un teléfono), **dinámico** (Android 11+ cambia la tasa de refresco de la pantalla a la de los FPS del juego: 30/48/60/90/120 Hz, más fluido y menos batería) o sin límite | `fps.mode`, menú |
| Captura de pantalla escalada sin borrosidad a la galería | menú |
| El teclado táctil se oculta solo al usar mando o teclado y vuelve al tocar la pantalla | automático |
| Botones de volumen controlan el volumen del juego | automático |
| Un hilo del juego que lanza una excepción ya no cierra la app (como en el teléfono, solo termina ese hilo) | automático |
| En segundo plano el juego no consume CPU aunque su bucle no tenga `sleep()` | automático |
| Teclado virtual multitáctil: cruceta con diagonales (1/3/7/9), teclado numérico, soft keys con el nombre del comando | `keypad=full|minimal|none`, `keypad.alpha`, `keypad.haptics` |
| Mandos y teclados físicos (cruceta, A/B/X/Y, L1/R1, Start/Select, WASD, números) | automático |
| Pantalla táctil enviada al juego como `pointerPressed/Dragged/Released` | automático |
| Limitador de FPS de cadencia fija (30 da 30, no ~27) para juegos que van demasiado rápido | `fps.limit=30` |
| Resolución elegible en el APK, incluido **widescreen** con la proporción del móvil (requiere reiniciar; útil en juegos que leen `getWidth()/getHeight()`) | menú del port |
| Rotación elegible en el APK (automática/vertical/horizontal) | menú del port |
| Soft keys de otros fabricantes (Motorola -21/-22, Siemens -1/-4, LG -202/-203) | `softkey.left`, `softkey.right` |
| Contador de FPS | `show.fps=true` |
| Formularios, listas y cajas de texto con controles nativos de Android y teclado del sistema | automático |
| Pantalla completa inmersiva, soporte de notch, pantalla siempre encendida | automático |
| Pausa/reanudación correctas (`pauseApp`/`startApp`, música pausada) | automático |
| Partidas guardadas persistentes (RMS) | automático |
| Fuentes nítidas y escalables | `font.small/medium/large`, `text.antialias` |
| Valor de `microedition.platform` (algunos juegos cambian el control según el modelo) | `microedition.platform` |

## Android 16/17 (targetSdk 37)

- En tablets y plegables (ancho ≥ 600dp) Android 17 ignora el bloqueo de orientación y de proporción.
  El port reescala la pantalla J2ME a cualquier ventana, así que solo cambia el tamaño o las bandas negras.
- El botón Atrás usa `OnBackInvokedCallback` además del opt-out `enableOnBackInvokedCallback=false`.
- El audio se pausa al ir a segundo plano y los sonidos que el juego lance en segundo plano esperan
  a que vuelvas (Android 17 bloquea el audio en segundo plano sin servicio en primer plano).

## Limitaciones conocidas

- **3D**: M3G (JSR-184) y MascotCapsule no están implementados. `j2a analyze` lo avisa.
- **APIs de fabricante poco comunes** (Siemens, Motorola, Sony Ericsson específicas): no implementadas.
- **SMS (JSR-120), Bluetooth (JSR-82), FileConnection (JSR-75), ubicación (JSR-179)**: no implementadas.
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
