package app.wn.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Immutable mix of independently seeded noise generators. */
public final class MixerConfig {
    public static final int MAX_GENERATORS = 8;
    public enum Texture { CONTINUOUS, OSCILLATING }
    public enum Width {
        MONO(0), NARROW(.4), DEFAULT(1), WIDE(1.5);
        public final double side;
        Width(double side) { this.side = side; }
    }
    public static final class Generator {
        public final String id;
        public final NoisePreset preset;
        public final boolean enabled;
        public final double level;
        public final Texture texture;
        public final Oscillation oscillation;
        public Generator(String id, NoisePreset preset, boolean enabled, double level) {
            this(id, preset, enabled, level, Texture.CONTINUOUS);
        }
        public Generator(String id, NoisePreset preset, boolean enabled, double level, Texture texture) {
            this(id,preset,enabled,level,texture,Oscillation.DEFAULT);
        }
        public Generator(String id, NoisePreset preset, boolean enabled, double level, Texture texture,Oscillation oscillation) {
            if (id == null || id.isEmpty() || preset == null) throw new IllegalArgumentException("A generator needs an ID and profile.");
            this.id = id; this.preset = preset; this.enabled = enabled; this.level = clamp(level);
            this.texture = preset.motion == null || texture == null ? Texture.CONTINUOUS : texture;
            this.oscillation=oscillation==null?Oscillation.DEFAULT:oscillation;
        }
        public boolean hasSignal(){return texture==Texture.CONTINUOUS||oscillation.tone>0||oscillation.noise>0;}
    }
    public final List<Generator> generators;
    public final double master;
    public final Width width;
    public MixerConfig(List<Generator> generators, double master, Width width) {
        if (generators == null || generators.isEmpty() || generators.size() > MAX_GENERATORS
                || !"default".equals(generators.get(0).id))
            throw new IllegalArgumentException("Keep Default and use at most " + MAX_GENERATORS + " generators.");
        HashSet<String> ids = new HashSet<>();
        for (Generator g : generators) if (!ids.add(g.id)) throw new IllegalArgumentException("Duplicate generator ID.");
        this.generators = Collections.unmodifiableList(new ArrayList<>(generators));
        this.master = clamp(master); this.width = width == null ? Width.DEFAULT : width;
    }
    public static MixerConfig defaults() {
        return new MixerConfig(Collections.singletonList(new Generator("default", NoisePreset.DEFAULT, true, .55)), .25, Width.DEFAULT);
    }
    public boolean hasAudibleLayer() {
        if (master <= 0) return false;
        for (Generator g : generators) if (g.enabled && g.level > 0 && g.hasSignal()) return true;
        return false;
    }
    public int enabledCount() { int n = 0; for (Generator g : generators) if (g.enabled && g.level > 0 && g.hasSignal()) n++; return n; }
    public static double clamp(double v) { return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0; }
}
