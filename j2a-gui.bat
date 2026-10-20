@echo off
rem Abre j2a con el Python que este disponible y muestra el error si algo falla.
cd /d "%~dp0"
where py >nul 2>nul && (py -3 j2a-gui.pyw) || (python j2a-gui.pyw)
if errorlevel 1 (
  echo.
  echo No se pudo abrir j2a. Instala Python 3.8 o superior desde https://www.python.org/downloads/
  echo marcando "Add python.exe to PATH", y vuelve a intentarlo.
  pause
)
