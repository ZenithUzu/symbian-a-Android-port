# M3G (JSR-184) 3D

`cpp/m3g` is Nokia's M3G implementation (Symbian Foundation, **Eclipse Public License 1.0**),
as adapted for Android by the J2ME Loader project (https://github.com/nikita36078/J2ME-Loader,
Apache License 2.0). `java/javax/microedition/m3g` are the matching Java bindings (EPL 1.0).

Changes for j2a:
- `Graphics3D.bindTarget` renders into the engine's screen bitmap (`Graphics.j2aTarget()`) and
  tells AutoFit that 3D covers the whole bound area.
- `Loader` opens JAR resources with `j2a.core.ResourceShim`.
- When a port is generated, the Java package is renamed to the app's engine package and the JNI
  function names in the C sources are renamed to match.

EPL 1.0 is a file-level copyleft: if you distribute an APK that contains these files, make the
source of these (possibly modified) files available under the EPL.
