# 1. Cómo se descompila un juego J2ME

## Qué hay dentro de un juego Symbian / Series 40 en Java

Un juego Java ME ("J2ME") para Nokia Symbian S60, Series 40, Sony Ericsson, Samsung, etc. se distribuye como:

| Archivo | Contenido |
|---|---|
| `Juego.jar` | Un ZIP con las clases compiladas (`.class`), las imágenes (`.png`), sonidos (`.mid`, `.wav`, `.amr`, `.mp3`), niveles y textos, y `META-INF/MANIFEST.MF`. |
| `Juego.jad` (opcional) | Texto plano con los mismos atributos del manifiesto, más `MIDlet-Jar-URL` y `MIDlet-Jar-Size`. Si existe, sus valores tienen prioridad. |

Atributos importantes del manifiesto:

```
MIDlet-Name: Mi Juego
MIDlet-Vendor: Estudio
MIDlet-Version: 1.0.2
MIDlet-1: Mi Juego, /icon.png, com.estudio.MiMIDlet   <- nombre, icono, clase principal
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
Nokia-MIDlet-Original-Display-Size: 240,320           <- resolución pensada por el autor
```

Las clases son **bytecode Java estándar**, normalmente compilado para Java 1.1–1.4 (versiones de class file 45.3–48.0), y pasadas por el *preverificador* de CLDC (añade el atributo `StackMap`). Casi todos los juegos comerciales están **ofuscados** con ProGuard/RetroGuard: clases `a`, `b`, `c`, métodos `a()`, `b(int)`...

## Herramientas para descompilar

| Herramienta | Uso en j2a | Notas |
|---|---|---|
| **Vineflower** (fork moderno de Fernflower, el descompilador de IntelliJ) | Motor por defecto (`--engine vineflower`) | El que mejor reconstruye `try/finally` antiguos (instrucciones `jsr/ret`), `switch` y bucles. Versión 1.12.0. |
| **CFR** | Alternativa (`--engine cfr`) | Útil cuando Vineflower falla en un método concreto. Versión 0.152. |
| JD-GUI / Recaf / Bytecode Viewer | Manual | Para mirar el código a ojo; j2a no los necesita. |

j2a descarga los JAR de Vineflower y CFR automáticamente desde sus releases de GitHub a `~/.j2a/tools/` la primera vez. Solo hace falta Java, y Android Studio ya trae uno (carpeta `jbr`), que j2a detecta solo.

```bash
python tool/j2a.py decompile MiJuego.jar -o MiJuego-src
```

## Lo que hace j2a antes y después de descompilar

1. **Parche de bytecode** (`j2a patch`): en J2ME es muy común cargar recursos con
   `"".getClass().getResourceAsStream("/img.png")`. En Android eso devuelve `null`, porque
   `String` pertenece al *boot class loader*, que no ve el APK. j2a reescribe cada
   `invokevirtual Class.getResourceAsStream` por `invokestatic j2a.core.ResourceShim.getResourceAsStream(Class, String)`.
   La pila de operandos es idéntica, así que no hay que tocar nada más del método (ni los `StackMap`).
2. **Descompilación** con el runtime J2ME de j2a como biblioteca (`-e`), para que el descompilador
   conozca los tipos `javax.microedition.*` y no ponga casts raros.
3. **Detección de fallos**: si una clase sale con marcas como `$VF: Couldn't be decompiled` o
   `<undefinedtype>`, se deja como bytecode.
4. **Verificación con javac**: si encuentra tu Android SDK, compila el código descompilado contra
   `android.jar` + runtime + JAR original. Las clases que no compilan se quedan como bytecode
   (iterando hasta que todo compile). Así el proyecto **siempre** compila en Android Studio.
5. **Arreglos de sintaxis**: identificadores que eran válidos en Java 1.4 y ahora son palabras
   reservadas (`enum`, `assert`) se renombran.

## Ofuscación: qué esperar

El código descompilado de un juego ofuscado compila y funciona igual, pero se lee así:

```java
public final class c extends Canvas implements Runnable {
   private static int[] a;
   private Image b;
   protected final void keyPressed(int var1) {
      if (this.a(var1) == 1) { ... }
```

Para modificar el juego conviene:
- Buscar primero las llamadas a la API J2ME (que **no** están ofuscadas): `drawImage`, `keyPressed`,
  `RecordStore.openRecordStore`, `Manager.createPlayer`... Son los "anclajes" para entender cada clase.
- Usar el modo `hybrid` para descompilar solo las clases que vas a tocar
  (`--mode hybrid --edit c,f`), dejando el resto como bytecode original.
- Renombrar con el refactor de Android Studio (Shift+F6) a medida que entiendes cada cosa.
