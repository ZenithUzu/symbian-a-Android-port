package demo;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * Small J2ME game used to test the j2a toolchain end to end. It touches the
 * APIs real Symbian/S40 games use: List/Form/TextField/Alert, GameCanvas,
 * Sprite, TiledLayer, LayerManager, RMS, MMAPI tones and resources loaded with
 * the classic "".getClass().getResourceAsStream(...) idiom.
 */
public class DemoMIDlet extends MIDlet implements CommandListener {
    private Display display;
    private List menu;
    private Form scores;
    private TextField nameField;
    private GameScreen game;
    private final Command back = new Command("Atrás", Command.BACK, 1);
    private final Command save = new Command("Guardar", Command.OK, 1);
    private final Command exit = new Command("Salir", Command.EXIT, 2);

    protected void startApp() {
        if (display != null) return; // resumed
        display = Display.getDisplay(this);
        menu = new List("Demo j2a", List.IMPLICIT, new String[]{"Jugar", "Récords", "Nombre", "Salir"}, null);
        menu.addCommand(exit);
        menu.setCommandListener(this);
        display.setCurrent(menu);
    }

    protected void pauseApp() {
        if (game != null) game.pause();
    }

    protected void destroyApp(boolean unconditional) {
        if (game != null) game.stop();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exit) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == back) {
            display.setCurrent(menu);
        } else if (c == save) {
            a.saveName(nameField.getString());
            display.setCurrent(new Alert("Guardado", "Hola, " + nameField.getString(), null, AlertType.INFO), menu);
        } else if (d == menu) {
            switch (menu.getSelectedIndex()) {
                case 0:
                    game = new GameScreen(this);
                    display.setCurrent(game);
                    game.start();
                    break;
                case 1:
                    scores = new Form("Récords");
                    scores.append(new StringItem("Mejor puntuación", String.valueOf(a.loadBest())));
                    scores.append(new StringItem("Jugador", a.loadName()));
                    scores.addCommand(back);
                    scores.setCommandListener(this);
                    display.setCurrent(scores);
                    break;
                case 2:
                    Form f = new Form("Tu nombre");
                    nameField = new TextField("Nombre", a.loadName(), 16, TextField.ANY);
                    f.append(nameField);
                    f.addCommand(save);
                    f.addCommand(back);
                    f.setCommandListener(this);
                    display.setCurrent(f);
                    break;
                default:
                    notifyDestroyed();
            }
        }
    }

    void gameOver(int score) {
        if (score > a.loadBest()) a.saveBest(score);
        display.setCurrent(new Alert("Fin", "Puntos: " + score, null, AlertType.INFO), menu);
    }
}
