package app.wn;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import app.wn.audio.MixerConfig;
import app.wn.audio.NoisePreset;
import app.wn.audio.Oscillation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable profile catalogue and mixer scene. Profile changes are committed before success is reported. */
public final class SettingsStore {
    public static final int MAX_PROFILES = 128;
    public static final class SavedProfile {
        public final String id;
        public final NoisePreset preset;
        SavedProfile(String id, NoisePreset preset) { this.id = id; this.preset = preset; }
    }
    public static final class Layer {
        public final String id, profileId;
        public final boolean enabled;
        public final double level;
        public final MixerConfig.Texture texture;
        public final Oscillation oscillation;
        public final Oscillation voiceControls;
        Layer(String id, String profileId, boolean enabled, double level) {
            this(id, profileId, enabled, level, MixerConfig.Texture.CONTINUOUS);
        }
        Layer(String id, String profileId, boolean enabled, double level, MixerConfig.Texture texture) {
            this(id,profileId,enabled,level,texture,Oscillation.DEFAULT);
        }
        Layer(String id, String profileId, boolean enabled, double level, MixerConfig.Texture texture,Oscillation oscillation) {
            this(id,profileId,enabled,level,texture,oscillation,null);
        }
        Layer(String id,String profileId,boolean enabled,double level,MixerConfig.Texture texture,Oscillation oscillation,Oscillation voiceControls) {
            this.id = id; this.profileId = profileId; this.enabled = enabled; this.level = MixerConfig.clamp(level);
            this.texture = texture;
            this.oscillation=oscillation;
            this.voiceControls=voiceControls;
        }
        public Oscillation controls(){return texture==MixerConfig.Texture.VOICE&&voiceControls!=null?voiceControls:oscillation;}
    }
    private static SettingsStore instance;
    private final SharedPreferences prefs;
    private List<SavedProfile> profiles = new ArrayList<>();
    private List<Layer> layers = new ArrayList<>();
    private volatile MixerConfig current;
    private volatile long revision;
    private double master;
    private MixerConfig.Width width;
    private boolean adjustedInstalled;
    private boolean neuralInstalled;
    private boolean voiceInstalled;

    public static synchronized SettingsStore get(Context context) {
        if (instance == null) instance = new SettingsStore(context.getApplicationContext());
        return instance;
    }
    SettingsStore(Context context) {
        prefs = context.getSharedPreferences("noise_settings", Context.MODE_PRIVATE);
        adjustedInstalled = prefs.getBoolean("adjusted_sample_v1", false);
        neuralInstalled=prefs.getBoolean("adjusted_neural_v1",false);
        voiceInstalled=prefs.getBoolean("adjusted_voice_v1",false);
        master = prefs.getFloat("master", .25f);
        try { width = MixerConfig.Width.valueOf(prefs.getString("width", "DEFAULT")); }
        catch (IllegalArgumentException e) { width = MixerConfig.Width.DEFAULT; }
        if (prefs.getInt("schema", 1) < 2) {
            layers.add(new Layer("default", null, prefs.getBoolean("default_enabled", true), prefs.getFloat("default_level", .55f)));
            String old = prefs.getString("profile", null);
            if (old != null) try {
                SavedProfile p = new SavedProfile(UUID.randomUUID().toString(), NoisePreset.parse(old));
                profiles.add(p);
                layers.add(new Layer(UUID.randomUUID().toString(), p.id, prefs.getBoolean("adjusted_enabled", false), prefs.getFloat("adjusted_level", .35f)));
            } catch (IllegalArgumentException e) { PlaybackStatus.error = "Legacy profile could not be read. Import it again."; }
            persist(true);
        } else {
            try {
                JSONArray catalogue = new JSONArray(prefs.getString("catalogue", "[]"));
                for (int i = 0; i < Math.min(catalogue.length(), MAX_PROFILES); i++) {
                    JSONObject p = catalogue.getJSONObject(i);
                    profiles.add(new SavedProfile(p.getString("id"), NoisePreset.parse(p.getJSONObject("profile").toString())));
                }
                JSONArray scene = new JSONArray(prefs.getString("generators", "[]"));
                for (int i = 0; i < Math.min(scene.length(), MixerConfig.MAX_GENERATORS); i++) {
                    JSONObject g = scene.getJSONObject(i);
                    String id = g.getString("id"), profile = g.optString("profileId", "");
                    if ("default".equals(id) || findProfile(profile) != null)
                        layers.add(new Layer(id, "default".equals(id) ? null : profile, g.optBoolean("enabled", true), g.optDouble("level", .5),
                                textureValue(g.optString("texture")),Oscillation.parse(g.optJSONObject("oscillation")),
                                g.has("voiceControls")?Oscillation.parse(g.optJSONObject("voiceControls")):null));
                }
            } catch (JSONException | IllegalArgumentException e) {
                PlaybackStatus.error = "Some saved settings could not be read.";
            }
            Layer defaultLayer = null;
            for (Layer l : layers) if ("default".equals(l.id)) { defaultLayer = l; break; }
            layers.removeIf(l -> "default".equals(l.id));
            layers.add(0, defaultLayer == null ? new Layer("default", null, true, .55) : defaultLayer);
            while (layers.size() > MixerConfig.MAX_GENERATORS) layers.remove(layers.size() - 1);
        }
        installAdjusted(context);
        installNeural(context);
        installVoice(context);
        boolean initialized=false;
        for(int i=0;i<layers.size();i++){Layer l=layers.get(i);SavedProfile p=findProfile(l.profileId);MixerConfig.Texture type=l.texture;
            if(p==null||p.preset.motion==null||(type==MixerConfig.Texture.VOICE&&p.preset.voice==null))type=MixerConfig.Texture.CONTINUOUS;
            if(l.voiceControls==null||type!=l.texture){layers.set(i,new Layer(l.id,l.profileId,l.enabled,l.level,type,l.oscillation,
                    l.voiceControls==null?defaultVoice(l.profileId):l.voiceControls));initialized=true;}}
        if(initialized)persist(false);
        rebuild();
    }
    private static MixerConfig.Texture textureValue(String value) {
        try{return MixerConfig.Texture.valueOf(value);}catch(IllegalArgumentException e){return MixerConfig.Texture.CONTINUOUS;}
    }
    private Oscillation defaultVoice(String profileId){SavedProfile p=findProfile(profileId);return p!=null&&p.preset.voice!=null?p.preset.voice.defaults():Oscillation.DEFAULT;}
    private void installVoice(Context context) {
        if(voiceInstalled)return;SavedProfile old=findProfile("builtin-adjusted-v1");
        if(old!=null)try(java.io.InputStream input=context.getAssets().open("adjusted.json")) {
            NoisePreset bundled=ProfileFiles.read(input);JSONObject json=new JSONObject(old.preset.toJson());
            JSONObject voice=new JSONObject(bundled.toJson()).optJSONObject("voice");
            if(!json.has("voice")&&voice!=null){json.put("voice",voice);profiles.set(profiles.indexOf(old),new SavedProfile(old.id,NoisePreset.parse(json.toString())));}
        }catch(java.io.IOException|JSONException e){throw new IllegalStateException("Could not install voice model.",e);}
        voiceInstalled=true;persist(true);
    }
    private void installNeural(Context context) {
        if(neuralInstalled)return;
        SavedProfile old=findProfile("builtin-adjusted-v1");
        if(old!=null)try(java.io.InputStream input=context.getAssets().open("adjusted.json")) {
            NoisePreset bundled=ProfileFiles.read(input);
            JSONObject json=new JSONObject(old.preset.toJson()),motion=json.optJSONObject("motion");
            JSONObject model=new JSONObject(bundled.toJson()).getJSONObject("motion").optJSONObject("model");
            if(motion!=null && !motion.has("model") && model!=null) {
                motion.put("model",model);
                profiles.set(profiles.indexOf(old),new SavedProfile(old.id,NoisePreset.parse(json.toString())));
            }
        }catch(java.io.IOException|JSONException e){throw new IllegalStateException("Could not install the motion model.",e);}
        neuralInstalled=true;persist(true);
    }
    private void installAdjusted(Context context) {
        if (adjustedInstalled || profiles.size() >= MAX_PROFILES) return;
        try (java.io.InputStream input = context.getAssets().open("adjusted.json")) {
            SavedProfile profile = new SavedProfile("builtin-adjusted-v1", ProfileFiles.read(input));
            profiles.add(0, profile);
            if (layers.size() < MixerConfig.MAX_GENERATORS)
                layers.add(1, new Layer("adjusted", profile.id, true, .35));
            adjustedInstalled = true;
            persist(true);
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not load Adjusted.", e); }
    }
    public MixerConfig snapshot() { return current; }
    public long revision() { return revision; }
    public synchronized List<SavedProfile> profiles() { return Collections.unmodifiableList(new ArrayList<>(profiles)); }
    public synchronized List<Layer> layers() { return Collections.unmodifiableList(new ArrayList<>(layers)); }
    public synchronized SavedProfile findProfile(String id) {
        for (SavedProfile p : profiles) if (p.id.equals(id)) return p;
        return null;
    }
    private void rebuild() {
        ArrayList<MixerConfig.Generator> generators = new ArrayList<>();
        for (Layer l : layers) {
            SavedProfile p = l.profileId == null ? null : findProfile(l.profileId);
            if (l.profileId == null || p != null)
                generators.add(new MixerConfig.Generator(l.id, p == null ? NoisePreset.DEFAULT : p.preset, l.enabled, l.level, l.texture,l.oscillation,l.voiceControls));
        }
        current = new MixerConfig(generators, master, width);
        revision++;
    }
    private JSONObject layerJson(Layer l) throws JSONException {
        return new JSONObject().put("id", l.id).put("profileId", l.profileId == null ? "" : l.profileId)
                .put("enabled", l.enabled).put("level", l.level).put("texture", l.texture.name()).put("oscillation",l.oscillation.toJson())
                .put("voiceControls",l.voiceControls==null?null:l.voiceControls.toJson());
    }
    private void persist(boolean commit) {
        persist(commit,commit);
    }
    private void persistScene(boolean commit){persist(commit,false);}
    @SuppressLint("ApplySharedPref")
    private void persist(boolean commit,boolean catalogueChanged) {
        try {
            JSONArray catalogue = new JSONArray(), scene = new JSONArray();
            if (catalogueChanged) for (SavedProfile p : profiles) catalogue.put(new JSONObject().put("id", p.id).put("profile", new JSONObject(p.preset.toJson())));
            for (Layer l : layers) scene.put(layerJson(l));
            SharedPreferences.Editor edit = prefs.edit().putInt("schema", 2)
                    .putBoolean("adjusted_sample_v1", adjustedInstalled)
                    .putBoolean("adjusted_neural_v1",neuralInstalled)
                    .putBoolean("adjusted_voice_v1",voiceInstalled)
                    .putString("generators", scene.toString()).putFloat("master", (float) master).putString("width", width.name());
            if(catalogueChanged)edit.putString("catalogue",catalogue.toString());
            if (commit) {
                if (!edit.commit()) throw new IllegalStateException("Could not save settings. Check free storage.");
            } else edit.apply();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private boolean nameExists(String name) {
        for (SavedProfile p : profiles) if (p.preset.name.equals(name)) return true;
        return false;
    }
    public synchronized String addProfile(NoisePreset preset) {
        if (profiles.size() >= MAX_PROFILES) throw new IllegalStateException("Profile limit reached (128). Delete an unused profile first.");
        String base = preset.name, chosen = base;
        int suffix = 2;
        while (nameExists(chosen)) {
            String ending = " (" + suffix++ + ")";
            chosen = base.substring(0, Math.min(base.length(), 80 - ending.length())) + ending;
        }
        if (!chosen.equals(base)) {
            try { preset = NoisePreset.parse(new JSONObject(preset.toJson()).put("name", chosen).toString()); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
        String id = UUID.randomUUID().toString();
        profiles.add(0, new SavedProfile(id, preset));
        try { persist(true); } catch (RuntimeException e) { profiles.remove(0); throw e; }
        rebuild(); return id;
    }
    public synchronized void renameProfile(String id, String name) {
        SavedProfile old = findProfile(id);
        if (old == null) throw new IllegalArgumentException("Profile no longer exists.");
        try {
            JSONObject json = new JSONObject(old.preset.toJson()).put("name", name);
            NoisePreset renamed = NoisePreset.parse(json.toString());
            int index = profiles.indexOf(old); profiles.set(index, new SavedProfile(id, renamed));
            try { persist(true); } catch (RuntimeException e) { profiles.set(index, old); throw e; }
            rebuild();
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    public synchronized void deleteProfile(String id) {
        for (Layer l : layers) if (id.equals(l.profileId)) throw new IllegalStateException("Remove generators using this profile before deleting it.");
        SavedProfile old = findProfile(id);
        if (old == null) return;
        int index = profiles.indexOf(old); profiles.remove(index);
        try { persist(true); } catch (RuntimeException e) { profiles.add(index, old); throw e; }
        rebuild();
    }
    public synchronized String addGenerator(String profileId) {
        if (layers.size() >= MixerConfig.MAX_GENERATORS) throw new IllegalStateException("Maximum 8 generators, including Default.");
        if (findProfile(profileId) == null) throw new IllegalArgumentException("Choose a saved profile.");
        String id = UUID.randomUUID().toString(); layers.add(new Layer(id, profileId, true, .35,MixerConfig.Texture.CONTINUOUS,Oscillation.DEFAULT,defaultVoice(profileId)));
        try { persistScene(true); } catch (RuntimeException e) { layers.remove(layers.size() - 1); throw e; }
        rebuild(); return id;
    }
    public synchronized void removeGenerator(String id) {
        if ("default".equals(id)) throw new IllegalArgumentException("Default cannot be removed.");
        ArrayList<Layer> old = new ArrayList<>(layers);
        layers.removeIf(l -> l.id.equals(id));
        try { persistScene(true); } catch (RuntimeException e) { layers = old; throw e; }
        rebuild();
    }
    public synchronized void updateGenerator(String id, Boolean enabled, Double level, String profileId) {
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (!l.id.equals(id)) continue;
            String p = profileId == null ? l.profileId : profileId;
            if ("default".equals(id)) p = null;
            else if (findProfile(p) == null) throw new IllegalArgumentException("Profile no longer exists.");
            MixerConfig.Texture texture = p == null || findProfile(p).preset.motion == null ? MixerConfig.Texture.CONTINUOUS : l.texture;
            if(texture==MixerConfig.Texture.VOICE&&findProfile(p).preset.voice==null)texture=MixerConfig.Texture.CONTINUOUS;
            layers.set(i, new Layer(id, p, enabled == null ? l.enabled : enabled, level == null ? l.level : level, texture,l.oscillation,l.voiceControls));
            persist(false); rebuild(); return;
        }
    }
    public synchronized void texture(String id, MixerConfig.Texture texture) {
        for (int i = 0; i < layers.size(); i++) {
            Layer old = layers.get(i);
            if (!old.id.equals(id)) continue;
            SavedProfile profile = findProfile(old.profileId);
            if (profile == null || profile.preset.motion == null) throw new IllegalArgumentException("This profile has no motion data.");
            if (texture == null) throw new IllegalArgumentException("Choose a generator type.");
            if(texture==MixerConfig.Texture.VOICE&&profile.preset.voice==null)throw new IllegalArgumentException("Import a voice-trained profile first.");
            layers.set(i, new Layer(id, old.profileId, old.enabled, old.level, texture,old.oscillation,old.voiceControls));
            try { persistScene(true); } catch (RuntimeException e) { layers.set(i, old); throw e; }
            rebuild(); return;
        }
    }
    public synchronized Layer layer(String id) {for(Layer l:layers)if(l.id.equals(id))return l;return null;}
    public synchronized void oscillation(String id,int field,double value) {
        Layer old=layer(id);if(old==null)return;
        setOscillation(old,old.controls().with(field,value),false);
    }
    public synchronized void resetOscillation(String id) {
        Layer old=layer(id);if(old!=null)setOscillation(old,old.texture==MixerConfig.Texture.VOICE?defaultVoice(old.profileId):Oscillation.DEFAULT,true);
    }
    public synchronized void voiceBlend(String id,boolean speech) {
        Layer old=layer(id);if(old==null||old.texture!=MixerConfig.Texture.VOICE)return;
        SavedProfile p=findProfile(old.profileId);if(p==null||p.preset.voice==null)return;
        Oscillation target=speech?p.preset.voice.speechDefaults():p.preset.voice.defaults();
        setOscillation(old,old.controls().with(0,target.tone).with(1,target.noise),true);
    }
    private void setOscillation(Layer old,Oscillation value,boolean commit) {
        int index=layers.indexOf(old);
        layers.set(index,new Layer(old.id,old.profileId,old.enabled,old.level,old.texture,
                old.texture==MixerConfig.Texture.VOICE?old.oscillation:value,old.texture==MixerConfig.Texture.VOICE?value:old.voiceControls));
        try{persistScene(commit);}catch(RuntimeException e){layers.set(index,old);throw e;}
        rebuild();
    }
    public synchronized void saveControls(){persistScene(true);}
    public synchronized void output(double level, MixerConfig.Width value) {
        master = MixerConfig.clamp(level); width = value == null ? MixerConfig.Width.DEFAULT : value;
        persist(false); rebuild();
    }
    public boolean wantsPlayback() { return prefs.getBoolean("play_requested", false); }
    @SuppressLint("ApplySharedPref")
    public void setWantsPlayback(boolean wanted) { prefs.edit().putBoolean("play_requested", wanted).commit(); }
}
