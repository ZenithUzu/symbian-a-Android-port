# j2a Offline para Android

Convierte un JAR Java ME a una **APK firmada directamente en Android**, sin Python, Gradle,
servidor, Termux ni PC durante la conversión. La aplicación incluye D8, apksig y una plantilla
compilada del motor, con los menús/filtros actuales y bibliotecas M3G/MascotCapsule para ARM y x86_64.
La herramienta no declara permiso de Internet. Los APK de juegos sí pueden usar la red, como el motor de escritorio.

## Uso

1. Instala `converter-debug.apk` del artefacto **j2a-offline-android** en GitHub Actions.
2. Selecciona un JAR; añade su JAD si contiene información que falta en el manifiesto.
3. Pulsa **Crear APK en este teléfono**. La notificación mantiene la compilación en primer plano.
4. Pulsa **Guardar APK** y elige dónde guardarla con el selector de archivos de Android.

No exporta proyectos y no usa ADB. El juego conserva su bytecode y recursos originales, convertidos
a DEX; no se descompila. Cada juego tiene un applicationId propio, estable entre versiones que
conservan nombre, editor y clase MIDlet. Los ajustes se cambian dentro del juego.

## Requisitos y límites

- Android 8/API 26 o posterior. La prueba automatizada completa usa Android 15/API 35; otros
  fabricantes/versiones necesitan pruebas, especialmente el compilador D8 en Android antiguo.
- Se recomienda un dispositivo con al menos 4 GB de RAM y espacio libre para los archivos temporales.
  Límite de entrada: 64 MiB; recursos descomprimidos: 200 MiB, con 32 MiB por entrada.
- PNG/GIF, manifiesto/JAD, nombre y llamadas de dibujo/buffers aportan pistas de resolución.
  La confianza se muestra en el registro. Si falta evidencia se usa 240x320 y el menú permite corregirla.
- La clave se genera en **AndroidKeyStore** y queda ligada a esta instalación. Borrar datos o
  desinstalar la herramienta elimina la posibilidad de actualizar APKs firmadas con esa clave.
  No es la clave de depuración de tu PC: una APK de escritorio con el mismo paquete/firma distinta
  no se puede actualizar directamente. La aplicación no desinstala juegos ni borra sus datos.
- APIs ausentes, ofuscación incompatible, cámaras fijas o recursos cifrados siguen necesitando
  parches del juego; una APK compilada no garantiza que todos los juegos funcionen.
- Una interrupción del proceso permite volver a intentarlo, pero no reanuda un compilador a medias.

## Compilar la herramienta una vez

Abre esta carpeta en Android Studio o ejecuta `./gradlew :converter:assembleDebug` con JDK 17,
SDK 35, Build Tools 35 y NDK 28.2.13676358. Gradle compila primero la plantilla con todos los
módulos del motor y la incluye como asset en la APK de la herramienta. Esta preparación necesita
internet; **las conversiones posteriores en el teléfono no**.

El workflow `.github/workflows/android-offline.yml` compila la herramienta, ejecuta una conversión
real en un emulador con Wi-Fi/datos apagados, verifica la firma, instala la APK resultante y abre
el juego de ejemplo. **j2a-offline-android** incluye solo el instalador de la herramienta;
**j2a-offline-validation** contiene el juego de prueba convertido y los registros.
Comprueba el resultado del workflow antes de considerar validada una compilación.

D8/R8: BSD 3-Clause. apksig: Apache 2.0. Los módulos extra y 3D conservan sus avisos/licencias
en `runtime-extra/NOTICE-EXTRA.md`, `runtime-m3g/NOTICE-M3G.md` y los shaders del motor.
Las fuentes de herramientas están documentadas en los proyectos oficiales:
[R8](https://r8.googlesource.com/r8/), [apksig](https://android.googlesource.com/platform/tools/apksig/).
