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

from j2a_tool import cli, generate  # noqa: E402
from j2a_tool.jarinfo import MidletSuite  # noqa: E402

try:  # optional: drop .jar files onto the window (pip install tkinterdnd2; j2a-gui.bat installs it)
    from tkinterdnd2 import DND_FILES, TkinterDnD
    BaseTk = TkinterDnD.Tk
except Exception:  # noqa: BLE001 - without it, multi-select in "Elegir…" and dropping on j2a-gui.bat still work
    DND_FILES = None
    BaseTk = tk.Tk

# (texto visible, valor del comando); None = no pasar la opción (j2a decide/detecta)
MODES = [("Código editable (recomendado)", "source"), ("Bytecode original (máxima fidelidad)", "jar")]
SCREENS = [("Detectar automáticamente", None)] + [
    ("%dx%d" % wh + (" (horizontal)" if wh[0] > wh[1] else ""), "%dx%d" % wh) for wh in (
        (96, 65), (101, 80), (128, 128), (128, 160), (132, 176), (176, 204), (176, 208), (176, 220),
        (208, 208), (240, 260), (240, 320), (320, 240), (240, 400), (400, 240), (240, 432), (352, 416),
        (320, 480), (480, 320), (360, 640), (640, 360), (480, 640), (640, 480), (480, 800), (800, 480))]
ORIENTATIONS = [("Detectar automáticamente", None), ("Vertical", "portrait"), ("Horizontal", "landscape"),
                ("Girar libremente", "auto")]
SHADERS = [("Píxeles nítidos", "nearest"), ("Nítido sin deformar (sharp)", "sharp"), ("Suavizado", "smooth"),
           ("Scanlines", "scanlines"), ("Rejilla LCD", "lcd"), ("CRT", "crt"), ("LCD verde", "mono")]
FPS_MODES = [("Sincronizado (vsync)", "vsync"), ("Dinámico (la pantalla se adapta)", "dynamic"),
             ("Sin límite", "unlimited")]
FPS_LIMITS = [("Sin límite", "0"), ("15", "15"), ("20", "20"), ("25", "25"), ("30", "30"), ("60", "60")]
SCALES = [("Proporción original", "fit"), ("16:9 (usa casi toda la pantalla, deforma poco)", "wide"),
          ("Pixel perfect", "integer"), ("Completa (estirar)", "stretch")]
ROTATIONS = [("Automático (detecta juegos que dibujan de lado)", None), ("No", "0"), ("90° (el juego dibuja de lado, girar a la derecha)", "90"),
             ("270° (el juego dibuja de lado, girar a la izquierda)", "270")]
KEYPADS = [("Automático (juegos táctiles: preguntar)", None), ("Completo (cruceta + números)", "full"),
           ("Cruceta + OK", "minimal"), ("Sin teclado (el juego trae controles táctiles)", "none")]


def project_dir(jar, out_root):
    """<carpeta>/<Nombre oficial>-android; sin carpeta elegida, junto al .jar."""
    try:  # official game name from the manifest, not the downloaded file name
        name = generate.display_file_name(MidletSuite(jar).name)
    except Exception:  # noqa: BLE001 - unreadable jar: the port itself will explain
        name = os.path.splitext(os.path.basename(jar))[0]
    return os.path.join(out_root or os.path.dirname(os.path.abspath(jar)), name + "-android")


def jar_paths(paths_):
    """Solo los .jar (los .jad que acompañan a un .jar se usan solos), sin repetir."""
    out = []
    for p in paths_:
        if p.lower().endswith(".jar") and os.path.isfile(p) and p not in out:
            out.append(p)
    return out


def build_args(v):
    """Lista de argumentos para 'j2a port' a partir de los valores de la ventana."""
    a = ["port", v["jar"], "-o", v["out"], "--mode", v["mode"], "--shader", v["shader"],
         "--fps-mode", v["fps_mode"], "--fps", v["fps"], "--scale", v.get("scale") or "fit"]
    for key, flag in (("rotate", "--rotate"), ("keypad", "--keypad"), ("screen", "--screen"), ("orientation", "--orientation"), ("name", "--name"),
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


def run_one(argv, q):
    """Runs one j2a command, sending its output to queue q. True if it worked."""
    try:
        with contextlib.redirect_stdout(QueueWriter(q)), contextlib.redirect_stderr(QueueWriter(q)):
            return cli.main(argv) == 0
    except SystemExit as e:  # j2a reports user errors with SystemExit("mensaje")
        if e.code not in (None, 0):
            q.put("\n" + str(e.code) + "\n")
        return e.code in (None, 0)
    except Exception as e:  # noqa: BLE001 - show any failure in the window, never crash it
        q.put("\nError: %r\n" % e)
        return False


class App(BaseTk):
    def __init__(self, initial=()):
        super().__init__()
        self.title("j2a — Ports de juegos J2ME a Android")
        self.minsize(640, 560)
        self.q = queue.Queue()
        self.busy = False
        self.vars = {}
        self.jars = []
        f = ttk.Frame(self, padding=12)
        f.pack(fill="both", expand=True)
        f.columnconfigure(1, weight=1)
        r = 0
        r = self.file_row(f, r, "Juegos (.jar)", "jar", self.pick_jar)
        r = self.file_row(f, r, "Guardar en (vacío = junto a cada .jar)", "out", self.pick_out)
        r = self.entry_row(f, r, "Nombre de la app (opcional, 1 juego)", "name")
        r = self.entry_row(f, r, "Paquete (opcional, 1 juego)", "package")
        r = self.combo_row(f, r, "Modo", "mode", MODES)
        r = self.combo_row(f, r, "Resolución", "screen", SCREENS)
        r = self.combo_row(f, r, "Rotación", "orientation", ORIENTATIONS)
        r = self.combo_row(f, r, "Pantalla", "scale", SCALES)
        r = self.combo_row(f, r, "Girar imagen", "rotate", ROTATIONS)
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
        self.write("1. Elige o arrastra uno o varios .jar  2. Pulsa «Generar port»  "
                   "3. Abre la carpeta en Android Studio y pulsa Run.\n")
        if DND_FILES:
            for w in [self] + self.descendants(self):
                w.drop_target_register(DND_FILES)
                w.dnd_bind("<<Drop>>", lambda e: self.set_jars(self.tk.splitlist(e.data)))
        else:
            self.write("(Para arrastrar .jar a esta ventana: python -m pip install tkinterdnd2. "
                       "También puedes soltarlos sobre j2a-gui.bat.)\n")
        if initial:
            self.set_jars(initial)
        self.after(100, self.poll)

    @classmethod
    def descendants(cls, w):
        out = []
        for c in w.winfo_children():
            out += [c] + cls.descendants(c)
        return out

    def set_jars(self, paths_):
        jars = jar_paths(paths_)
        if not jars:
            messagebox.showwarning("j2a", "Eso no es un archivo .jar.")
            return
        self.jars = jars
        self.vars["jar"].set(jars[0] if len(jars) == 1 else "%d juegos: " % len(jars) + ", ".join(
            os.path.basename(j) for j in jars))
        if len(jars) > 1:
            self.write("%d juegos listos: %s\n" % (len(jars), ", ".join(os.path.basename(j) for j in jars)))

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
        ps = filedialog.askopenfilenames(title="Elige uno o varios juegos (Ctrl o Mayús para varios)",
                                         filetypes=[("Juegos J2ME", "*.jar"), ("Todos", "*.*")])
        if ps:
            self.set_jars(ps)

    def pick_out(self):
        p = filedialog.askdirectory(title="Carpeta donde guardar el proyecto")
        if p:
            self.vars["out"].set(p)

    def pick_shaders(self):
        p = filedialog.askdirectory(title="Carpeta con shaders .ini/.fsh (formato PPSSPP)")
        if p:
            self.vars["shaders"].set(p)

    # --------------------------------------------------------------- actions
    def need_jars(self):
        typed = self.value("jar")  # a path typed or pasted by hand
        if os.path.isfile(typed) and typed not in self.jars:
            self.jars = jar_paths([typed])
        if not self.jars:
            messagebox.showwarning("j2a", "Primero elige o arrastra el .jar del juego (o varios).")
        return self.jars

    def analyze(self):
        self.run([["analyze", j] for j in self.need_jars()])

    def port(self):
        jars = self.need_jars()
        if not jars:
            return
        out_root = self.value("out")
        if out_root and len(jars) == 1 and out_root.endswith("-android"):
            out_root = os.path.dirname(out_root)  # a project folder from older versions of this window
        v = {k: self.value(k) for k in ("mode", "screen", "orientation", "shader", "fps_mode", "fps",
                                        "keypad", "shaders", "rotate", "scale")}
        if len(jars) == 1:  # name and package belong to one game
            v.update(name=self.value("name"), package=self.value("package"))
        v["force"] = self.vars["force"].get()
        self.last_out = [project_dir(j, out_root) for j in jars]
        jobs = [build_args(dict(v, jar=j, out=o)) for j, o in zip(jars, self.last_out)]
        self.run(jobs, done=lambda ok: ok and messagebox.showinfo(
            "j2a", ("Proyecto listo." if len(jobs) == 1 else "%d proyectos listos." % len(jobs))
            + "\n\nAbre la carpeta en Android Studio (File > Open) y pulsa Run ▶."))

    def open_out(self):
        outs = [o for o in getattr(self, "last_out", []) if os.path.isdir(o)]
        p = outs[0] if len(outs) == 1 else (self.value("out") or (os.path.dirname(outs[0]) if outs else ""))
        if not p or not os.path.isdir(p):
            messagebox.showinfo("j2a", "La carpeta todavía no existe: genera el port primero.")
            return
        if sys.platform.startswith("win"):
            os.startfile(p)  # noqa: S606 - user-chosen local folder
        else:
            subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", p])

    def run(self, jobs, done=None):
        """jobs: one argv list, or several (one per game) run one after another."""
        if self.busy or not jobs:
            return
        if isinstance(jobs[0], str):
            jobs = [jobs]
        self.busy = True
        for b in self.buttons:
            b.state(["disabled"])

        def work():
            failed = []
            for argv in jobs:
                self.q.put("\n$ j2a " + " ".join(argv) + "\n")
                if not run_one(argv, self.q):
                    failed.append(os.path.basename(argv[1]) if len(argv) > 1 else argv[0])
            if len(jobs) > 1:  # one bad game never stops the others
                self.q.put("\n%d de %d bien%s\n" % (len(jobs) - len(failed), len(jobs),
                                                      ("; fallaron: " + ", ".join(failed)) if failed else ""))
            self.q.put(("done", not failed, done))

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
    App(sys.argv[1:]).mainloop()  # .jar files dropped on j2a-gui.bat / this file arrive here
