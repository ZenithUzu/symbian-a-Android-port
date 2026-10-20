package javax.microedition.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Vector;

import j2a.core.Host;
import j2a.core.ResourceShim;

public final class Manager {
    public static final String TONE_DEVICE_LOCATOR = "device://tone";
    public static final String MIDI_DEVICE_LOCATOR = "device://midi";

    private static final Vector<BasePlayer> players = new Vector<BasePlayer>();
    private static final TimeBase systemTimeBase = new TimeBase() {
        public long getTime() { return System.currentTimeMillis() * 1000L; }
    };

    private Manager() {}

    public static String[] getSupportedContentTypes(String protocol) {
        return new String[]{"audio/midi", "audio/mid", "audio/x-midi", "audio/sp-midi", "audio/x-wav", "audio/wav",
                "audio/mpeg", "audio/mp3", "audio/amr", "audio/mp4", "audio/aac", "audio/ogg", "audio/x-tone-seq"};
    }

    public static String[] getSupportedProtocols(String contentType) {
        return new String[]{"device", "http", "https", "resource"};
    }

    public static Player createPlayer(String locator) throws IOException, MediaException {
        if (locator == null) throw new IllegalArgumentException();
        if (locator.startsWith(TONE_DEVICE_LOCATOR) || locator.startsWith(MIDI_DEVICE_LOCATOR)) return new TonePlayer();
        if (locator.startsWith("http://") || locator.startsWith("https://")) return new AudioPlayer(locator, null);
        String res = null;
        if (locator.startsWith("resource:")) res = locator.substring("resource:".length());
        else if (locator.startsWith("file://")) res = locator.substring("file://".length());
        else if (locator.startsWith("/")) res = locator;
        if (res != null) {
            InputStream in = ResourceShim.open(res);
            if (in == null) throw new MediaException("Not found: " + locator);
            return createPlayer(in, null);
        }
        throw new MediaException("Unsupported locator: " + locator);
    }

    public static Player createPlayer(InputStream stream, String type) throws IOException, MediaException {
        if (stream == null) throw new IllegalArgumentException();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        byte[] data = out.toByteArray();
        if (type != null && type.equals("audio/x-tone-seq")) {
            TonePlayer p = new TonePlayer();
            p.setSequence(data);
            return p;
        }
        return new AudioPlayer(data, type);
    }

    public static void playTone(int note, int duration, int volume) throws MediaException {
        if (note < 0 || note > 127 || duration <= 0) throw new IllegalArgumentException();
        if (!Host.cfg.sound || Host.isPaused()) return;
        ToneSynth.playTone(note, duration, volume, 1f);
    }

    public static TimeBase getSystemTimeBase() { return systemTimeBase; }

    static void register(BasePlayer p) { players.addElement(p); }

    static void unregister(BasePlayer p) { players.removeElement(p); }

    // ----------------------------------------------- j2a host hooks (not MMAPI)

    public static void j2aPauseAll() {
        Object[] ps = players.toArray();
        for (int i = 0; i < ps.length; i++) ((BasePlayer) ps[i]).j2aPause();
    }

    public static void j2aResumeAll() {
        if (!Host.cfg.sound) return;
        Object[] ps = players.toArray();
        for (int i = 0; i < ps.length; i++) ((BasePlayer) ps[i]).j2aResume();
    }

    public static void j2aCloseAll() {
        Object[] ps = players.toArray();
        for (int i = 0; i < ps.length; i++) {
            try { ((BasePlayer) ps[i]).close(); } catch (Throwable ignored) {}
        }
    }
}
