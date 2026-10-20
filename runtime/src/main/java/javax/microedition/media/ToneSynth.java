package javax.microedition.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Hashtable;
import java.util.Vector;

/** Renders MIDP tones / ToneControl sequences to PCM and plays them. */
final class ToneSynth {
    static final int RATE = 22050;

    private ToneSynth() {}

    /** A rendered note: frequency (0 = silence), duration in ms, volume 0..100. */
    static final class Note {
        final double freq;
        final int ms;
        final int volume;

        Note(double freq, int ms, int volume) {
            this.freq = freq;
            this.ms = ms;
            this.volume = volume;
        }
    }

    static double freq(int note) {
        return 440.0 * Math.pow(2, (note - 69) / 12.0);
    }

    static short[] render(Vector<Note> notes, float master) {
        int total = 0;
        for (int i = 0; i < notes.size(); i++) total += notes.elementAt(i).ms * RATE / 1000;
        short[] pcm = new short[Math.max(1, total)];
        int pos = 0;
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.elementAt(i);
            int len = n.ms * RATE / 1000;
            if (n.freq > 0) {
                double amp = 9000 * master * (n.volume / 100.0);
                int fade = Math.min(len / 4, RATE / 200);
                for (int k = 0; k < len; k++) {
                    double env = 1;
                    if (k < fade) env = k / (double) fade;
                    else if (k > len - fade) env = (len - k) / (double) fade;
                    double s = Math.sin(2 * Math.PI * n.freq * k / RATE);
                    // a bit of the 3rd harmonic gives the classic "phone beeper" timbre
                    s = 0.8 * s + 0.2 * Math.sin(6 * Math.PI * n.freq * k / RATE);
                    pcm[pos + k] = (short) (amp * env * s);
                }
            }
            pos += len;
        }
        return pcm;
    }

    @SuppressWarnings("deprecation")
    static AudioTrack play(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        t.play();
        return t;
    }

    static void playTone(final int note, final int durationMs, final int volume, final float master) {
        new Thread(new Runnable() {
            public void run() {
                Vector<Note> v = new Vector<Note>();
                v.addElement(new Note(freq(note), Math.max(1, durationMs), Math.max(0, Math.min(100, volume))));
                AudioTrack t = null;
                try {
                    t = play(render(v, master));
                    Thread.sleep(durationMs + 50);
                } catch (Throwable ignored) {
                } finally {
                    if (t != null) t.release();
                }
            }
        }, "J2ME-Tone").start();
    }

    /** Parses a ToneControl sequence (JSR-135 format). */
    static Vector<Note> parse(byte[] seq) {
        Vector<Note> out = new Vector<Note>();
        if (seq == null || seq.length < 2) return out;
        int i = 0;
        int tempo = 30, resolution = 64;
        if (seq[i] == -2) i += 2; // VERSION 1
        while (i + 1 < seq.length && (seq[i] == -3 || seq[i] == -4)) {
            if (seq[i] == -3) tempo = seq[i + 1] & 0xFF;
            else resolution = seq[i + 1] & 0xFF;
            i += 2;
        }
        Hashtable<Integer, int[]> blocks = new Hashtable<Integer, int[]>();
        while (i + 1 < seq.length && seq[i] == -5) {
            int num = seq[i + 1];
            int start = i + 2, j = start;
            while (j + 1 < seq.length && !(seq[j] == -6 && seq[j + 1] == num)) j += 2;
            blocks.put(num, new int[]{start, j});
            i = j + 2;
        }
        int[] vol = {100};
        events(seq, i, seq.length, blocks, out, Math.max(1, tempo * 4), Math.max(1, resolution), vol, 0);
        return out;
    }

    private static void events(byte[] s, int from, int to, Hashtable<Integer, int[]> blocks, Vector<Note> out,
                               int bpm, int res, int[] vol, int depth) {
        if (depth > 8) return;
        int i = from;
        while (i + 1 < to) {
            int a = s[i], b = s[i + 1];
            if (a >= 0 || a == -1) {
                int ms = (b & 0xFF) * 240000 / (res * bpm);
                out.addElement(new Note(a == -1 ? 0 : freq(a), ms, vol[0]));
                i += 2;
            } else if (a == -7) {
                int[] blk = blocks.get(b);
                if (blk != null) events(s, blk[0], blk[1], blocks, out, bpm, res, vol, depth + 1);
                i += 2;
            } else if (a == -8) {
                vol[0] = Math.max(0, Math.min(100, b));
                i += 2;
            } else if (a == -9 && i + 3 < to) {
                int count = b & 0xFF;
                int note = s[i + 2], dur = s[i + 3] & 0xFF;
                for (int k = 0; k < count; k++)
                    out.addElement(new Note(note == -1 ? 0 : freq(note), dur * 240000 / (res * bpm), vol[0]));
                i += 4;
            } else {
                i += 2;
            }
        }
    }
}
