package app.wn.audio;

/** Procedural detuned harmonic banks following measured pitch/energy control curves. */
final class MotionOscillator {
    private static final int MAX_HARMONICS = 96;
    private static final double TAU = 2 * Math.PI, INV_ROOT_TWO = 1 / Math.sqrt(2);
    private final double[] weights = new double[MAX_HARMONICS + 1];
    private final double[] targets = new double[MAX_HARMONICS + 1];
    private final double offset, detune;
    private double clock, phaseA, phaseB, pitch = 0, energy = 0;
    private NoisePreset previous;
    private double targetRms, maxFrequency, scaleState;
    MotionOscillator(long seed) {
        offset = ((seed >>> 11) * 0x1.0p-53) * 7;
        detune = .985 + (((seed ^ 0x1872369L) >>> 11) * 0x1.0p-53) * .03;
        phaseA = offset % TAU; phaseB = (offset * 1.618 + 1) % TAU;
    }
    void render(NoisePreset profile, double[] mid, double[] side, double[] pulse) {
        MotionShape motion = profile.motion;
        if (motion == null) throw new IllegalArgumentException("Oscillating texture needs a motion profile.");
        if (profile != previous) {
            double squares = 0;
            for (int k = 1; k < NoiseMixer.FFT_SIZE/2; k++) {
                double g = profile.gainAt(k * (double) NoiseMixer.SAMPLE_RATE / NoiseMixer.FFT_SIZE);
                squares += g*g;
                if (g > 0) maxFrequency = (k+1) * (double) NoiseMixer.SAMPLE_RATE / NoiseMixer.FFT_SIZE;
            }
            targetRms = Math.sqrt(2 * squares / NoiseMixer.FFT_SIZE);
            previous = profile;
        }
        double seconds = clock + offset, span = mid.length / (double) NoiseMixer.SAMPLE_RATE;
        double a = motion.pitchAt(seconds) * detune, b = motion.pitchAt(seconds + span) * detune;
        double ea = motion.energyAt(seconds), eb = motion.energyAt(seconds + span);
        double center = (a+b) * .5;
        int harmonics = Math.min(MAX_HARMONICS, Math.min((int) Math.ceil(maxFrequency / Math.min(a,b)) + 1,
                (int) (21900 / Math.max(a,b))));
        double sum = 0;
        for (int h = 1; h <= harmonics; h++) {
            targets[h] = profile.gainAt(center * h);
            sum += targets[h] * targets[h];
        }
        double scale = sum > 1e-12 ? targetRms / Math.sqrt(sum * .5) : 0;
        if (pitch == 0) { pitch = a; energy = ea; }
        for (int i = 0; i < mid.length; i++) {
            double fraction = i / (double) mid.length;
            pitch += ((a + (b-a)*fraction) - pitch) * .001;
            energy += ((ea + (eb-ea)*fraction) - energy) * .001;
            scaleState += (scale - scaleState) * .001;
            phaseA += TAU * pitch / NoiseMixer.SAMPLE_RATE;
            phaseB += TAU * pitch * 1.009 / NoiseMixer.SAMPLE_RATE;
            if (phaseA >= TAU) phaseA -= TAU;
            if (phaseB >= TAU) phaseB -= TAU;
            double sinA = Math.sin(phaseA), sinB = Math.sin(phaseB);
            double twiceA = 2*Math.cos(phaseA), twiceB = 2*Math.cos(phaseB);
            double lastA = 0, lastB = 0, currentA = sinA, currentB = sinB, toneA = 0, toneB = 0;
            for (int h = 1; h <= harmonics; h++) {
                weights[h] += (targets[h] - weights[h]) * .001;
                toneA += weights[h] * currentA; toneB += weights[h] * currentB;
                double nextA = twiceA * currentA - lastA, nextB = twiceB * currentB - lastB;
                lastA = currentA; lastB = currentB; currentA = nextA; currentB = nextB;
            }
            double loudness = scaleState * (.3 + .7 * energy);
            mid[i] = (toneA + toneB) * INV_ROOT_TWO * loudness;
            side[i] = (toneA - toneB) * INV_ROOT_TWO * loudness;
            pulse[i] = .72 + .28 * energy;
        }
        clock += span;
    }
}
