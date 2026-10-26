package app.wn;

import android.content.*;
import android.net.Uri;
import android.view.View;
import android.view.KeyEvent;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.wn.audio.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class VoiceMaskerDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Context isolated(){String name="voice-test-"+UUID.randomUUID();return new ContextWrapper(context){
        @Override public SharedPreferences getSharedPreferences(String ignored,int mode){return context.getSharedPreferences(name,mode);}
    };}
    private NoisePreset bundled() throws Exception {try(java.io.InputStream in=context.getAssets().open("adjusted.json")){return ProfileFiles.read(in);}}
    @After public void stop() throws Exception {PreviewController.stop();SettingsStore.get(context).setWantsPlayback(false);context.stopService(new Intent(context,NoiseService.class));waitFor(()->!PlaybackStatus.active,8000);}
    @Test public void upgradeAddsVocalModelWithoutChangingExistingSettingsOrNames() throws Exception {
        Context c=isolated();JSONObject old=new JSONObject(bundled().toJson()).put("name","My voice profile");old.remove("voice");
        JSONArray profiles=new JSONArray().put(new JSONObject().put("id","builtin-adjusted-v1").put("profile",old));
        Oscillation controls=new Oscillation(.42,.67,1.8,.6,-2,1.4);
        JSONArray layers=new JSONArray().put(new JSONObject().put("id","default").put("enabled",false).put("level",.45))
                .put(new JSONObject().put("id","adjusted").put("profileId","builtin-adjusted-v1").put("texture","OSCILLATING").put("level",.38).put("oscillation",controls.toJson()));
        assertTrue(c.getSharedPreferences("x",0).edit().putInt("schema",2).putBoolean("adjusted_sample_v1",true).putBoolean("adjusted_neural_v1",true)
                .putString("catalogue",profiles.toString()).putString("generators",layers.toString()).putFloat("master",.31f).putString("width","NARROW").commit());
        SettingsStore s=new SettingsStore(c);assertEquals("My voice profile",s.findProfile("builtin-adjusted-v1").preset.name);assertNotNull(s.findProfile("builtin-adjusted-v1").preset.voice);
        assertEquals(.42,s.layer("adjusted").oscillation.tone,0);assertEquals(.38,s.layer("adjusted").level,0);assertEquals(.31,s.snapshot().master,.0001);
        assertEquals(MixerConfig.Width.NARROW,s.snapshot().width);assertFalse(s.layer("default").enabled);
        s.texture("adjusted",MixerConfig.Texture.VOICE);assertEquals(0,s.layer("adjusted").controls().tone,0);
        s.voiceBlend("adjusted",true);assertTrue(s.layer("adjusted").controls().tone>0);
        s.oscillation("adjusted",6,2);s.saveControls();SettingsStore reloaded=new SettingsStore(c);
        assertEquals(MixerConfig.Texture.VOICE,reloaded.layer("adjusted").texture);assertEquals(2,reloaded.layer("adjusted").controls().voices);
        reloaded.resetOscillation("adjusted");assertEquals(0,reloaded.layer("adjusted").controls().tone,0);
        reloaded.texture("adjusted",MixerConfig.Texture.OSCILLATING);assertEquals(.42,reloaded.layer("adjusted").controls().tone,0);
    }
    private String generator() throws Exception {
        SettingsStore s=SettingsStore.get(context);for(SettingsStore.Layer l:s.layers())if(!"default".equals(l.id))s.removeGenerator(l.id);
        String profile=s.addProfile(bundled());String id=s.addGenerator(profile);s.texture(id,MixerConfig.Texture.VOICE);s.output(.25,MixerConfig.Width.DEFAULT);return id;
    }
    private View find(OscillationActivity a,String text,int flags){ArrayList<View> found=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(found,text,flags);
        for(View view:found){if(flags==View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION&&view.getContentDescription()!=null&&text.contentEquals(view.getContentDescription()))return view;
            if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;}throw new AssertionError("Missing control "+text);}
    @Test public void voicePresetButtonsAndCountSliderWorkWithoutChangingOldToneSettings() throws Exception {
        String id=generator();SettingsStore s=SettingsStore.get(context);
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(new Intent(context,OscillationActivity.class).putExtra("generator",id))) {
            scenario.onActivity(a->{
                assertNotNull(find(a,"Voice level",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION));
                ((Button)find(a,"Speech-like blend",View.FIND_VIEWS_WITH_TEXT)).performClick();assertTrue(s.layer(id).controls().tone>0);
                SeekBar count=(SeekBar)find(a,"Voices",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                count.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));assertEquals(3,s.layer(id).controls().voices);
                ((Button)find(a,"Noise-first",View.FIND_VIEWS_WITH_TEXT)).performClick();assertEquals(0,s.layer(id).controls().tone,0);assertEquals(3,s.layer(id).controls().voices);
                ((Button)find(a,"Reset voice settings",View.FIND_VIEWS_WITH_TEXT)).performClick();assertEquals(4,s.layer(id).controls().voices);
                assertEquals(1,s.layer(id).oscillation.tone,0);
            });
        }
    }
    private java.io.File clip() throws Exception {
        int samples=48000*7;ByteBuffer bytes=ByteBuffer.allocate(44+samples*4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*4).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(16).putShort((short)1).putShort((short)2).putInt(48000).putInt(192000).putShort((short)4).putShort((short)16);
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*4);
        for(int i=0;i<samples;i++){short value=(short)(12000*Math.sin(2*Math.PI*1000*i/48000));bytes.putShort(value).putShort((short)-value);}
        java.io.File file=new java.io.File(context.getCacheDir(),"voice-preview.wav");Files.write(file.toPath(),bytes.array());return file;
    }
    @Test public void changingPreviewTypeKeepsTheSameSourcePositionAndSession() throws Exception {
        String id=generator();java.io.File file=clip();SettingsStore s=SettingsStore.get(context);
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(new Intent(context,OscillationActivity.class).putExtra("generator",id))) {
            scenario.onActivity(a->a.load(Uri.fromFile(file)));AtomicBoolean ready=new AtomicBoolean();
            waitFor(()->{scenario.onActivity(a->ready.set(find(a,"Play preview",View.FIND_VIEWS_WITH_TEXT).isEnabled()));return ready.get();},10000);
            scenario.onActivity(a->((Button)find(a,"Play preview",View.FIND_VIEWS_WITH_TEXT)).performClick());waitFor(()->PreviewController.session()!=null,8000);
            PreviewSession session=PreviewController.session();waitFor(()->session.seconds()>.2,4000);double position=session.seconds();
            scenario.onActivity(a->((Spinner)find(a,"Preview generator type",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)).setSelection(1));
            waitFor(()->s.layer(id).texture==MixerConfig.Texture.OSCILLATING,3000);assertSame(session,PreviewController.session());
            scenario.onActivity(a->((Spinner)find(a,"Preview generator type",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)).setSelection(2));
            waitFor(()->s.layer(id).texture==MixerConfig.Texture.VOICE,3000);assertSame(session,PreviewController.session());assertTrue(session.seconds()>=position);
        }
    }
    @Test public void speechLikeModeContinuesInBackgroundAndStopReleasesIt() throws Exception {
        String id=generator();SettingsStore s=SettingsStore.get(context);s.voiceBlend(id,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START)));waitFor(()->PlaybackStatus.playing,8000);
            scenario.onActivity(a->a.moveTaskToBack(true));Thread.sleep(1800);assertTrue(PlaybackStatus.playing);
            assertTrue(context.getSystemService(android.media.AudioManager.class).isMusicActive());
            context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()[0].getNotification().actions[0].actionIntent.send();
            waitFor(()->!PlaybackStatus.active,8000);
        }
    }
    private interface Condition{boolean get()throws Exception;}
    private static void waitFor(Condition condition,long timeout)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+timeout;
        while(android.os.SystemClock.elapsedRealtime()<end){if(condition.get())return;Thread.sleep(60);}assertTrue("Timed out",condition.get());}
}
