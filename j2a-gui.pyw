#!/usr/bin/env python3
"""j2a con interfaz gráfica. En Windows basta con doble clic en este archivo.

Es una ventana encima de los mismos comandos de tool/j2a.py: lo que genera es idéntico.
"""
import contextlib
import os
import queue
import subprocess
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "tool"))

import tkinter as tk  # noqa: E402
from tkinter import filedialog, messagebox, ttk  # noqa: E402

from j2a_tool import cli  # noqa: E402

# (texto visible, valor del comando); None = no pasar la opción (j2a decide/detecta)
MODES = [("Código editable (recomendado)", "source"), ("Bytecode original (máxima fidelidad)", "jar")]
SCREENS = [("Detectar automáticamente", None), ("128x160", "128x160"), ("176x208", "176x208"),
           ("176x220", "176x220"), ("240x320", "240x320"), ("320x240 (horizontal)", "320x240"),
           ("240x400", "240x400"), ("360x640", "360x640"), ("640x360 (horizontal)", "640x360")]
ORIENTATIONS = [("Detectar automáticamente", None), ("Vertical", "portrait"), ("Horizontal", "landscape"),
                ("Girar libremente", "auto")]
SHADERS = [("Píxeles nítidos", "nearest"), ("Nítido sin deformar (sharp)", "sharp"), ("Suavizado", "smooth"),
           ("Scanlines", "scanlines"), ("Rejilla LCD", "lcd"), ("CRT", "crt"), ("LCD verde", "mono")]
FPS_MODES = [("Sincronizado (vsync)", "vsync"), ("Dinámico (la pantalla se adapta)", "dynamic"),
             ("Sin límite", "unlimited")]
FPS_LIMITS = [("Sin límite", "0"), ("15", "15"), ("20", "20"), ("25", "25"), ("30", "30"), ("60", "60")]
KEYPADS = [("Automático (juegos táctiles: preguntar)", None), ("Completo (cruceta + números)", "full"),
           ("Cruceta + OK", "minimal"), ("Sin teclado (el juego trae controles táctiles)", "none")]


def build_args(v):
    """Lista de argumentos para 'j2a port' a partir de los valores de la ventana."""
    a = ["port", v["jar"], "-o", v["out"], "--mode", v["mode"], "--shader", v["shader"],
         "--fps-mode", v["fps_mode"], "--fps", v["fps"]]
    for key, flag in (("keypad", "--keypad"), ("screen", "--screen"), ("orientation", "--orientation"), ("name", "--name"),
                      ("package", "--package"), ("shaders", "--shaders")):
        if v.get(key):
            a += [flag, v[key]]
    if v.get("force"):
        a.append("--force")
    return a


class QueueWriter:
    """Manda lo que j2a imprime a la ventana (desde el hilo de trabajo)."""

    def __init__(self, q):
        self.q = q

    def write(self, s):
        if s:
            self.q.put(s)

    def flush(self):
        pass


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("j2a — Ports de juegos J2ME a Android")
        self.minsize(640, 560)
        self.q = queue.Queue()
        self.busy = False
        self.vars = {}
        f = ttk.Frame(self, padding=12)
        f.pack(fill="both", expand=True)
        f.columnconfigure(1, weight=1)
        r = 0
        r = self.file_row(f, r, "Juego (.jar)", "jar", self.pick_jar)
        r = self.file_row(f, r, "Guardar proyecto en", "out", self.pick_out)
        r = self.entry_row(f, r, "Nombre de la app (opcional)", "name")
        r = self.entry_row(f, r, "Paquete (opcional, ej. com.yo.juego)", "package")
        r = self.combo_row(f, r, "Modo", "mode", MODES)
        r = self.combo_row(f, r, "Resolución", "screen", SCREENS)
        r = self.combo_row(f, r, "Rotación", "orientation", ORIENTATIONS)
        r = self.combo_row(f, r, "Shader", "shader", SHADERS)
        r = self.combo_row(f, r, "Modo de FPS", "fps_mode", FPS_MODES)
        r = self.combo_row(f, r, "Límite de FPS", "fps", FPS_LIMITS)
        r = self.combo_row(f, r, "Teclado táctil", "keypad", KEYPADS)
        r = self.file_row(f, r, "Shaders PPSSPP (opcional)", "shaders", self.pick_shaders)
        self.vars["force"] = tk.BooleanVar(value=False)
        ttk.Checkbutton(f, text="Reemplazar la carpeta si ya existe", variable=self.vars["force"]).grid(
            row=r, column=1, sticky="w", pady=(4, 8))
        r += 1

        bar = ttk.Frame(f)
        bar.grid(row=r, column=0, columnspan=3, sticky="ew", pady=4)
        self.buttons = [
            ttk.Button(bar, text="Comprobar entorno", command=lambda: self.run(["doctor"])),
            ttk.Button(bar, text="Analizar juego", command=self.analyze),
            ttk.Button(bar, text="Generar port", command=self.port),
            ttk.Button(bar, text="Abrir carpeta", command=self.open_out),
        ]
        for b in self.buttons:
            b.pack(side="left", padx=(0, 6))
        r += 1

        self.log = tk.Text(f, height=14, wrap="word", state="disabled")
        self.log.grid(row=r, column=0, columnspan=3, sticky="nsew", pady=(6, 0))
        f.rowconfigure(r, weight=1)
        self.write("1. Elige el .jar  2. Pulsa «Generar port»  3. Abre la carpeta en Android Studio y pulsa Run.\n")
        self.after(100, self.poll)

    # ------------------------------------------------------------------ rows
    def entry_row(self, f, r, label, key):
        ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", pady=2)
        self.vars[key] = tk.StringVar()
        ttk.Entry(f, textvariable=self.vars[key]).grid(row=r, column=1, columnspan=2, sticky="ew", pady=2)
        return r + 1

    def file_row(self, f, r, label, key, cmd):
        ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", pady=2)
        self.vars[key] = tk.StringVar()
        ttk.Entry(f, textvariable=self.vars[key]).grid(row=r, column=1, sticky="ew", pady=2)
        ttk.Button(f, text="Elegir…", command=cmd).grid(row=r, column=2, padx=(6, 0), pady=2)
        return r + 1

    def combo_row(self, f, r, label, key, options):
        ttk.Label(f, text=label).grid(row=r, column=0, sticky="w", pady=2)
        var = tk.StringVar(value=options[0][0])
        self.vars[key] = var
        self.vars[key + "#opts"] = dict(options)
        ttk.Combobox(f, textvariable=var, values=[o[0] for o in options], state="readonly").grid(
            row=r, column=1, columnspan=2, sticky="ew", pady=2)
        return r + 1

    def value(self, key):
        v = self.vars[key].get()
        opts = self.vars.get(key + "#opts")
        return opts[v] if opts else v.strip()

    # --------------------------------------------------------------- pickers
    def pick_jar(self):
        p = filedialog.askopenfilename(title="Elige el juego", filetypes=[("Juegos J2ME", "*.jar"), ("Todos", "*.*")])
        if p:
            self.vars["jar"].set(p)
            if not self.vars["out"].get():
                base = os.path.splitext(os.path.basename(p))[0]
                self.vars["out"].set(os.path.join(os.path.dirname(p), base + "-android"))

    def pick_out(self):
        p = filedialog.askdirectory(title="Carpeta donde guardar el proyecto")
        if p:
            self.vars["out"].set(p)

    def pick_shaders(self):
        p = filedialog.askdirectory(title="Carpeta con shaders .ini/.fsh (formato PPSSPP)")
        if p:
            self.vars["shaders"].set(p)

    # --------------------------------------------------------------- actions
    def need_jar(self):
        jar = self.value("jar")
        if not jar or not os.path.isfile(jar):
            messagebox.showwarning("j2a", "Primero elige el archivo .jar del juego.")
            return None
        return jar

    def analyze(self):
        jar = self.need_jar()
        if jar:
            self.run(["analyze", jar])

    def port(self):
        jar = self.need_jar()
        if not jar:
            return
        if not self.value("out"):
            messagebox.showwarning("j2a", "Elige dónde guardar el proyecto.")
            return
        v = {k: self.value(k) for k in ("jar", "out", "name", "package", "mode", "screen", "orientation",
                                        "shader", "fps_mode", "fps", "keypad", "shaders")}
        v["force"] = self.vars["force"].get()
        self.run(build_args(v), done=lambda ok: ok and messagebox.showinfo(
            "j2a", "Proyecto listo.\n\nAbre la carpeta en Android Studio (File > Open) y pulsa Run ▶."))

    def open_out(self):
        p = self.value("out")
        if not p or not os.path.isdir(p):
            messagebox.showinfo("j2a", "La carpeta todavía no existe: genera el port primero.")
            return
        if sys.platform.startswith("win"):
            os.startfile(p)  # noqa: S606 - user-chosen local folder
        else:
            subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", p])

    def run(self, argv, done=None):
        if self.busy:
            return
        self.busy = True
        for b in self.buttons:
            b.state(["disabled"])
        self.write("\n$ j2a " + " ".join(argv) + "\n")

        def work():
            ok = False
            try:
                with contextlib.redirect_stdout(QueueWriter(self.q)), contextlib.redirect_stderr(QueueWriter(self.q)):
                    ok = cli.main(argv) == 0
            except SystemExit as e:  # j2a reports user errors with SystemExit("mensaje")
                if e.code not in (None, 0):
                    self.q.put("\n" + str(e.code) + "\n")
                ok = e.code in (None, 0)
            except Exception as e:  # noqa: BLE001 - show any failure in the window, never crash it
                self.q.put("\nError: %r\n" % e)
            self.q.put(("done", ok, done))

        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        try:
            while True:
                item = self.q.get_nowait()
                if isinstance(item, tuple):
                    _, ok, done = item
                    self.busy = False
                    for b in self.buttons:
                        b.state(["!disabled"])
                    self.write("\n" + ("✔ Terminado" if ok else "✘ Falló (mira el mensaje de arriba)") + "\n")
                    if done:
                        done(ok)
                else:
                    self.write(item)
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def write(self, s):
        self.log.configure(state="normal")
        self.log.insert("end", s)
        self.log.see("end")
        self.log.configure(state="disabled")


if __name__ == "__main__":
    App().mainloop()
