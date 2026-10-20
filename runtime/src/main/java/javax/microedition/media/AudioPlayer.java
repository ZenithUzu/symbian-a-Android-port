package javax.microedition.media;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import j2a.core.Host;

/** Plays MIDI, WAV, MP3, AMR, AAC and OGG through android.media.MediaPlayer. */
final class AudioPlayer extends BasePlayer {
    private final byte[] data;
    private final String url;
    private String path;
    private volatile MediaPlayer mp;
    private int loopsLeft;

    AudioPlayer(byte[] data, String type) {
        super(type == null ? sniff(data) : type);
        this.data = data;
        this.url = null;
    }

    AudioPlayer(String url, String type) {
        super(type);
        this.data = null;
        this.url = url;
    }

    static String sniff(byte[] d) {
        if (d.length >= 4 && d[0] == 'M' && d[1] == 'T' && d[2] == 'h' && d[3] == 'd') return "audio/midi";
        if (d.length >= 4 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F') return "audio/x-wav";
        if (d.length >= 5 && d[0] == '#' && d[1] == '!' && d[2] == 'A' && d[3] == 'M' && d[4] == 'R') return "audio/amr";
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return "audio/ogg";
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return "audio/mpeg";
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return "audio/mpeg";
        if (d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return "audio/mp4";
        return "audio/unknown";
    }

    private static String extension(String type) {
        if (type == null) return ".bin";
        type = type.toLowerCase();
        if (type.contains("midi") || type.contains("mid")) return ".mid";
        if (type.contains("wav")) return ".wav";
        if (type.contains("amr")) return ".amr";
        if (type.contains("ogg")) return ".ogg";
        if (type.contains("mpeg") || type.contains("mp3")) return ".mp3";
        if (type.contains("mp4") || type.contains("aac") || type.contains("m4a")) return ".m4a";
        return ".bin";
    }

    @Override
    void doRealize() throws MediaException {
        if (data == null) return;
        try {
            File dir = new File(Host.cacheDir(), "j2a_media");
            if (!dir.exists()) dir.mkdirs();
            int hash = java.util.Arrays.hashCode(data);
            File f = new File(dir, Integer.toHexString(hash) + "_" + data.length + extension(contentType));
            if (!f.exists() || f.length() != data.length) {
                FileOutputStream out = new FileOutputStream(f);
                try { out.write(data); } finally { out.close(); }
            }
            path = f.getAbsolutePath();
        } catch (IOException e) {
            throw new MediaException(e.toString());
        }
    }

    /**
     * All MediaPlayer work runs on one audio thread. MediaPlayer.prepare() takes tens of ms;
     * done on the game thread (many games create or restart a Player for every sound effect)
     * it froze the game for a moment each time a sound played.
     */
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "j2a-audio");
            t.setDaemon(true);
            return t;
        }
    });

    /** Audio thread only. */
    private void prepareNow() {
        if (mp != null) return;
        MediaPlayer m = new MediaPlayer();
        try {
            m.setDataSource(path != null ? path : url);
            m.prepare();
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer x) {
                    AUDIO.execute(new Runnable() { public void run() { completed(); } });
                }
            });
            mp = m;
        } catch (Exception e) {
            m.release();
            Log.w("j2a", "Cannot play " + contentType + ": " + e);
        }
    }

    @Override
    void doPrefetch() {
        AUDIO.execute(new Runnable() { public void run() { prepareNow(); } });
    }

    /** Audio thread only. */
    private void completed() {
        MediaPlayer m = mp;
        if (m == null) return;
        if (loopCount > 0 && --loopsLeft > 0) {
            m.seekTo(0);
            m.start();
            return;
        }
        m.seekTo(0);
        endOfMedia();
    }

    @Override
    void doStart() {
        final int loops = loopCount;
        AUDIO.execute(new Runnable() {
            public void run() {
                prepareNow();
                MediaPlayer m = mp;
                if (m == null) {
                    endOfMedia(); // unsupported file: behave like a sound that already ended
                    return;
                }
                loopsLeft = loops;
                m.setLooping(loops < 0);
                float v = volume();
                m.setVolume(v, v);
                m.start();
            }
        });
    }

    @Override
    void doStop() {
        AUDIO.execute(new Runnable() {
            public void run() {
                MediaPlayer m = mp;
                if (m != null && m.isPlaying()) m.pause();
            }
        });
    }

    @Override
    void doDeallocate() {
        AUDIO.execute(new Runnable() {
            public void run() {
                if (mp != null) {
                    mp.release();
                    mp = null;
                }
            }
        });
    }

    @Override
    void applyVolume(final float v) {
        AUDIO.execute(new Runnable() {
            public void run() {
                if (mp != null) mp.setVolume(v, v);
            }
        });
    }

    public long setMediaTime(long now) {
        final int ms = (int) (Math.max(0, now) / 1000);
        AUDIO.execute(new Runnable() {
            public void run() {
                if (mp != null) mp.seekTo(ms);
            }
        });
        return now;
    }

    public long getMediaTime() {
        try { return mp == null ? 0 : mp.getCurrentPosition() * 1000L; } catch (Throwable t) { return 0; }
    }

    public long getDuration() {
        try {
            if (mp == null) return TIME_UNKNOWN;
            int d = mp.getDuration();
            return d < 0 ? TIME_UNKNOWN : d * 1000L;
        } catch (Throwable t) {
            return TIME_UNKNOWN;
        }
    }
}
