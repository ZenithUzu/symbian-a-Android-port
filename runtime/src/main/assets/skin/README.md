# Skin del mando en pantalla

Imágenes del paquete "3ds_v2" que proporcionó el usuario del repositorio (overlay estilo 3DS para
emuladores), recortadas. Solo se usan los controles útiles en un juego J2ME: cruceta, joystick,
A B X Y, L R, Select y Start. Cada `<nombre>.png` tiene su `<nombre>_pressed.png`;
`joystick_base.png` (la base sin palanca, hecha por j2a a partir de `joystick.png`) queda fija
mientras `joystick_pressed.png` se mueve. La cruceta tiene una imagen por dirección (`dpad_up.png`,
`dpad_down_left.png`...), también hechas por j2a desde `dpad.png` oscureciendo la zona pulsada, para que la
luz del relieve no gire; un skin en formato Citra (`dpad_pressed_one/two.png`) también sirve.

Para otro skin en un port concreto, pon PNG con los mismos nombres en `app/src/main/assets/skin/`.
