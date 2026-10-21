# Skin del mando en pantalla

Imágenes del paquete "3ds_v2" que proporcionó el usuario del repositorio (overlay estilo 3DS para
emuladores), recortadas. Solo se usan los controles útiles en un juego J2ME: cruceta, joystick,
A B X Y, L R, Select y Start. Cada `<nombre>.png` tiene su `<nombre>_pressed.png`;
`joystick_base.png` (la base sin palanca, hecha por j2a a partir de `joystick.png`) queda fija
mientras `joystick_pressed.png` se mueve; `dpad_pressed_one.png` es "arriba" y `dpad_pressed_two.png` "arriba + izquierda" (se giran para el resto).

Para otro skin en un port concreto, pon PNG con los mismos nombres en `app/src/main/assets/skin/`.
