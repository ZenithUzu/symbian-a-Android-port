package com.nokia.mid.sound;

import java.io.ByteArrayInputStream;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;

/** Nokia UI API sound, implemented on top of MMAPI players. */
public class Sound {
    public static final int FORMAT_TONE = 1;
    public static final int FORMAT_WAV = 5;
    public static final int SOUND_PLAYING = 0;
    public static final int SOUND_STOPPED = 1;
    public static final int SOUND_UNINITIALIZED = 3;

    private Player player;
    private int freq;
    private long duration;
    private int state = SOUND_UNINITIALIZED;
    private int gain = 255;
    private SoundListener listener;

    public Sound(byte[] data, int type) {
        init(data, type);
    }

    public Sound(int freq, long duration) {
        init(freq, duration);
    }

    public static int getConcurrentSoundCount(int type) { return 4; }

    public static int[] getSupportedFormats() { return new int[]{FORMAT_TONE, FORMAT_WAV}; }

    public void init(byte[] data, int type) {
        release();
        if (type == FORMAT_WAV || (data != null && data.length > 4 && data[0] == 'R' && data[1] == 'I')) {
            try {
                player = Manager.createPlayer(new ByteArrayInputStream(data), null);
                player.addPlayerListener(new PlayerListener() {
                    public void playerUpdate(Player p, String event, Object d) {
                        if (PlayerListener.END_OF_MEDIA.equals(event)) setState(SOUND_STOPPED);
                    }
                });
            } catch (Exception ignored) {
                player = null;
            }
        }
        // FORMAT_TONE (Nokia smart-messaging ringtones) is played silently.
        state = SOUND_STOPPED;
    }

    public void init(int freq, long duration) {
        release();
        this.freq = freq;
        this.duration = duration;
        state = SOUND_STOPPED;
    }

    public void play(int loop) {
        if (player != null) {
            try {
                player.stop();
                player.setLoopCount(loop == 0 ? -1 : loop);
                VolumeControl vc = (VolumeControl) player.getControl("VolumeControl");
                if (vc != null) vc.setLevel(gain * 100 / 255);
                player.start();
                setState(SOUND_PLAYING);
            } catch (Exception ignored) {}
        } else if (freq > 0 && duration > 0) {
            int note = (int) Math.round(69 + 12 * Math.log(freq / 440.0) / Math.log(2));
            try {
                Manager.playTone(Math.max(0, Math.min(127, note)), (int) duration, gain * 100 / 255);
            } catch (Exception ignored) {}
        }
    }

    public void resume() {
        play(1);
    }

    public void stop() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
        }
        setState(SOUND_STOPPED);
    }

    public void release() {
        if (player != null) player.close();
        player = null;
        state = SOUND_UNINITIALIZED;
    }

    public int getState() { return state; }

    public void setGain(int gain) { this.gain = Math.max(0, Math.min(255, gain)); }

    public int getGain() { return gain; }

    public void setSoundListener(SoundListener l) { listener = l; }

    private void setState(int s) {
        state = s;
        SoundListener l = listener;
        if (l != null) l.soundStateChanged(this, s);
    }
}
