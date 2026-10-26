package app.wn.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class NoiseMixerTest {
    private static NoisePreset narrow() {
        return NoisePreset.parse("{\"version\":1,\"name\":\"Test band\",\"bands\":[{\"lowHz\":900,\"highHz\":1800,\"level\":1}]}");
    }
    private static MixerConfig config(boolean d, boolean a, double dl, double al, double master, MixerConfig.Width width, NoisePreset p) {
        java.util.ArrayList<MixerConfig.Generator> generators = new java.util.ArrayList<>();
        generators.add(new MixerConfig.Generator("default", NoisePreset.DEFAULT, d, dl));
        if (p != null) generators.add(new MixerConfig.Generator("adjusted", p, a, al));
        return new MixerConfig(generators, master, width);
    }
    @Test public void monoIsIdenticalAndStereoWidthChangesCorrelation() {
        double[] correlations = new double[4];
        for (MixerConfig.Width width : MixerConfig.Width.values()) {
            NoiseMixer m = new NoiseMixer(1234); float[] b = new float[NoiseMixer.FRAMES * 2];
            MixerConfig c = config(true, false, 1, 0, 1, width, null);
            for (int i = 0; i < 30; i++) m.render(b, c, 1);
            double ll = 0, rr = 0, lr = 0;
            for (int block = 0; block < 30; block++) {
                m.render(b, c, 1);
                for (int i = 0; i < b.length; i += 2) {
                    if (width == MixerConfig.Width.MONO) assertEquals(b[i], b[i + 1], 1e-7);
                    ll += b[i] * b[i]; rr += b[i + 1] * b[i + 1]; lr += b[i] * b[i + 1];
                }
            }
            correlations[width.ordinal()] = lr / Math.sqrt(ll * rr);
            double expected = (1 - width.side * width.side) / (1 + width.side * width.side);
            assertEquals(expected, correlations[width.ordinal()], .04);
        }
        assertTrue(correlations[0] > correlations[1]); assertTrue(correlations[1] > correlations[2]);
        assertTrue(correlations[2] > correlations[3]);
    }
    @Test public void layersMixIndependentlyAndDoNotChangeEachOthersGain() {
        NoisePreset p = narrow(); NoiseMixer mix = new NoiseMixer(17), first = new NoiseMixer(17), second = new NoiseMixer(17);
        float[] both = new float[NoiseMixer.FRAMES * 2], a = both.clone(), b = both.clone();
        MixerConfig c = config(true, true, .4, .7, .5, MixerConfig.Width.DEFAULT, p);
        MixerConfig ca = config(true, false, .4, .7, .5, MixerConfig.Width.DEFAULT, p);
        MixerConfig cb = config(false, true, .4, .7, .5, MixerConfig.Width.DEFAULT, p);
        for (int block = 0; block < 25; block++) {
            mix.render(both, c, 1); first.render(a, ca, 1); second.render(b, cb, 1);
            for (int i = 0; i < both.length; i++) assertEquals(a[i] + b[i], both[i], 1e-7);
        }
    }
    @Test public void fullPowerRemainsFiniteAndBelowPeakLimit() {
        NoiseMixer m = new NoiseMixer(24); NoisePreset p = NoisePreset.parse(NoisePreset.DEFAULT.toJson());
        MixerConfig c = config(true, true, 1, 1, 1, MixerConfig.Width.WIDE, p);
        float[] b = new float[NoiseMixer.FRAMES * 2]; double energy = 0;
        for (int j = 0; j < 200; j++) {
            m.render(b, c, 1);
            for (float v : b) { assertTrue(Float.isFinite(v)); assertTrue(Math.abs(v) <= .960001); energy += v * v; }
        }
        assertTrue(energy > 1);
    }
    @Test public void disablingBothLayersSettlesToSilenceAndCanRestart() {
        NoiseMixer m = new NoiseMixer(41); float[] b = new float[NoiseMixer.FRAMES * 2];
        MixerConfig on = config(true, false, 1, 0, 1, MixerConfig.Width.DEFAULT, null);
        MixerConfig off = config(false, false, 1, 0, 1, MixerConfig.Width.DEFAULT, null);
        for (int i = 0; i < 10; i++) m.render(b, on, 1);
        for (int i = 0; i < 35; i++) m.render(b, off, 1);
        for (float v : b) assertEquals(0, v, 1e-6);
        for (int i = 0; i < 10; i++) m.render(b, on, 1);
        double e = 0; for (float v : b) e += v * v; assertTrue(e > .1);
    }
    @Test public void adjustedOutputRejectsUnselectedFrequencies() {
        NoiseMixer m = new NoiseMixer(99); float[] b = new float[NoiseMixer.FRAMES * 2];
        MixerConfig c = config(false, true, 0, 1, 1, MixerConfig.Width.MONO, narrow());
        for (int i = 0; i < 20; i++) m.render(b, c, 1);
        int n = 8192; double[] signal = new double[n], imaginary = new double[n];
        for (int block = 0; block < n / NoiseMixer.FRAMES; block++) {
            m.render(b, c, 1);
            for (int i = 0; i < NoiseMixer.FRAMES; i++) signal[block * NoiseMixer.FRAMES + i] = b[i * 2];
        }
        for (int i = 0; i < n; i++) signal[i] *= .5 - .5 * Math.cos(2 * Math.PI * i / n);
        Fft.transform(signal, imaginary, false);
        double selected = 0, rejected = 0;
        for (int k = 1; k < n / 2; k++) {
            double hz = k * (double) NoiseMixer.SAMPLE_RATE / n;
            double power = signal[k] * signal[k] + imaginary[k] * imaginary[k];
            if (hz >= 950 && hz <= 1750) selected += power;
            if (hz < 650 || hz > 2100) rejected += power;
        }
        assertTrue("Stopband rejection must exceed 35 dB; ratio=" + selected / rejected, selected / rejected > Math.pow(10, 3.5));
    }
    @Test public void generatedBlocksAreNotRepeatingSamples() {
        NoiseMixer m = new NoiseMixer(52); float[] a = new float[NoiseMixer.FRAMES * 2], b = a.clone();
        MixerConfig c = config(true, false, 1, 0, 1, MixerConfig.Width.DEFAULT, null);
        for (int i = 0; i < 12; i++) m.render(a, c, 1);
        m.render(b, c, 1); assertFalse(java.util.Arrays.equals(a, b));
    }
    @Test public void absentProfileCannotEnableAdjustedLayerAndInvalidLevelsAreClamped() {
        MixerConfig c = config(false, true, Double.NaN, 3, -1, null, null);
        assertEquals(1, c.generators.size()); assertFalse(c.hasAudibleLayer()); assertEquals(0, c.master, 0);
        assertEquals(0, c.generators.get(0).level, 0);
    }
}
