@echo off
rem Abre j2a con el Python que este disponible y muestra el error si algo falla.
rem Puedes soltar uno o varios .jar sobre este archivo: se abren ya cargados en la ventana.
cd /d "%~dp0"
set PY=python
where py >nul 2>nul && set PY=py -3
rem Una sola vez: permite arrastrar .jar dentro de la ventana (si falla, la ventana funciona igual)
%PY% -c "import tkinterdnd2" >nul 2>nul || %PY% -m pip install --user --quiet tkinterdnd2
%PY% j2a-gui.pyw %*
if errorlevel 1 (
  echo.
  echo No se pudo abrir j2a. Instala Python 3.8 o superior desde https://www.python.org/downloads/
  echo marcando "Add python.exe to PATH", y vuelve a intentarlo.
  pause
)
