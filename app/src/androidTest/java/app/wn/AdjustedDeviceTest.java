package app.wn;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.media.AudioManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.wn.audio.MixerConfig;
import app.wn.audio.NoisePreset;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdjustedDeviceTest {
    private Context isolated() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="adjusted-test-"+java.util.UUID.randomUUID();
        return new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String ignored,int mode) {
                return base.getSharedPreferences(name,mode);
            }
        };
    }
    @Test public void freshInstallHasSecondAdjustedAndBothTypesPersistWithoutReseeding() {
        Context c=isolated();SettingsStore s=new SettingsStore(c);
        assertEquals(2,s.layers().size());assertEquals("default",s.layers().get(0).id);
        SettingsStore.Layer adjusted=s.layers().get(1);
        assertEquals("Adjusted",s.findProfile(adjusted.profileId).preset.name);
        assertNotNull(s.findProfile(adjusted.profileId).preset.motion);
        s.texture(adjusted.id,MixerConfig.Texture.OSCILLATING);
        SettingsStore reloaded=new SettingsStore(c);
        assertEquals(MixerConfig.Texture.OSCILLATING,reloaded.snapshot().generators.get(1).texture);
        assertNotNull(NoisePreset.parse(reloaded.findProfile(adjusted.profileId).preset.toJson()).motion);
        String other=reloaded.addGenerator(adjusted.profileId);
        assertEquals(MixerConfig.Texture.CONTINUOUS,reloaded.snapshot().generators.get(2).texture);
        reloaded.removeGenerator(adjusted.id);reloaded.removeGenerator(other);
        assertEquals(1,new SettingsStore(c).layers().size());
        assertNotNull(new SettingsStore(c).findProfile(adjusted.profileId));
    }
    @Test public void upgradePreservesExistingLayersLevelsAndProfiles() throws Exception {
        Context c=isolated();SharedPreferences prefs=c.getSharedPreferences("ignored",0);
        JSONArray catalogue=new JSONArray().put(new JSONObject().put("id","keep").put("profile",new JSONObject(NoisePreset.DEFAULT.toJson())));
        JSONArray layers=new JSONArray().put(new JSONObject().put("id","default").put("level",.62).put("enabled",false))
                .put(new JSONObject().put("id","custom").put("profileId","keep").put("level",.39).put("enabled",true));
        assertTrue(prefs.edit().putInt("schema",2).putString("catalogue",catalogue.toString()).putString("generators",layers.toString())
                .putFloat("master",.42f).putString("width","NARROW").commit());
        SettingsStore s=new SettingsStore(c);
        assertEquals(3,s.layers().size());assertEquals("adjusted",s.layers().get(1).id);
        assertEquals("custom",s.layers().get(2).id);assertEquals(.39,s.layers().get(2).level,.0001);
        assertFalse(s.layers().get(0).enabled);assertEquals(.62,s.layers().get(0).level,.0001);
        assertEquals(.42,s.snapshot().master,.0001);assertEquals(MixerConfig.Width.NARROW,s.snapshot().width);
        assertNotNull(s.findProfile("keep"));assertEquals(3,new SettingsStore(c).layers().size());
    }
    @Test public void fullMixerGetsSavedAdjustedWithoutLosingAnyGenerators() throws Exception {
        Context c=isolated();SharedPreferences prefs=c.getSharedPreferences("ignored",0);
        JSONArray catalogue=new JSONArray().put(new JSONObject().put("id","keep").put("profile",new JSONObject(NoisePreset.DEFAULT.toJson())));
        JSONArray layers=new JSONArray().put(new JSONObject().put("id","default"));
        for(int i=1;i<8;i++)layers.put(new JSONObject().put("id","g"+i).put("profileId","keep"));
        assertTrue(prefs.edit().putInt("schema",2).putString("catalogue",catalogue.toString()).putString("generators",layers.toString()).commit());
        SettingsStore s=new SettingsStore(c);assertEquals(8,s.layers().size());
        assertNotNull(s.findProfile("builtin-adjusted-v1"));
        for(int i=1;i<8;i++)assertEquals("g"+i,s.layers().get(i).id);
    }
    @Test public void bothTypesPlayTogetherInBackgroundAndTypeControlIsVisible() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SettingsStore s=SettingsStore.get(c);
        for(SettingsStore.Layer layer:s.layers())if(!"default".equals(layer.id))s.removeGenerator(layer.id);
        String profile;
        try(java.io.InputStream input=c.getAssets().open("adjusted.json")){profile=s.addProfile(ProfileFiles.read(input));}
        String continuous=s.addGenerator(profile),oscillating=s.addGenerator(profile);
        s.texture(oscillating,MixerConfig.Texture.OSCILLATING);s.output(.25,MixerConfig.Width.DEFAULT);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                java.util.ArrayList<android.view.View> controls=new java.util.ArrayList<>();
                a.getWindow().getDecorView().findViewsWithText(controls,"Type: Oscillating noise + tone",android.view.View.FIND_VIEWS_WITH_TEXT);
                assertFalse(controls.isEmpty());
                a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START));
            });
            long deadline=android.os.SystemClock.elapsedRealtime()+10000;
            while(!PlaybackStatus.playing && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            assertTrue(PlaybackStatus.playing);
            scenario.onActivity(a->a.moveTaskToBack(true));Thread.sleep(2000);
            assertTrue(PlaybackStatus.playing);assertTrue(c.getSystemService(AudioManager.class).isMusicActive());
            assertEquals(MixerConfig.Texture.OSCILLATING,s.snapshot().generators.get(2).texture);
        } finally {
            s.setWantsPlayback(false);c.stopService(new Intent(c,NoiseService.class));
            long deadline=android.os.SystemClock.elapsedRealtime()+8000;
            while(PlaybackStatus.active && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            s.removeGenerator(continuous);s.removeGenerator(oscillating);
        }
        assertFalse(PlaybackStatus.active);
    }
}
