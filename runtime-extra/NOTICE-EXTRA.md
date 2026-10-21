# Extra device APIs (from J2ME Loader)

The Java sources in `java/` (vendor APIs: Nokia, Siemens, Motorola, Sony Ericsson, Samsung,
Vodafone/J-Phone/KDDI/JBlend, MascotCapsule 3D; JSRs: sensors 256, file/PIM 75, location 179,
messaging 120, Bluetooth 82, AMMS 234, CHAPI 211; MMAPI controls) and the native MascotCapsule
helper in `cpp/micro3d` + shaders in `assets/micro3d` come from **J2ME Loader**
(https://github.com/nikita36078/J2ME-Loader, Apache License 2.0; a few files are Nokia EPL 1.0,
see each header). Copyright of each file stays with its authors (see headers).

j2a changes: `javax/microedition/util/ContextHolder`, `ActivityResultListener` and
`javax/microedition/shell/AppClassLoader` are small compatibility classes written for j2a;
calls to J2ME Loader internals (AndroidX, preferences, its virtual keyboard, notifications)
were replaced with engine equivalents or no-ops; MascotCapsule shaders moved to `micro3d/`.
When a port is generated, packages are renamed to the app's engine package.
