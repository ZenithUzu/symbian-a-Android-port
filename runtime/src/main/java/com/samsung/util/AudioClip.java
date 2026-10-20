package com.samsung.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.VolumeControl;

import j2a.core.ResourceShim;

/** Samsung audio API, implemented on top of MMAPI. */
public class AudioClip {
    public static final int TYPE_MMF = 1;
    public static final int TYPE_MP3 = 2;
    public static final int TYPE_MIDI = 3;

    private Player player;

    public AudioClip(int type, String filename) throws IOException {
        InputStream in = ResourceShim.open(filename);
        if (in == null) throw new IOException(filename);
        try {
            player = Manager.createPlayer(in, null);
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
    }

    public AudioClip(int type, byte[] audioData, int offset, int length) {
        try {
            player = Manager.createPlayer(new ByteArrayInputStream(audioData, offset, length), null);
        } catch (Exception ignored) {}
    }

    public static boolean isSupported() { return true; }

    public void play(int loop, int volume) {
        if (player == null) return;
        try {
            player.stop();
            player.setLoopCount(loop <= 0 ? 1 : loop);
            VolumeControl vc = (VolumeControl) player.getControl("VolumeControl");
            if (vc != null) vc.setLevel(volume * 20);
            player.start();
        } catch (Exception ignored) {}
    }

    public void pause() { stop(); }

    public void resume() {
        try { if (player != null) player.start(); } catch (Exception ignored) {}
    }

    public void stop() {
        try { if (player != null) player.stop(); } catch (Exception ignored) {}
    }
}
