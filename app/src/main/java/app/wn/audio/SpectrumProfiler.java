package app.wn.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded-memory Welch spectrum, RMS gating, and sparse 40-Hz band selection. */
public final class SpectrumProfiler {
    public static final int SIZE = 4096, HOP = 2048;
    public static final double LOW_HZ = 80, HIGH_HZ = 8000, BIN_HZ = 40;
    public static final class Tuning {
        public final double coverage, occupancy;
        public final int maxBands;
        public Tuning(double coverage, double occupancy, int maxBands) {
            if (!Double.isFinite(coverage) || !Double.isFinite(occupancy) || coverage < .1 || coverage > 1 || occupancy < .01 || occupancy > 1 || maxBands < 1 || maxBands > 64)
                throw new IllegalArgumentException("Invalid profile settings.");
            this.coverage = coverage; this.occupancy = occupancy; this.maxBands = maxBands;
        }
        public static Tuning narrow() { return new Tuning(.65, .2, 12); }
        public static Tuning balanced() { return new Tuning(.8, .35, 16); }
        public static Tuning broad() { return new Tuning(.9, .5, 24); }
    }
    public static final class Result {
        public final NoisePreset profile;
        public final double seconds, energyCoverage, bandwidthHz, peakDbfs, rmsDbfs;
        public final long analysedFrames, usedFrames, aboveFullScaleSamples;
        Result(NoisePreset profile, double seconds, double energyCoverage, double bandwidthHz,
               double peakDbfs, double rmsDbfs, long frames, long used, long clipped) {
            this.profile = profile; this.seconds = seconds; this.energyCoverage = energyCoverage;
            this.bandwidthHz = bandwidthHz; this.peakDbfs = peakDbfs; this.rmsDbfs = rmsDbfs;
            analysedFrames = frames; usedFrames = used; aboveFullScaleSamples = clipped;
        }
    }
    private final int sampleRate, channels, binCount;
    private final float[][] input;
    private final double[] window = new double[SIZE], re = new double[SIZE], im = new double[SIZE], binPower;
    private final double[][] histogram;
    private final long[] histogramFrames = new long[97];
    private int filled;
    private long samples, frames, overScale;
    private double peak, totalEnergy, peakFrameDb = -300, windowEnergy;

    public SpectrumProfiler(int sampleRate, int channels) {
        if (sampleRate < 8000 || sampleRate > 384000 || channels < 1 || channels > 32)
            throw new IllegalArgumentException("Unsupported PCM sample rate or channel count.");
        this.sampleRate = sampleRate; this.channels = channels;
        binCount = (int) Math.floor((Math.min(HIGH_HZ, sampleRate / 2.0) - LOW_HZ) / BIN_HZ);
        input = new float[channels][SIZE]; binPower = new double[binCount]; histogram = new double[97][binCount];
        for (int i = 0; i < SIZE; i++) {
            window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / SIZE);
            windowEnergy += window[i] * window[i];
        }
    }
    public void accept(float[] interleaved, int offset, int length) {
        if (length % channels != 0 || offset < 0 || length < 0 || offset + length > interleaved.length)
            throw new IllegalArgumentException("PCM must contain complete interleaved frames.");
        for (int i = offset; i < offset + length; i += channels) {
            for (int c = 0; c < channels; c++) {
                float v = interleaved[i + c];
                if (!Float.isFinite(v)) throw new IllegalArgumentException("Audio contains non-finite PCM values.");
                input[c][filled] = v; totalEnergy += (double) v * v;
                peak = Math.max(peak, Math.abs(v)); if (Math.abs(v) >= .999) overScale++;
            }
            filled++; samples++;
            if (filled == SIZE) {
                analyse();
                for (float[] channel : input) System.arraycopy(channel, HOP, channel, 0, SIZE - HOP);
                filled = SIZE - HOP;
            }
        }
    }
    public double seconds() { return samples / (double) sampleRate; }
    private void analyse() {
        Arrays.fill(binPower, 0);
        double frameEnergy = 0;
        for (int c = 0; c < channels; c++) {
            double mean = 0;
            for (int i = 0; i < SIZE; i++) { mean += input[c][i]; frameEnergy += (double) input[c][i] * input[c][i]; }
            mean /= SIZE;
            for (int i = 0; i < SIZE; i++) { re[i] = (input[c][i] - mean) * window[i]; im[i] = 0; }
            Fft.transform(re, im, false);
            for (int k = 1; k < SIZE / 2; k++) {
                double hz = k * (double) sampleRate / SIZE;
                int b = (int) Math.floor((hz - LOW_HZ) / BIN_HZ);
                if (b >= 0 && b < binCount)
                    binPower[b] += 2 * (re[k] * re[k] + im[k] * im[k]) / (SIZE * windowEnergy * channels);
            }
        }
        double db = 10 * Math.log10(Math.max(frameEnergy / (SIZE * channels), 1e-30));
        peakFrameDb = Math.max(peakFrameDb, db);
        int bucket = Math.max(-96, Math.min(0, (int) Math.floor(db))) + 96;
        for (int b = 0; b < binCount; b++) histogram[bucket][b] += binPower[b];
        histogramFrames[bucket]++; frames++;
    }
    public Result finish(String name, Tuning tuning) {
        if (seconds() < .5 || frames < 4 || peakFrameDb < -60)
            throw new IllegalArgumentException("Not enough audible audio. Record at least one second of clear speech.");
        int gate = Math.max(-96, Math.min(0, (int) Math.ceil(Math.max(-60, peakFrameDb - 25)))) + 96;
        double[] power = new double[binCount], smooth = new double[binCount];
        long used = 0;
        for (int h = gate; h < 97; h++) {
            used += histogramFrames[h];
            for (int b = 0; b < binCount; b++) power[b] += histogram[h][b];
        }
        if (used < 2) throw new IllegalArgumentException("Not enough non-silent frames.");
        double total = 0, smoothTotal = 0, strongest = 0;
        for (int b = 0; b < binCount; b++) {
            total += power[b];
            smooth[b] = .5 * power[b] + .25 * power[Math.max(0, b - 1)] + .25 * power[Math.min(binCount - 1, b + 1)];
            smoothTotal += smooth[b]; strongest = Math.max(strongest, smooth[b]);
        }
        if (total < 1e-18 || !Double.isFinite(total)) throw new IllegalArgumentException("No usable energy in the speech-frequency range.");
        Integer[] order = new Integer[binCount];
        for (int b = 0; b < binCount; b++) order[b] = b;
        Arrays.sort(order, Comparator.<Integer>comparingDouble(b -> -smooth[b]).thenComparingInt(b -> b));
        boolean[] selected = new boolean[binCount];
        int cap = Math.max(1, (int) Math.ceil(binCount * tuning.occupancy)), count = 0;
        double captured = 0;
        for (int b : order) {
            if (count >= cap || captured >= smoothTotal * tuning.coverage || smooth[b] < strongest * .001) break;
            selected[b] = true; captured += smooth[b]; count++;
        }
        ArrayList<int[]> runs = new ArrayList<>();
        for (int b = 0; b < binCount;) {
            if (!selected[b]) { b++; continue; }
            int start = b; while (b < binCount && selected[b]) b++;
            runs.add(new int[]{start, b});
        }
        runs.sort((a, b) -> {
            double pa = 0, pb = 0;
            for (int i = a[0]; i < a[1]; i++) pa += smooth[i];
            for (int i = b[0]; i < b[1]; i++) pb += smooth[i];
            int comparison = Double.compare(pb, pa); return comparison != 0 ? comparison : Integer.compare(a[0], b[0]);
        });
        while (runs.size() > tuning.maxBands) runs.remove(runs.size() - 1);
        runs.sort(Comparator.comparingInt(a -> a[0]));
        double maxMean = 0, bandwidth = 0, keptEnergy = 0;
        for (int[] run : runs) {
            double sum = 0; for (int b = run[0]; b < run[1]; b++) { sum += smooth[b]; keptEnergy += power[b]; }
            maxMean = Math.max(maxMean, sum / (run[1] - run[0])); bandwidth += (run[1] - run[0]) * BIN_HZ;
        }
        try {
            JSONArray bands = new JSONArray();
            for (int[] run : runs) {
                double sum = 0; for (int b = run[0]; b < run[1]; b++) sum += smooth[b];
                bands.put(new JSONObject().put("lowHz", LOW_HZ + run[0] * BIN_HZ).put("highHz", LOW_HZ + run[1] * BIN_HZ)
                        .put("level", Math.min(1, Math.sqrt(sum / (run[1] - run[0]) / maxMean))));
            }
            NoisePreset preset = NoisePreset.parse(new JSONObject().put("version", 1).put("name", name).put("bands", bands).toString());
            return new Result(preset, seconds(), keptEnergy / total, bandwidth, 20 * Math.log10(Math.max(peak, 1e-15)),
                    10 * Math.log10(Math.max(totalEnergy / Math.max(1, samples * channels), 1e-30)), frames, used, overScale);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}

