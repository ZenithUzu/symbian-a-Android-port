# 2. El "lenguaje de traducción": de J2ME a Android

## No hace falta traducir el lenguaje, sino la plataforma

J2ME y Android usan **el mismo lenguaje: Java**. Lo que cambia es la *plataforma* (las bibliotecas):

| | J2ME (Symbian / S40) | Android |
|---|---|---|
| Lenguaje | Java 1.1–1.4 | Java (hasta 17+) / Kotlin |
| Máquina virtual | KVM / CLDC HotSpot | ART (bytecode DEX) |
| Bibliotecas base | CLDC 1.1 (`java.lang`, `java.util`, `java.io`) | Casi todo Java SE: CLDC cabe dentro |
| UI / gráficos | `javax.microedition.lcdui` | `android.view`, `android.graphics` |
| Guardado | RMS (`javax.microedition.rms`) | archivos / SharedPreferences |
| Sonido | MMAPI (`javax.microedition.media`) | `MediaPlayer`, `AudioTrack` |
| Entrada | teclado del teléfono, códigos -1..-7, 0-9 | pantalla táctil, mandos, teclados |

Por eso j2a **no reescribe el juego a Kotlin ni a otro lenguaje**. Hace dos cosas:

1. **El bytecode se reutiliza tal cual.** El compilador D8 de Android (el que usa Gradle) convierte
   `.class` de Java 1.1 a DEX sin problemas, incluso con `jsr/ret`. El código descompilado también
   es Java normal que Android Studio compila.
2. **Se implementa la plataforma J2ME encima de Android.** El módulo `j2me-runtime` contiene
   clases con **los mismos nombres de paquete** (`javax.microedition.lcdui.Canvas`, `Graphics`,
   `Sprite`, `RecordStore`, `Player`...) pero programadas con APIs de Android. El juego cree que
   está en un Nokia; en realidad cada llamada termina en Android.

Es el mismo principio que usan J2ME Loader o los ports oficiales "J2ME Android Bridge" de Nokia,
pero aquí el resultado es **un proyecto de Android Studio con código editable**, no un emulador.

```
┌───────────────────────────── APK ─────────────────────────────┐
│  Juego (código descompilado editable  +  libs/game.jar)        │
│        │  llama a javax.microedition.* / com.nokia.mid.*       │
│        ▼                                                       │
│  j2me-runtime  (implementación de J2ME con APIs de Android)    │
│        │  Canvas → SurfaceView, Graphics → android.graphics    │
│        ▼                                                       │
│  Android (ART, GPU, MediaPlayer, almacenamiento, táctil)       │
└────────────────────────────────────────────────────────────────┘
```

## Tabla de traducción de APIs

| J2ME | Implementación Android en `j2me-runtime` |
|---|---|
| `MIDlet` (`startApp/pauseApp/destroyApp`) | `J2meActivity`: `onCreate` → constructor + `startApp`, `onPause` → `pauseApp`, `onResume` → `startApp`, `notifyDestroyed` → `finish()` |
| `Display.setCurrent(Canvas)` | Muestra la `ScreenView` (SurfaceView) |
| `Display.setCurrent(Form/List/TextBox/Alert)` | Vistas nativas Android (`EditText`, `RadioButton`, `SeekBar`...) — mucho más cómodas en táctil |
| `Canvas.paint(Graphics)` / `repaint()` / `serviceRepaints()` | Hilo de eventos J2ME único (`EventQueue`), dibuja en un `Bitmap` del tamaño virtual y lo escala a la pantalla |
| `GameCanvas.getGraphics()` / `flushGraphics()` / `getKeyStates()` | Búfer propio + presentación inmediata; estados de teclas *latched* como en MIDP |
| `Graphics` (`drawImage`, `drawRegion`, `drawString`, `fillArc`, `drawRGB`, `setClip`...) | `android.graphics.Canvas` + `Paint` sin antialiasing, recorte y traslación con semántica exacta de MIDP |
| `Image.createImage(...)`, `getRGB`, `createRGBImage` | `Bitmap` ARGB_8888 (`BitmapFactory`) |
| `Font` (SMALL/MEDIUM/LARGE) | `Typeface` + tamaños proporcionales a la resolución virtual |
| `Sprite`, `TiledLayer`, `LayerManager` | Reimplementados completos: frames, transformaciones, píxel de referencia, colisión por rectángulo y por píxel |
| `RecordStore` (RMS) | Un archivo por store en `files/rms/`, escritura atómica |
| `Manager.createPlayer` (MIDI, WAV, MP3, AMR) | `MediaPlayer` (Android reproduce MIDI de forma nativa) |
| `Manager.playTone`, `ToneControl` | Sintetizador propio con `AudioTrack` |
| `Connector.open("http://...")` | `HttpURLConnection` |
| `com.nokia.mid.ui.FullCanvas`, `DirectGraphics`, `DirectUtils`, `DeviceControl` | Soportados (rotaciones/espejos, polígonos, píxeles 4444/565/8888, vibración) |
| `com.nokia.mid.sound.Sound` | Sobre MMAPI |
| `com.samsung.util.AudioClip / Vibration / LCDLight` | Soportados |
| `Class.getResourceAsStream` | `ResourceShim` (parche de bytecode); los recursos van en `src/main/resources` |
| Teclas `-1..-7`, `0-9`, `*`, `#` | Teclado virtual en pantalla, teclas físicas, WASD y mandos (A = OK, B = soft derecha, Start = soft izquierda) |

`j2a analyze` compara automáticamente las clases y métodos que usa cada juego contra este runtime y te dice qué falta.

## Ejemplo: cómo queda una llamada

Código original del juego (J2ME):

```java
protected void paint(Graphics g) {
    g.drawRegion(sprites, 16, 0, 16, 16, Sprite.TRANS_MIRROR, x, y, Graphics.TOP | Graphics.LEFT);
}
```

No cambia nada en el juego. Lo que se ejecuta en Android es la implementación del runtime:

```java
// j2me-runtime: javax/microedition/lcdui/Graphics.java
Matrix m = new Matrix();
m.postScale(-1, 1); m.postTranslate(w, 0);       // TRANS_MIRROR
canvas.save(); canvas.translate(x, y); canvas.concat(m);
canvas.drawBitmap(src.bitmap, new Rect(16, 0, 32, 16), new RectF(0, 0, 16, 16), paint);
canvas.restore();
```

## ¿Y si quiero el juego en Kotlin?

Una vez generado el proyecto, Android Studio puede convertir cualquier archivo con
*Code → Convert Java File to Kotlin File*. Hazlo solo con las clases que vayas a modificar mucho:
la conversión automática de código ofuscado suele necesitar retoques, y Java funciona igual de bien.
