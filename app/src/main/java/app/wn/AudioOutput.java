package app.wn;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import app.wn.audio.MixerConfig;
import app.wn.audio.NoiseMixer;
import java.util.function.Consumer;

/** Owns the synthesis thread and AudioTrack; stopping flushes queued audio immediately. */
final class AudioOutput {
    private volatile MixerConfig config;
    private volatile boolean running;
    private volatile double duck = 1;
    private volatile AudioTrack track;
    private Thread thread;
    private final Consumer<String> onError;

    AudioOutput(MixerConfig initial, Consumer<String> onError) { config = initial; this.onError = onError; }
    void update(MixerConfig settings) { config = settings; }
    void setDucked(boolean value) { duck = value ? .18 : 1; }
    void start() {
        running = true;
        thread = new Thread(this::play, "WN-synthesis");
        thread.start();
    }
    private void play() {
        AudioTrack output = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            int minimum = AudioTrack.getMinBufferSize(NoiseMixer.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            if (minimum <= 0) throw new IllegalStateException("Stereo audio output is unavailable.");
            output = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(NoiseMixer.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minimum * 2, NoiseMixer.FRAMES * 2 * 4 * 3)).build();
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Audio output could not start.");
            track = output;
            if (!running) return;
            NoiseMixer mixer = new NoiseMixer(System.nanoTime());
            float[] block = new float[NoiseMixer.FRAMES * 2];
            output.play();
            while (running) {
                mixer.render(block, config, duck);
                int offset = 0;
                while (running && offset < block.length) {
                    int written = output.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("Audio device disconnected (" + written + "). Tap Start to retry.");
                    if (written == 0) Thread.yield();
                    offset += written;
                }
            }
        } catch (RuntimeException e) {
            if (running) onError.accept(e.getMessage() == null ? "Playback failed. Tap Start to retry." : e.getMessage());
        } finally {
            if (output != null) {
                try { output.pause(); output.flush(); } catch (IllegalStateException ignored) { }
                output.release();
            }
            track = null;
        }
    }
    void stop() {
        running = false;
        AudioTrack output = track;
        if (output != null) try { output.pause(); output.flush(); } catch (IllegalStateException ignored) { }
        if (thread != null) thread.interrupt();
    }
    static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }
}
