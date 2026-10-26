package app.wn.audio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Low-rate pitch and energy controls, never a recording or speech waveform. */
public final class MotionShape {
    public final double stepSeconds;
    public final int size;
    private final double[] pitch, energy;
    private MotionShape(double stepSeconds, double[] pitch, double[] energy) {
        this.stepSeconds = stepSeconds; this.pitch = pitch; this.energy = energy; size = pitch.length;
    }
    static MotionShape parse(JSONObject object) throws JSONException {
        double step = finite(object.get("stepMs"));
        JSONArray pitches = object.getJSONArray("pitchHz"), energies = object.getJSONArray("energy");
        int count = pitches.length();
        if (step < 20 || step > 1000 || count < 2 || count > 2048 || energies.length() != count)
            throw new IllegalArgumentException("Motion needs 2–2048 matching points and a 20–1000 ms step.");
        double[] p = new double[count], e = new double[count];
        for (int i = 0; i < count; i++) {
            p[i] = finite(pitches.get(i)); e[i] = finite(energies.get(i));
            if (p[i] < 60 || p[i] > 1000 || e[i] < 0 || e[i] > 1)
                throw new IllegalArgumentException("Motion pitch must be 60–1000 Hz and energy 0–1.");
        }
        return new MotionShape(step / 1000, p, e);
    }
    private static double finite(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
            throw new IllegalArgumentException("Motion values must be finite numbers.");
        return ((Number) value).doubleValue();
    }
    public double pitchAt(double seconds) { return sample(pitch, seconds); }
    public double energyAt(double seconds) { return sample(energy, seconds); }
    private double sample(double[] data, double seconds) {
        double index = (seconds / stepSeconds) % size;
        if (index < 0) index += size;
        int a = (int) index, b = (a + 1) % size;
        return data[a] + (data[b] - data[a]) * (index - a);
    }
    JSONObject toJson() throws JSONException {
        JSONArray p = new JSONArray(), e = new JSONArray();
        for (int i = 0; i < size; i++) { p.put(pitch[i]); e.put(energy[i]); }
        return new JSONObject().put("stepMs", stepSeconds * 1000).put("pitchHz", p).put("energy", e);
    }
}
