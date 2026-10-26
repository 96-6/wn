package app.wn.audio;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Continuous spectral noise; independent streams and smooth gains for every generator. */
public final class NoiseMixer {
    public static final int SAMPLE_RATE = 48000, FFT_SIZE = 4096, FRAMES = FFT_SIZE / 2;
    private static final double HEADROOM = .16;
    private static final double SMOOTHING = 1 - Math.exp(-1.0 / (SAMPLE_RATE * .04));
    private final long seed;
    private final LinkedHashMap<String, Voice> voices = new LinkedHashMap<>();
    private Voice[] renderVoices = new Voice[0];
    private final double[] mids = new double[FRAMES], sides = new double[FRAMES];
    private MixerConfig previous;
    private double master, width = 1, limiter = 1, duck = 1;

    public NoiseMixer(long seed) { this.seed = seed; }

    public void render(float[] stereo, MixerConfig config, double duckTarget) {
        if (stereo.length != FRAMES * 2) throw new IllegalArgumentException("Wrong output buffer size");
        if (config != previous) {
            for (Voice v : voices.values()) { v.retained = false; v.target = 0; }
            for (MixerConfig.Generator g : config.generators) {
                Voice v = voices.get(g.id);
                if (v == null) {
                    v = new Voice(seed ^ idSeed(g.id));
                    voices.put(g.id, v);
                }
                v.retained = true; v.target = g.enabled ? g.level : 0;
                v.texture = g.texture;
                if (v.preset != g.preset) {
                    v.preset = g.preset; v.mid.setProfile(g.preset); v.side.setProfile(g.preset);
                }
            }
            renderVoices = voices.values().toArray(new Voice[0]);
            previous = config;
        }
        Arrays.fill(mids, 0); Arrays.fill(sides, 0);
        boolean retired = false;
        for (Voice v : renderVoices) {
            if (v.target > 0 || v.gain > .00001) {
                v.mid.render(v.m); v.side.render(v.s);
                double textureTarget = v.texture == MixerConfig.Texture.OSCILLATING ? 1 : 0;
                boolean motion = v.preset.motion != null && (textureTarget > 0 || v.oscillation > .00001);
                if (motion) v.oscillator.render(v.preset, v.tm, v.ts, v.pulse);
                for (int i = 0; i < FRAMES; i++) {
                    v.gain += (v.target - v.gain) * SMOOTHING;
                    v.oscillation += (textureTarget - v.oscillation) * SMOOTHING;
                    double mix = motion ? v.oscillation : 0;
                    double noise = 1 + mix * (v.pulse[i] - 1);
                    mids[i] += (v.m[i] * noise + v.tm[i] * mix * .65) * v.gain;
                    sides[i] += (v.s[i] * noise + v.ts[i] * mix * .65) * v.gain;
                }
            } else {
                v.gain = 0; v.mid.clear(); v.side.clear();
                retired |= !v.retained;
            }
        }
        double max = 0;
        for (int i = 0; i < FRAMES; i++) {
            master += (config.master - master) * SMOOTHING;
            width += (config.width.side - width) * SMOOTHING;
            duck += (duckTarget - duck) * SMOOTHING;
            double gain = HEADROOM * master * duck / Math.sqrt(1 + width * width);
            stereo[i * 2] = (float) ((mids[i] + sides[i] * width) * gain);
            stereo[i * 2 + 1] = (float) ((mids[i] - sides[i] * width) * gain);
            max = Math.max(max, Math.max(Math.abs(stereo[i * 2]), Math.abs(stereo[i * 2 + 1])));
        }
        double safeGain = max > .96 ? .96 / max : 1;
        limiter = safeGain < limiter ? safeGain : Math.min(safeGain, limiter + .005);
        for (int i = 0; i < stereo.length; i++) stereo[i] *= (float) limiter;
        if (retired) {
            Iterator<Map.Entry<String, Voice>> it = voices.entrySet().iterator();
            while (it.hasNext()) { Voice v = it.next().getValue(); if (!v.retained && v.gain == 0) it.remove(); }
            renderVoices = voices.values().toArray(new Voice[0]);
        }
    }

    private static long idSeed(String id) {
        long value = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) { value ^= id.charAt(i); value *= 0x100000001b3L; }
        return value;
    }

    private static final class Voice {
        final Source mid, side;
        final MotionOscillator oscillator;
        final double[] m = new double[FRAMES], s = new double[FRAMES];
        final double[] tm = new double[FRAMES], ts = new double[FRAMES], pulse = new double[FRAMES];
        NoisePreset preset;
        MixerConfig.Texture texture = MixerConfig.Texture.CONTINUOUS;
        double target, gain, oscillation;
        boolean retained;
        Voice(long seed) {
            mid = new Source(seed ^ 0x12345678L); side = new Source(seed ^ 0x76a2b345L);
            oscillator = new MotionOscillator(seed);
        }
    }

    private static final class Source {
        final double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
        final double[] tail = new double[FRAMES], spectrum = new double[FFT_SIZE / 2];
        final double[] window = new double[FFT_SIZE];
        long random;
        Source(long seed) {
            random = seed == 0 ? 1 : seed;
            for (int i = 0; i < FFT_SIZE; i++) window[i] = Math.sqrt(.5 - .5 * Math.cos(2 * Math.PI * i / FFT_SIZE));
        }
        void setProfile(NoisePreset p) {
            for (int i = 0; i < spectrum.length; i++)
                spectrum[i] = p == null ? 0 : p.gainAt(i * (double) SAMPLE_RATE / FFT_SIZE) * Math.sqrt(FFT_SIZE / 2.0);
        }
        double noise() {
            random ^= random >>> 12; random ^= random << 25; random ^= random >>> 27;
            long value = random * 2685821657736338717L;
            return ((value >>> 11) * 0x1.0p-53 * 2 - 1) * 1.7320508075688772;
        }
        void render(double[] output) {
            re[0] = im[0] = re[FFT_SIZE / 2] = im[FFT_SIZE / 2] = 0;
            for (int k = 1; k < FFT_SIZE / 2; k++) {
                double real = noise() * spectrum[k], imaginary = noise() * spectrum[k];
                re[k] = re[FFT_SIZE - k] = real;
                im[k] = imaginary; im[FFT_SIZE - k] = -imaginary;
            }
            Fft.transform(re, im, true);
            for (int i = 0; i < FRAMES; i++) {
                output[i] = tail[i] + re[i] * window[i];
                tail[i] = re[i + FRAMES] * window[i + FRAMES];
            }
        }
        void clear() { java.util.Arrays.fill(tail, 0); }
    }
}
