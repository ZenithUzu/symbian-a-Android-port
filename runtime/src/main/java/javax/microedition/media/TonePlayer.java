package javax.microedition.media;

import android.media.AudioTrack;

import java.util.Vector;

import javax.microedition.media.control.ToneControl;

/** Player for Manager.TONE_DEVICE_LOCATOR with a working ToneControl. */
final class TonePlayer extends BasePlayer implements ToneControl {
    private byte[] sequence;
    private short[] pcm;
    private int durationMs;
    private AudioTrack track;
    private Thread waiter;
    private long startedAt;

    TonePlayer() {
        super("audio/x-tone-seq");
    }

    public void setSequence(byte[] seq) {
        if (state == STARTED) throw new IllegalStateException();
        sequence = seq;
        pcm = null;
    }

    @Override
    public Control getControl(String type) {
        if (type != null && type.endsWith("ToneControl")) return this;
        return super.getControl(type);
    }

    @Override
    public Control[] getControls() {
        return new Control[]{this};
    }

    @Override
    void doRealize() {}

    @Override
    void doPrefetch() {
        Vector<ToneSynth.Note> notes = ToneSynth.parse(sequence);
        int ms = 0;
        for (int i = 0; i < notes.size(); i++) ms += notes.elementAt(i).ms;
        durationMs = ms;
        pcm = ToneSynth.render(notes, 1f);
    }

    @Override
    void doStart() {
        if (pcm == null) doPrefetch();
        releaseTrack();
        if (durationMs <= 0) {
            endOfMedia();
            return;
        }
        try {
            short[] scaled = pcm;
            float v = volume();
            if (v < 0.999f) {
                scaled = new short[pcm.length];
                for (int i = 0; i < pcm.length; i++) scaled[i] = (short) (pcm[i] * v);
            }
            track = ToneSynth.play(scaled);
        } catch (Throwable ignored) {}
        startedAt = System.currentTimeMillis();
        final int loops = loopCount;
        waiter = new Thread(new Runnable() {
            public void run() {
                int left = loops;
                try {
                    while (true) {
                        Thread.sleep(durationMs);
                        if (left > 0 && --left == 0) break;
                        AudioTrack t = track;
                        if (t != null) {
                            t.stop();
                            t.reloadStaticData();
                            t.play();
                        }
                        startedAt = System.currentTimeMillis();
                    }
                    endOfMedia();
                } catch (InterruptedException ignored) {}
            }
        }, "J2ME-ToneSeq");
        waiter.start();
    }

    private void releaseTrack() {
        if (waiter != null) {
            waiter.interrupt();
            waiter = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            track.release();
            track = null;
        }
    }

    @Override
    void doStop() { releaseTrack(); }

    @Override
    void doDeallocate() { releaseTrack(); }

    @Override
    void applyVolume(float v) {}

    public long setMediaTime(long now) { return 0; }

    public long getMediaTime() {
        return state == STARTED ? (System.currentTimeMillis() - startedAt) * 1000L : 0;
    }

    public long getDuration() { return durationMs * 1000L; }
}
