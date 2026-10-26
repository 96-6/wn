package app.wn.audio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Arrays;

/** Immutable, validated spectral amplitude profile. Frequencies are in Hz. */
public final class NoisePreset {
    private static final double[] CENTERS = {31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final double[] REFERENCE_LEVELS = {.17, .29, .38, .56, .65, .59, .38, .28, .16, .04};
    public static final NoisePreset DEFAULT = new NoisePreset("Default · reference curve", REFERENCE_LEVELS, null);
    public static final int MAX_JSON_BYTES = 262144;

    public static final class Band {
        public final double lowHz, highHz, level;
        public Band(double lowHz, double highHz, double level) {
            this.lowHz = lowHz; this.highHz = highHz; this.level = level;
        }
    }

    public final String name;
    private final double[] levels;
    private final Band[] bands;
    public final MotionShape motion;
    public final VoiceModel voice;

    private NoisePreset(String name, double[] levels, Band[] bands) {
        this(name, levels, bands, null);
    }
    private NoisePreset(String name, double[] levels, Band[] bands, MotionShape motion) {
        this(name,levels,bands,motion,null);
    }
    private NoisePreset(String name,double[] levels,Band[] bands,MotionShape motion,VoiceModel voice) {
        this.name = name;
        this.levels = levels == null ? null : levels.clone();
        this.bands = bands == null ? null : bands.clone();
        this.motion = motion;
        this.voice=voice;
    }

    public static NoisePreset parse(String json) throws IllegalArgumentException {
        if (json == null || json.length() > MAX_JSON_BYTES) fail("Profile is too large (maximum 256 KiB).");
        try {
            JSONTokener tokener = new JSONTokener(json.charAt(0) == '\ufeff' ? json.substring(1) : json);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONObject) || tokener.nextClean() != 0) fail("Use a single JSON object.");
            JSONObject o = (JSONObject) root;
            MotionShape motion = o.has("motion") ? MotionShape.parse(o.getJSONObject("motion")) : null;
            VoiceModel voice=o.has("voice")?VoiceModel.parse(o.getJSONObject("voice")):null;
            if(voice!=null&&motion==null)fail("Voice model needs motion data.");
            if (number(o, "version") != 1) fail("Profile version must be 1.");
            Object rawName = o.opt("name");
            if (!(rawName instanceof String)) fail("A profile name is required.");
            String name = ((String) rawName).trim();
            if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl))
                fail("Name must contain 1–80 readable characters.");
            boolean octave = o.has("levels"), explicit = o.has("bands");
            if (octave == explicit) fail("Provide either 'levels' or 'bands', not both.");
            if (octave) {
                JSONArray a = o.getJSONArray("levels");
                if (a.length() != 10) fail("The levels array must contain exactly 10 numbers.");
                double[] levels = new double[10];
                for (int i = 0; i < 10; i++) levels[i] = unit(a.get(i), "levels[" + i + "]");
                if (Arrays.stream(levels).allMatch(v -> v == 0)) fail("At least one level must be above zero.");
                return new NoisePreset(name, levels, null, motion,voice);
            }
            JSONArray a = o.getJSONArray("bands");
            if (a.length() < 1 || a.length() > 64) fail("Use between 1 and 64 frequency bands.");
            Band[] bands = new Band[a.length()];
            double previousHigh = 0;
            boolean audible = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.getJSONObject(i);
                double low = number(b, "lowHz"), high = number(b, "highHz");
                if (low < 20 || high > 22000 || high - low < 20)
                    fail("Band " + (i + 1) + ": use 20–22000 Hz and a width of at least 20 Hz.");
                if (low < previousHigh) fail("Bands must be sorted and must not overlap.");
                if (b.has("level") == b.has("gainDb")) fail("Each band needs either 'level' or 'gainDb'.");
                double level;
                if (b.has("level")) level = unit(b.get("level"), "level");
                else {
                    double db = number(b, "gainDb");
                    if (db < -60 || db > 0) fail("gainDb must be between -60 and 0.");
                    level = Math.pow(10, db / 20);
                }
                audible |= level > 0;
                bands[i] = new Band(low, high, level);
                previousHigh = high;
            }
            if (!audible) fail("At least one band must be above zero.");
            return new NoisePreset(name, null, bands, motion,voice);
        } catch (JSONException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid JSON profile: " + e.getMessage(), e);
        }
    }

    private static double number(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
            fail(key + " must be a finite number.");
        return ((Number) value).doubleValue();
    }

    private static double unit(Object value, String key) {
        if (!(value instanceof Number)) fail(key + " must be a number from 0 to 1.");
        double v = ((Number) value).doubleValue();
        if (!Double.isFinite(v) || v < 0 || v > 1) fail(key + " must be between 0 and 1.");
        return v;
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    public double gainAt(double hz) {
        if (hz < 20 || hz > 22000) return 0;
        if (levels != null) {
            if (hz <= CENTERS[0]) return levels[0];
            for (int i = 1; i < 10; i++) {
                if (hz <= CENTERS[i]) {
                    double t = Math.log(hz / CENTERS[i - 1]) / Math.log(CENTERS[i] / CENTERS[i - 1]);
                    return levels[i - 1] * (1 - t) + levels[i] * t;
                }
            }
            return levels[9] * Math.max(0, (22000 - hz) / 6000);
        }
        for (int i = 0; i < bands.length; i++) {
            Band b = bands[i];
            if (hz < b.lowHz) return 0;
            if (hz <= b.highHz) {
                double edge = Math.min(25, (b.highHz - b.lowHz) * .1);
                boolean joinsLeft = i > 0 && bands[i-1].highHz == b.lowHz && bands[i-1].level > 0;
                boolean joinsRight = i+1 < bands.length && bands[i+1].lowHz == b.highHz && bands[i+1].level > 0;
                double t = Math.min(1, Math.min(joinsLeft ? 1 : (hz - b.lowHz) / edge,
                        joinsRight ? 1 : (b.highHz - hz) / edge));
                return b.level * (.5 - .5 * Math.cos(Math.PI * t));
            }
        }
        return 0;
    }

    public boolean isOctaveProfile() { return levels != null; }
    public double[] octaveLevels() { return levels == null ? null : levels.clone(); }
    public Band[] frequencyBands() { return bands == null ? new Band[0] : bands.clone(); }

    public String toJson() {
        try {
            JSONObject o = new JSONObject().put("version", 1).put("name", name);
            if (motion != null) o.put("motion", motion.toJson());
            if(voice!=null)o.put("voice",voice.toJson());
            JSONArray a = new JSONArray();
            if (levels != null) {
                for (double level : levels) a.put(level);
                o.put("levels", a);
            } else {
                for (Band b : bands) a.put(new JSONObject().put("lowHz", b.lowHz)
                        .put("highHz", b.highHz).put("level", b.level));
                o.put("bands", a);
            }
            return o.toString(2);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
