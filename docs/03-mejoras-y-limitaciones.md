# 3. Mejoras incluidas y limitaciones

## Mejoras respecto al teléfono original

Todas se configuran en `app/src/main/assets/j2a/port.properties` del proyecto generado
(o desde código en `MainActivity.onConfigLoaded`). Algunas las puede cambiar el jugador
desde el **menú del port** (botón ☰ del teclado virtual, o tecla Escape).

| Mejora | Opción |
|---|---|
| Escalado a cualquier pantalla: proporcional, entero (pixel-perfect) o estirado | `scale.mode=fit|integer|stretch` |
| Suavizado bilineal opcional | `scale.filter=true` |
| Dibujado por GPU en Android 8+ (`lockHardwareCanvas`) | automático |
| Teclado virtual multitáctil: cruceta con diagonales (1/3/7/9), teclado numérico, soft keys con el nombre del comando | `keypad=full|minimal|none`, `keypad.alpha`, `keypad.haptics` |
| Mandos y teclados físicos (cruceta, A/B/X/Y, L1/R1, Start/Select, WASD, números) | automático |
| Pantalla táctil enviada al juego como `pointerPressed/Dragged/Released` | automático |
| Limitador de FPS para juegos que van demasiado rápido en CPUs modernas | `fps.limit=30` |
| Contador de FPS | `show.fps=true` |
| Formularios, listas y cajas de texto con controles nativos de Android y teclado del sistema | automático |
| Pantalla completa inmersiva, soporte de notch, pantalla siempre encendida | automático |
| Pausa/reanudación correctas (`pauseApp`/`startApp`, música pausada) | automático |
| Partidas guardadas persistentes (RMS) | automático |
| Fuentes nítidas y escalables | `font.small/medium/large`, `text.antialias` |
| Valor de `microedition.platform` (algunos juegos cambian el control según el modelo) | `microedition.platform` |

## Limitaciones conocidas

- **3D**: M3G (JSR-184) y MascotCapsule no están implementados. `j2a analyze` lo avisa.
- **APIs de fabricante poco comunes** (Siemens, Motorola, Sony Ericsson específicas): no implementadas.
- **SMS (JSR-120), Bluetooth (JSR-82), FileConnection (JSR-75), ubicación (JSR-179)**: no implementadas.
- Tonos Nokia en formato *Smart Messaging* (`Sound.FORMAT_TONE` con bytes) se ignoran en silencio.
- `CustomItem` dentro de formularios es básico.
- El runtime está verificado en compilación contra la API de Android y el parche de bytecode contra
  el verificador de la JVM; cada juego real puede destapar comportamientos que haya que ajustar en
  `j2me-runtime/` — por eso el runtime se copia dentro de cada proyecto, para que puedas tocarlo.

## Cómo añadir una API que falta

1. `j2a analyze juego.jar` te dice qué clase o método falta.
2. Crea la clase con el mismo paquete y nombre en `runtime/src/main/java/` (por ejemplo
   `com/siemens/mp/game/Light.java`) usando APIs de Android.
3. Vuelve a ejecutar `j2a analyze`: la clase aparecerá como soportada, y todos los ports que generes
   a partir de ahí la incluirán.

## Aviso legal

Porta únicamente juegos de los que tengas los derechos, o para uso personal con copias que poseas.
Descompilar y redistribuir juegos comerciales sin permiso puede infringir derechos de autor.
