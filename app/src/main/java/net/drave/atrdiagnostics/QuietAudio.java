package net.drave.atrdiagnostics;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.util.function.Consumer;

/**
 * Keeps Daanvia alive on OPPO, OnePlus and realme phones while the bike display is on. Their battery manager closes
 * apps about five minutes after the screen goes off ("o-kill" in the logs of 1 Nov, every 5-6 minutes in a pocket, with
 * battery limits already off), but leaves apps that are playing audio alone. So this plays silence:
 *  - zeros, looped by the audio hardware (no CPU work after it starts);
 *  - on the phone's own speaker, so a Bluetooth intercom isn't kept busy by it;
 *  - without audio focus, so music and Daanvia's voice guidance are never paused or ducked by it.
 */
final class QuietAudio {
    private final Consumer<String> log;
    private AudioTrack track;

    QuietAudio(Consumer<String> log) { this.log = log; }

    synchronized void start(Context c) {
        if (track != null) return;
        try {
            int rate = 8000, frames = rate;  // one second of silence, looped
            AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames * 2).build();
            t.write(new short[frames], 0, frames);
            t.setLoopPoints(0, frames, -1);
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null) for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { t.setPreferredDevice(d); break; }
            t.play();
            track = t;
            log.accept("Keep-alive: playing silence so this phone doesn't close Daanvia with the screen off.");
        } catch (Exception e) { log.accept("Keep-alive audio unavailable: " + e.getClass().getSimpleName()); }
    }

    synchronized void stop() {
        if (track == null) return;
        try { track.stop(); } catch (Exception ignored) {}
        track.release(); track = null;
    }
}
