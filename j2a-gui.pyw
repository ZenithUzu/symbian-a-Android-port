#!/usr/bin/env python3
"""j2a con interfaz gráfica. En Windows basta con doble clic en este archivo (o en j2a-gui.bat).

Es una ventana encima de los mismos comandos de tool/j2a.py: lo que genera es idéntico.
"""
import contextlib
import json
import re
import os
import queue
import subprocess
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "tool"))

import tkinter as tk  # noqa: E402
from tkinter import filedialog, messagebox, ttk  # noqa: E402

from j2a_tool import adb, cli, generate, paths  # noqa: E402
from j2a_tool.jarinfo import MidletSuite  # noqa: E402

try:  # optional: drop .jar files onto the window (pip install tkinterdnd2; j2a-gui.bat installs it)
    from tkinterdnd2 import DND_FILES, TkinterDnD
    BaseTk = TkinterDnD.Tk
except Exception:  # noqa: BLE001 - without it, multi-select in "Elegir…" and dropping on j2a-gui.bat still work
    DND_FILES = None
    BaseTk = tk.Tk

# (texto visible, valor del comando); None = no pasar la opción (j2a decide/detecta)
MODES = [("Código editable (recomendado)", "source"), ("Bytecode original (máxima fidelidad)", "jar")]
# Colores: la lista de juegos imita la pantalla LCD de un teléfono Nokia; el resto queda sobrio.
PAPER, INK, MUTED = "#F3F5FA", "#18233B", "#586780"
LCD, LCD_INK = "#FFFFFF", "#263957"
ACCENT, ACCENT_DOWN, BAD = "#365BD6", "#2847AA", "#B42338"

PREFS = os.path.join(paths.cache_dir(), "gui.json")


def load_prefs():
    try:
        with open(PREFS, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_prefs(prefs):
    try:
        os.makedirs(os.path.dirname(PREFS), exist_ok=True)
        with open(PREFS, "w", encoding="utf-8") as f:
            json.dump(prefs, f)
    except OSError:
        pass  # only a convenience: the window works without it


def project_dir(jar, out_root, name=None):
    """Projects go to proyectos/<Nombre oficial>-android by default."""
    if not name:
        try:  # official game name from the manifest, not the downloaded file name
            name = MidletSuite(jar).name
        except Exception:  # noqa: BLE001 - unreadable jar: the port itself will explain
            name = os.path.splitext(os.path.basename(jar))[0]
    return os.path.join(out_root or paths.projects_dir(),
                        generate.display_file_name(name) + "-android")


def jar_paths(paths_):
    """Solo los .jar (los .jad que acompañan a un .jar se usan solos), sin repetir."""
    out = []
    for p in paths_:
        p = os.path.abspath(p)
        if p.lower().endswith(".jar") and os.path.isfile(p) and p not in out:
            out.append(p)
    return out


def game_info(jar):
    """Nombre y paquete que j2a usará solo, para mostrarlos en la lista."""
    try:
        suite = MidletSuite(jar)
        midlets = suite.midlets()
        result = suite.name, generate.official_package(suite, midlets[0]["class"] if midlets else None), suite.screen_detection()
        suite.zip.close()
        return result
    except Exception:  # noqa: BLE001 - unreadable jar: the port itself will explain
        return os.path.splitext(os.path.basename(jar))[0], "?", None


def build_args(v):
    """Lista de argumentos para 'j2a port' a partir de los valores de la ventana."""
    a = ["port", v["jar"], "-o", v["out"], "--mode", v["mode"]]
    for key, flag in (("name", "--name"), ("package", "--package"), ("shaders", "--shaders"),
                      ("apk_output", "--apk-output")):
        if v.get(key):
            a += [flag, v[key]]
    if v.get("force"):
        a.append("--force")
    if v.get("apk", True):
        a.append("--apk")
    if v.get("install"):
        a += ["--install", "--device", v["device"]]
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


class Game:
    def __init__(self, jar):
        self.jar = jar
        self.auto_name, self.auto_package, self.detection = game_info(jar)
        d = self.detection
        self.screen_label = '%dx%d · %s' % (d['width'], d['height'], d['confidence']) if d else 'No detectada'
        self.name = self.package = self.folder = ""  # "" = automático
        self.status = "Pendiente"

    def out(self, root):
        return self.folder or project_dir(self.jar, root, self.name or self.auto_name)


class App(BaseTk):
    def __init__(self, initial=()):
        super().__init__()
        self.title("j2a Studio — de Java ME a APK")
        self.minsize(900, 600)
        self.geometry("1020x720")
        self.configure(bg=PAPER)
        self.q = queue.Queue()
        self.busy = False
        self.games = {}  # jar path -> Game, in list order
        self.prefs = load_prefs()
        self.vars = {}
        self.style_window()

        f = ttk.Frame(self, padding=(18, 14, 18, 14))
        f.pack(fill="both", expand=True)
        f.columnconfigure(0, weight=1)

        ttk.Label(f, text="j2a Studio  /  Tu biblioteca, en Android", style="H.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(f, text="01 Añade juegos    →    02 Elige la entrega    →    03 Crea el proyecto o la APK",
                  style="Muted.TLabel").grid(row=1, column=0, sticky="w", pady=(0, 6))
        self.list_frame = tk.Frame(f, bg=LCD, highlightthickness=2, highlightbackground=LCD_INK)
        self.list_frame.grid(row=2, column=0, sticky="nsew")
        f.rowconfigure(2, weight=1, minsize=150)
        self.tree = ttk.Treeview(self.list_frame, columns=("name", "pkg", "screen", "out", "status"), show="headings",
                                 style="LCD.Treeview", selectmode="extended", height=6)
        for col, text, w in (("name", "Juego", 170), ("pkg", "Paquete", 150), ("screen", "Resolución · confianza", 160), ("out", "Se guarda en", 200),
                             ("status", "Estado", 110)):
            self.tree.heading(col, text=text, anchor="w")
            self.tree.column(col, width=w, minwidth=60, anchor="w", stretch=col in ("name", "out"))
        self.tree.pack(fill="both", expand=True, padx=2, pady=2)
        self.tree.tag_configure("bad", foreground=BAD)
        self.tree.bind("<Double-1>", lambda e: self.edit_game())
        self.tree.bind("<Return>", lambda e: self.edit_game())
        self.tree.bind("<Delete>", lambda e: self.remove_games())
        self.empty = tk.Label(self.list_frame, text="Suelta aquí tus juegos .jar\no pulsa «Añadir juegos»",
                              bg=LCD, fg=LCD_INK, font=self.fonts["lcd_big"], cursor="hand2")
        self.empty.bind("<Button-1>", lambda e: self.add_games())

        row = ttk.Frame(f)
        row.grid(row=3, column=0, sticky="ew", pady=(8, 14))
        ttk.Button(row, text="Añadir juegos…", command=self.add_games).pack(side="left")
        ttk.Button(row, text="Quitar", command=self.remove_games).pack(side="left", padx=(6, 0))
        ttk.Button(row, text="Abrir carpeta", command=self.open_out).pack(side="right")

        # Shared output base with separate proyectos/ and apk/ folders
        save = ttk.Frame(f)
        save.grid(row=4, column=0, sticky="ew")
        save.columnconfigure(2, weight=1)
        ttk.Label(save, text="Guardar", style="H2.TLabel").grid(row=0, column=0, sticky="w", padx=(0, 12))
        self.vars["out"] = tk.StringVar(value=self.prefs.get("workspace_root", paths.REPO))
        ttk.Label(save, textvariable=self.vars["out"], style="Muted.TLabel").grid(row=0, column=1, columnspan=2, sticky="w")
        ttk.Button(save, text="Cambiar carpeta base…", command=self.pick_out).grid(row=0, column=3, sticky="e")
        ttk.Label(save, text="Proyectos → proyectos/     APKs → apk/", style="Muted.TLabel").grid(row=1, column=1, columnspan=3, sticky="w")

        # options: all automatic unless opened
        self.opts_open = tk.BooleanVar(value=True)
        self.opts_btn = ttk.Button(f, style="Link.TButton", command=self.toggle_options)
        self.opts_btn.grid(row=5, column=0, sticky="w", pady=(12, 0))
        self.opts = ttk.Frame(f, padding=(14, 6, 0, 0))
        self.opts.columnconfigure(1, weight=1)
        self.opts.columnconfigure(3, weight=1)
        self.combo(self.opts, 0, 0, "Código generado", "mode", MODES)
        r = 1
        self.vars["shaders"] = tk.StringVar()
        ttk.Label(self.opts, text="Filtros extra").grid(row=r, column=0, sticky="w", pady=3)
        ttk.Button(self.opts, text="Elegir carpeta…", command=self.pick_shaders).grid(row=r, column=1, sticky="w")
        ttk.Label(self.opts, textvariable=self.vars["shaders"], style="Muted.TLabel").grid(
            row=r, column=2, columnspan=2, sticky="w")
        ttk.Button(self.opts, text="Comprobar Java y Android SDK", command=lambda: self.run([["doctor"]])).grid(
            row=r + 1, column=0, columnspan=2, sticky="w", pady=(8, 0))
        self.combo(self.opts, r + 2, 0, "Entrega", "delivery", [
            ("APK instalable", "apk"), ("Proyecto de Android Studio", "project"), ("APK + instalar por ADB", "install")])
        self.vars["device"] = tk.StringVar()
        self.device_combo = ttk.Combobox(self.opts, textvariable=self.vars["device"], state="readonly", width=28)
        self.device_combo.grid(row=r + 2, column=2, sticky="ew")
        ttk.Button(self.opts, text="Buscar ADB", command=self.scan_adb).grid(row=r + 2, column=3, padx=6)
        self.adb_status = tk.StringVar(value="ADB: conecta Android y autoriza la depuración USB.")
        ttk.Label(self.opts, textvariable=self.adb_status, style="Muted.TLabel").grid(row=r + 3, column=0, columnspan=4, sticky="w")
        ttk.Label(self.opts, text="Resolución, widescreen, filtros, FPS y controles se ajustan dentro del juego.\n"
                  "La APK usa firma de depuración. La primera compilación necesita internet y el SDK de Android.",
                  style="Muted.TLabel").grid(row=r + 4, column=0, columnspan=4, sticky="w", pady=8)
        self.opts.grid(row=6, column=0, sticky="ew")
        self.update_options_label()

        bottom = ttk.Frame(f)
        bottom.grid(row=7, column=0, sticky="ew", pady=(16, 0))
        bottom.columnconfigure(1, weight=1)
        self.go = ttk.Button(bottom, text="Crear ports", style="Primary.TButton", command=self.port)
        self.go.grid(row=0, column=0, sticky="w")
        self.progress = ttk.Progressbar(bottom, mode="indeterminate", length=100)
        self.progress.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        self.status = tk.StringVar()
        ttk.Label(bottom, textvariable=self.status, style="Muted.TLabel").grid(row=0, column=1, sticky="w", padx=12)
        self.log_btn = ttk.Button(bottom, text="Ver detalles", style="Link.TButton", command=self.toggle_log)
        self.log_btn.grid(row=0, column=2, sticky="e")
        self.log = tk.Text(f, height=10, wrap="word", state="disabled", bg="#F7F8F5", fg=INK, relief="flat",
                           font=self.fonts["mono"], highlightthickness=1, highlightbackground="#C9CEC7")

        if DND_FILES:
            for w in [self] + self.descendants(self):
                w.drop_target_register(DND_FILES)
                w.dnd_bind("<<Drop>>", lambda e: self.add_paths(self.tk.splitlist(e.data)))
        else:
            self.write("Para arrastrar .jar a esta ventana: python -m pip install tkinterdnd2 "
                       "(j2a-gui.bat lo instala solo). También puedes soltarlos sobre j2a-gui.bat.\n")
        self.refresh()
        if initial:
            self.add_paths(initial)
        self.protocol("WM_DELETE_WINDOW", self.close_window)
        self.after(100, self.poll)

    def close_window(self):
        if self.busy:
            messagebox.showinfo("Trabajo en curso", "Espera a que termine la tarea antes de cerrar la ventana.")
        else:
            self.destroy()

    def scan_adb(self):
        if self.busy:
            return
        self.adb_status.set("Buscando dispositivos ADB…")
        def scan():
            try:
                self.q.put(("adb", adb.devices(), None))
            except Exception as error:
                self.q.put(("adb", [], str(error)))
        threading.Thread(target=scan, daemon=True).start()

    # ----------------------------------------------------------------- look
    def style_window(self):
        from tkinter import font as tkfont
        base = tkfont.nametofont("TkDefaultFont").actual()["family"]
        if "Segoe UI" in tkfont.families():
            base = "Segoe UI"
        mono = "Consolas" if "Consolas" in tkfont.families() else "TkFixedFont"
        self.fonts = {"body": (base, 10), "h": (base, 15, "bold"), "h2": (base, 10, "bold"),
                      "lcd": (base, 10, "bold"), "lcd_big": (base, 13, "bold"), "mono": (mono, 9)}
        st = ttk.Style(self)
        st.theme_use("clam")  # the only built-in theme that takes these colors on every system
        st.configure(".", background=PAPER, foreground=INK, font=self.fonts["body"])
        st.configure("TFrame", background=PAPER)
        st.configure("H.TLabel", font=self.fonts["h"])
        st.configure("H2.TLabel", font=self.fonts["h2"])
        st.configure("Muted.TLabel", foreground=MUTED)
        st.configure("TButton", padding=(10, 4), background="#DDE1DA", bordercolor="#B9BFB6")
        st.map("TButton", background=[("active", "#D0D5CD")])
        st.configure("Primary.TButton", padding=(22, 8), background=ACCENT, foreground="white",
                     bordercolor=ACCENT, font=self.fonts["h2"])
        st.map("Primary.TButton", background=[("disabled", "#9AA8B2"), ("pressed", ACCENT_DOWN), ("active", ACCENT_DOWN)])
        st.configure("Link.TButton", padding=(0, 2), background=PAPER, bordercolor=PAPER, foreground=ACCENT,
                     relief="flat")
        st.map("Link.TButton", background=[("active", PAPER)], foreground=[("active", ACCENT_DOWN)])
        st.configure("TRadiobutton", background=PAPER)
        st.configure("TCheckbutton", background=PAPER)
        st.configure("LCD.Treeview", background=LCD, fieldbackground=LCD, foreground=LCD_INK,
                     font=self.fonts["body"], rowheight=36, borderwidth=0)
        st.configure("LCD.Treeview.Heading", background=LCD, foreground=LCD_INK, font=self.fonts["body"],
                     relief="flat", borderwidth=0)
        st.map("LCD.Treeview.Heading", background=[("active", LCD)])
        # selected row = inverted pixels, like the menus of those phones
        st.map("LCD.Treeview", background=[("selected", LCD_INK)], foreground=[("selected", LCD)])

    @classmethod
    def descendants(cls, w):
        out = []
        for c in w.winfo_children():
            out += [c] + cls.descendants(c)
        return out

    def combo(self, parent, r, c, label, key, options):
        ttk.Label(parent, text=label).grid(row=r, column=c, sticky="w", pady=3, padx=(0 if c == 0 else 14, 8))
        var = tk.StringVar(value=options[0][0])
        self.vars[key] = var
        self.vars[key + "#opts"] = dict(options)
        cb = ttk.Combobox(parent, textvariable=var, values=[o[0] for o in options], state="readonly", width=30)
        cb.grid(row=r, column=c + 1, sticky="ew", pady=3)
        cb.bind("<<ComboboxSelected>>", lambda e: self.update_options_label())
        self.vars[key + "#default"] = options[0][0]
        self.vars[key + "#label"] = label

    def value(self, key):
        v = self.vars[key].get()
        opts = self.vars.get(key + "#opts")
        return opts[v] if opts else v.strip()

    # ---------------------------------------------------------------- list
    def out_root(self):
        return os.path.join(self.vars["out"].get(), "proyectos")

    def apk_root(self):
        return os.path.join(self.vars["out"].get(), "apk")

    def refresh(self):
        """Redraws the game list from self.games."""
        self.tree.delete(*self.tree.get_children())
        root = self.out_root()
        for jar, g in self.games.items():
            out = g.out(root)  # the full path is in the edit window; here the part that tells them apart
            short = os.path.join("…", os.path.basename(os.path.dirname(out)), os.path.basename(out))
            self.tree.insert("", "end", iid=jar, values=(g.name or g.auto_name, g.package or g.auto_package,
                                                         g.screen_label, short, g.status),
                             tags=("bad",) if g.status == "Falló" else ())
        if self.games:
            self.empty.place_forget()
        else:
            self.empty.place(relx=0.5, rely=0.5, anchor="center")
        n = len(self.games)
        action = {"apk": "Generar APK", "project": "Crear proyecto Android", "install": "Crear e instalar APK"}[self.value("delivery")]
        self.go.configure(text=action + (" · %d juegos" % n if n > 1 else ""))

    def add_paths(self, paths_):
        if self.busy:
            return
        jars = jar_paths(paths_)
        if not jars:
            messagebox.showwarning("j2a", "Solo se pueden añadir archivos .jar.")
            return
        for j in jars:
            if j not in self.games:
                self.games[j] = Game(j)
        self.refresh()

    def add_games(self):
        ps = filedialog.askopenfilenames(title="Añadir juegos (Ctrl o Mayús para elegir varios)",
                                         filetypes=[("Juegos Java", "*.jar"), ("Todos", "*.*")])
        if ps:
            self.add_paths(ps)

    def remove_games(self):
        if self.busy:
            return
        for jar in self.tree.selection():
            self.games.pop(jar, None)
        self.refresh()

    def edit_game(self):
        sel = self.tree.selection()
        if not sel or self.busy:
            return
        g = self.games[sel[0]]
        d = tk.Toplevel(self, bg=PAPER, padx=18, pady=14)
        d.title(g.auto_name)
        d.transient(self)
        d.columnconfigure(1, weight=1)
        ttk.Label(d, text=os.path.basename(g.jar), style="H2.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(d, text="Deja un campo vacío para usar el valor automático.", style="Muted.TLabel").grid(
            row=1, column=0, columnspan=3, sticky="w", pady=(0, 8))
        fields = {}
        for i, (label, key, auto) in enumerate((("Nombre de la app", "name", g.auto_name),
                                                ("Paquete", "package", g.auto_package),
                                                ("Carpeta del proyecto", "folder", g.out(self.out_root())))):
            r = 2 + 2 * i
            ttk.Label(d, text=label).grid(row=r, column=0, sticky="w", padx=(0, 10), pady=3)
            fields[key] = tk.StringVar(value=getattr(g, key))
            ttk.Entry(d, textvariable=fields[key], width=46).grid(row=r, column=1, sticky="ew", pady=(3, 0))
            ttk.Label(d, text="Automático: " + auto, style="Muted.TLabel").grid(
                row=r + 1, column=1, sticky="w", pady=(0, 6))
            if key == "folder":
                ttk.Button(d, text="Elegir…", command=lambda v=fields[key]: v.set(
                    filedialog.askdirectory(parent=d, title="Carpeta del proyecto") or v.get())).grid(
                    row=r, column=2, padx=(6, 0))

        def save():
            pkg = fields["package"].get().strip()
            if pkg and not re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$", pkg):
                messagebox.showwarning("j2a", "El paquete debe ser como com.estudio.juego (minúsculas, con puntos).",
                                       parent=d)
                return
            g.name, g.package, g.folder = fields["name"].get().strip(), pkg, fields["folder"].get().strip()
            d.destroy()
            self.refresh()

        bar = ttk.Frame(d)
        bar.grid(row=8, column=0, columnspan=3, sticky="e", pady=(8, 0))
        ttk.Button(bar, text="Cancelar", command=d.destroy).pack(side="right", padx=(6, 0))
        ttk.Button(bar, text="Guardar", style="Primary.TButton", command=save).pack(side="right")
        d.bind("<Return>", lambda e: save())
        d.bind("<Escape>", lambda e: d.destroy())
        d.grab_set()

    # ------------------------------------------------------------- options
    def toggle_options(self):
        self.opts_open.set(not self.opts_open.get())
        if self.opts_open.get():
            self.opts.grid(row=6, column=0, sticky="ew")
        else:
            self.opts.grid_forget()
        self.update_options_label()

    def update_options_label(self):
        self.opts_btn.configure(text=("▾ " if self.opts_open.get() else "▸ ") + "Exportación y dispositivos")
        if hasattr(self, "go"):
            self.refresh()

    def toggle_log(self, show=None):
        show = not self.log.winfo_ismapped() if show is None else show
        if show:
            self.log.grid(row=8, column=0, sticky="nsew", pady=(10, 0))
        else:
            self.log.grid_forget()
        self.log_btn.configure(text="Ocultar detalles" if show else "Ver detalles")

    # --------------------------------------------------------------- pickers
    def pick_out(self):
        p = filedialog.askdirectory(title="Carpeta base para proyectos/ y apk/")
        if p:
            self.vars["out"].set(p)
            self.prefs["workspace_root"] = p
            save_prefs(self.prefs)
            self.refresh()

    def pick_shaders(self):
        p = filedialog.askdirectory(title="Carpeta con filtros RetroArch (.glsl) o PPSSPP (.ini/.fsh)")
        if p:
            self.vars["shaders"].set(p)

    # --------------------------------------------------------------- actions
    def port(self):
        if self.busy:
            return
        if not self.games:
            self.add_games()
            return
        root = self.out_root()
        v = {"mode": self.value("mode"), "shaders": self.value("shaders"), "apk_output": self.apk_root()}
        v["apk"] = self.value("delivery") != "project"
        v["install"] = self.value("delivery") == "install"
        v["device"] = self.vars["device"].get()
        if v["install"] and not v["device"]:
            messagebox.showwarning("Selecciona Android", "Pulsa Buscar ADB y selecciona un dispositivo autorizado.")
            return
        folders = [os.path.normcase(os.path.abspath(g.out(root))) for g in self.games.values()]
        if len(set(folders)) != len(folders):
            messagebox.showwarning("Carpetas repetidas", "Dos juegos usan la misma carpeta. Cambia la carpeta de uno con doble clic.")
            return
        existing = [g for g in self.games.values() if os.path.isdir(g.out(root)) and os.listdir(g.out(root))]
        replace = False
        if existing:
            replace = messagebox.askyesnocancel("j2a", "%s ya tiene%s proyecto en su carpeta.\n\n"
                                                "Sí: reemplazarlo (se pierden los cambios que le hiciste)\n"
                                                "No: conservarlo y compilar su APK con sus ajustes actuales" % (
                                                    ", ".join(g.name or g.auto_name for g in existing),
                                                    "n" if len(existing) > 1 else ""))
            if replace is None:
                return
        jobs = []
        for g in self.games.values():
            if g in existing and not replace:
                g.status = "En cola" if v["apk"] else "Ya existe"
                if v["apk"]:
                    args = ["build", g.out(root), "-o", self.apk_root()]
                    if v["install"]:
                        args += ["--install", "--device", v["device"]]
                    jobs.append((g.jar, args))
                continue
            g.status = "En cola"
            jobs.append((g.jar, build_args(dict(v, jar=g.jar, out=g.out(root), name=g.name, package=g.package,
                                                force=g in existing))))
        self.refresh()
        if not jobs:
            self.status.set("Nada que crear: todos ya tienen proyecto.")
        self.run(jobs)

    def open_out(self):
        sel = self.tree.selection() or tuple(self.games)
        outs = [self.games[j].out(self.out_root()) for j in sel if j in self.games]
        p = self.apk_root() if self.value("delivery") != "project" and os.path.isdir(self.apk_root()) else None
        p = p or next((o for o in outs if os.path.isdir(o)), None) or self.out_root()
        if not p or not os.path.isdir(p):
            messagebox.showinfo("j2a", "Todavía no hay carpeta: crea el port primero.")
            return
        if sys.platform.startswith("win"):
            os.startfile(p)  # noqa: S606 - user-chosen local folder
        else:
            subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", p])

    def run(self, jobs):
        """jobs: [(jar or None, argv)], run one after another; one bad game never stops the others."""
        if self.busy or not jobs:
            return
        jobs = [j if isinstance(j, tuple) else (None, j) for j in jobs]
        self.busy = True
        self.go.state(["disabled"])
        self.progress.start(12)
        self.locked_controls = []
        for widget in self.descendants(self):
            if isinstance(widget, (ttk.Button, ttk.Combobox, ttk.Entry, ttk.Checkbutton, ttk.Radiobutton)):
                if widget is self.log_btn or (isinstance(widget, ttk.Button) and widget.cget("text") == "Abrir carpeta"):
                    continue
                if not widget.instate(["disabled"]):
                    self.locked_controls.append(widget)
                    widget.state(["disabled"])

        def work():
            failed = 0
            for i, (jar, argv) in enumerate(jobs, 1):
                if jar:
                    self.q.put(("status", jar, "Compilando…" if "--apk" in argv or argv[0] == "build" else "Generando…"))
                    self.q.put(("line", "Creando %d de %d: %s" % (i, len(jobs), self.games[jar].name
                                                                   or self.games[jar].auto_name)))
                self.q.put("\n$ j2a " + " ".join(argv) + "\n")
                ok = run_one(argv, self.q)
                failed += not ok
                if jar:
                    self.q.put(("status", jar, ("Instalado" if "--install" in argv else "APK lista" if "--apk" in argv or argv[0] == "build" else "Proyecto listo") if ok else "Falló"))
            n = len(jobs)
            self.q.put(("done", failed, ("Listo: %d trabajo(s). Consulta el estado de cada juego." % n
                                         if not failed else "%d de %d fallaron: mira los detalles." % (failed, n))
                        if jobs[0][0] else ""))

        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        try:
            while True:
                item = self.q.get_nowait()
                if not isinstance(item, tuple):
                    self.write(item)
                elif item[0] == "adb":
                    _, devices, error = item
                    ready = [d["serial"] for d in devices if d["state"] == "device"]
                    self.device_combo.configure(values=ready)
                    if self.vars["device"].get() not in ready:
                        self.vars["device"].set(ready[0] if len(ready) == 1 else "")
                    self.adb_status.set(error or "; ".join(d["serial"] + ": " + d["state"] for d in devices) or "Sin dispositivos. Activa depuración USB y acepta la autorización.")
                elif item[0] == "status":
                    g = self.games.get(item[1])
                    if g:
                        g.status = item[2]
                        self.refresh()
                elif item[0] == "line":
                    self.status.set(item[1])
                else:  # done
                    _, failed, text = item
                    self.busy = False
                    self.progress.stop()
                    for widget in self.locked_controls:
                        widget.state(["!disabled"])
                    self.go.state(["!disabled"])
                    self.status.set(text)
                    if failed or not text:
                        self.toggle_log(True)
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
