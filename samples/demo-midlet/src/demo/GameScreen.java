package demo;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.LayerManager;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.lcdui.game.TiledLayer;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;

class GameScreen extends GameCanvas implements Runnable, CommandListener {
    private final DemoMIDlet midlet;
    private final Command quit = new Command("Salir", Command.BACK, 1);
    private volatile boolean running;
    private volatile boolean paused;
    private final LayerManager layers = new LayerManager();
    private Sprite hero;
    private Sprite coin;
    private TiledLayer ground;
    private int score;
    private int ticks;
    private Player music;

    GameScreen(DemoMIDlet midlet) {
        super(true);
        this.midlet = midlet;
        setFullScreenMode(true);
        addCommand(quit);
        setCommandListener(this);
        try {
            Image tiles = load("/tiles.png");
            Image heroImg = load("/hero.png");
            ground = new TiledLayer(getWidth() / 16 + 1, getHeight() / 16 + 1, tiles, 16, 16);
            for (int r = 0; r < ground.getRows(); r++)
                for (int c = 0; c < ground.getColumns(); c++)
                    ground.setCell(c, r, ((r + c) % 2) + 1);
            hero = new Sprite(heroImg, 16, 16);
            hero.defineReferencePixel(8, 8);
            hero.setRefPixelPosition(getWidth() / 2, getHeight() / 2);
            coin = new Sprite(heroImg, 16, 16);
            coin.setFrame(1);
            coin.setTransform(Sprite.TRANS_ROT90);
            coin.setPosition(20, 40);
            layers.append(hero);
            layers.append(coin);
            layers.append(ground);
        } catch (IOException e) {
            throw new RuntimeException(e.toString());
        }
        try {
            InputStream in = getClass().getResourceAsStream("/beep.wav");
            if (in != null) music = Manager.createPlayer(in, "audio/x-wav");
        } catch (IOException e) {
            music = null;
        } catch (MediaException e) {
            music = null;
        }
    }

    /** The classic J2ME idiom that breaks on Android without j2a's bytecode patch. */
    private static Image load(String name) throws IOException {
        InputStream in = "".getClass().getResourceAsStream(name);
        if (in == null) throw new IOException("missing " + name);
        return Image.createImage(in);
    }

    void start() {
        running = true;
        new Thread(this).start();
    }

    void stop() { running = false; }

    void pause() { paused = true; }

    protected void showNotify() { paused = false; }

    public void commandAction(Command c, Displayable d) {
        running = false;
        midlet.gameOver(score);
    }

    public void run() {
        Graphics g = getGraphics();
        while (running) {
            if (!paused) {
                int keys = getKeyStates();
                int dx = 0, dy = 0;
                if ((keys & LEFT_PRESSED) != 0) { dx = -2; hero.setTransform(Sprite.TRANS_MIRROR); }
                if ((keys & RIGHT_PRESSED) != 0) { dx = 2; hero.setTransform(Sprite.TRANS_NONE); }
                if ((keys & UP_PRESSED) != 0) dy = -2;
                if ((keys & DOWN_PRESSED) != 0) dy = 2;
                hero.move(dx, dy);
                if (++ticks % 8 == 0) hero.nextFrame();
                if (hero.collidesWith(coin, true)) {
                    score += 10;
                    coin.setPosition(a.rnd(getWidth() - 16), a.rnd(getHeight() - 16));
                    try {
                        if (music != null) music.start();
                        else Manager.playTone(72, 80, 80);
                    } catch (MediaException ignored) {
                    }
                }
                render(g);
                flushGraphics();
            }
            try { Thread.sleep(30); } catch (InterruptedException e) { return; }
        }
    }

    private void render(Graphics g) {
        layers.paint(g, 0, 0);
        g.setColor(0x000000);
        g.fillRect(0, 0, getWidth(), 18);
        g.setColor(0xFFFF00);
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL));
        g.drawString("Puntos: " + score, 2, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString("Salir", getWidth() - 2, getHeight() - 2, Graphics.BOTTOM | Graphics.RIGHT);
    }
}
