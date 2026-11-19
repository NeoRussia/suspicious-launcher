package suspiciousspeculars.launcher.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import java.awt.Toolkit;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * The one sound the window makes: Duck mode.
 *
 * Java 8's javax.sound plays PCM, not MP3, so the cue ships as a 15 KB mono WAV
 * (ui/sound/quack.wav), converted once from the web build's reference/quack.mp3 with
 * {@code ffmpeg -i quack.mp3 -ac 1 -ar 44100 -sample_fmt s16 quack.wav}. Nothing draws it, so the
 * pixel gates cannot see it: {@code --sound} checks the jar carries a decodable cue and the build
 * runs that. Playback runs on a daemon thread: opening a line can block, and the window must never
 * wait for a quack. A machine with no mixer falls back to the beep the tile made before.
 */
public final class Sound {

    private Sound() {
    }

    /** Start the cue at {@code resource} (a classpath path) and return immediately. */
    public static void play(final String resource) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                if (!start(resource, false)) fallback();
            }
        }, "quack");
        thread.setDaemon(true);
        thread.start();
    }

    /** Play it and wait for the end; for {@code --quack}, which has nothing else to do. */
    public static boolean playNow(String resource) {
        return start(resource, true);
    }

    /** The cue's format, or "missing <path>" - the check that the jar actually carries it. */
    public static String describe(String resource) {
        InputStream raw = Sound.class.getResourceAsStream(resource);
        if (raw == null) return "missing " + resource;
        try {
            AudioInputStream audio =
                    AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            try {
                AudioFormat f = audio.getFormat();
                return String.format(Locale.ROOT, "%s  %s %.0f Hz, %d bit, %d ch, %.2f s",
                        resource, f.getEncoding(), f.getSampleRate(), f.getSampleSizeInBits(),
                        f.getChannels(), audio.getFrameLength() / f.getFrameRate());
            } finally {
                audio.close();
            }
        } catch (Exception e) {
            return "not playable: " + resource + " (" + e.getMessage() + ")";
        }
    }

    private static boolean start(String resource, boolean wait) {
        Clip clip = null;
        try {
            InputStream raw = Sound.class.getResourceAsStream(resource);
            if (raw == null) return false;
            AudioInputStream audio =
                    AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            clip = AudioSystem.getClip();
            final Clip opened = clip;
            clip.addLineListener(new LineListener() {
                public void update(LineEvent event) {
                    if (event.getType() == LineEvent.Type.STOP) opened.close();
                }
            });
            clip.open(audio);
            audio.close();
            clip.start();
            if (wait) {
                while (clip.isRunning()) Thread.sleep(20);
            }
            return true;
        } catch (Throwable t) {
            if (clip != null) {
                try {
                    clip.close();
                } catch (Throwable ignored) {
                    // already closed
                }
            }
            return false;
        }
    }

    private static void fallback() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignored) {
            // headless, or no sound device at all: nothing left to try
        }
    }
}
