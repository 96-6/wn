package app.wn;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.SeekBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.wn.audio.MixerConfig;
import app.wn.audio.Oscillation;
import app.wn.audio.PreviewClip;
import app.wn.audio.PreviewSession;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class ProceduralDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @After public void stop() throws Exception {
        PreviewController.stop();SettingsStore.get(context).setWantsPlayback(false);
        context.stopService(new Intent(context,NoiseService.class));waitFor(()->!PlaybackStatus.active,8000);
    }
    private Context isolated(){String name="procedural-test-"+java.util.UUID.randomUUID();return new ContextWrapper(context){
        @Override public android.content.SharedPreferences getSharedPreferences(String ignored,int mode){return context.getSharedPreferences(name,mode);}
    };}
    @Test public void allFineControlsPersistResetAndKeepMixerValues() throws Exception {
        Context c=isolated();SettingsStore s=new SettingsStore(c);String id=s.layers().get(1).id;
        s.texture(id,MixerConfig.Texture.OSCILLATING);s.updateGenerator(id,true,.43,null);s.output(.37,MixerConfig.Width.WIDE);
        double[] values={1.72,.62,2.31,1.6,-3.4,1.25};for(int i=0;i<values.length;i++)s.oscillation(id,i,values[i]);s.saveControls();
        SettingsStore reloaded=new SettingsStore(c);for(int i=0;i<values.length;i++)assertEquals(values[i],reloaded.layer(id).oscillation.value(i),1e-8);
        reloaded.resetOscillation(id);reloaded=new SettingsStore(c);
        for(int i=0;i<values.length;i++)assertEquals(Oscillation.DEFAULT.value(i),reloaded.layer(id).oscillation.value(i),0);
        assertEquals(.43,reloaded.layer(id).level,1e-8);assertEquals(.37,reloaded.snapshot().master,.0001);
        assertEquals(MixerConfig.Width.WIDE,reloaded.snapshot().width);assertEquals(MixerConfig.Texture.OSCILLATING,reloaded.layer(id).texture);
    }
    private String generator() throws Exception {
        SettingsStore s=SettingsStore.get(context);
        for(SettingsStore.Layer l:s.layers())if(!"default".equals(l.id))s.removeGenerator(l.id);
        String p;try(InputStream input=context.getAssets().open("adjusted.json")){p=s.addProfile(ProfileFiles.read(input));}
        String id=s.addGenerator(p);s.texture(id,MixerConfig.Texture.OSCILLATING);s.output(.25,MixerConfig.Width.DEFAULT);return id;
    }
    private Intent activity(String id){return new Intent(context,OscillationActivity.class).putExtra("generator",id);}
    private View text(OscillationActivity a,String value,int flags){ArrayList<View> list=new ArrayList<>();a.getWindow().getDecorView().findViewsWithText(list,value,flags);assertFalse("Missing control "+value,list.isEmpty());return list.get(0);}
    @Test public void visibleSlidersChangeSettingsAndResetButtonRestoresDefaults() throws Exception {
        String id=generator();SettingsStore s=SettingsStore.get(context);
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(activity(id))) {
            scenario.onActivity(a->{
                SeekBar slider=(SeekBar)text(a,"Tone level",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
                assertTrue(s.layer(id).oscillation.tone>1);
                ((Button)text(a,"Reset oscillating settings",View.FIND_VIEWS_WITH_TEXT)).performClick();
                assertEquals(1,s.layer(id).oscillation.tone,0);
                for(String control:new String[]{"Noise level","Voice oscillation speed","Variation","Pitch shift","Tone spread"})assertNotNull(text(a,control,View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION));
            });
        }
    }
    private File fixture(String extension) throws Exception {
        File f=new File(context.getCacheDir(),"preview."+extension);
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("voice."+extension)){
            Files.copy(in,f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }return f;
    }
    @Test public void previewDecodesWavMp3FlacAndAacWithoutStereoCancellation() throws Exception {
        for(String extension:new String[]{"wav","mp3","flac","m4a"}) {
            PreviewClip clip=AndroidProfiler.previewFile(context,Uri.fromFile(fixture(extension)),0,2,new AndroidProfiler.Control(),seconds->{});
            assertTrue(clip.rms>.1);assertEquals(96000,clip.frames,1600);
        }
    }
    private void ready(ActivityScenario<OscillationActivity> scenario) throws Exception {
        AtomicBoolean result=new AtomicBoolean();waitFor(()->{scenario.onActivity(a->result.set(text(a,"Play preview",View.FIND_VIEWS_WITH_TEXT).isEnabled()));return result.get();},12000);
    }
    @Test public void previewPlaysOnceAndRestoresThePreviouslyStoppedState() throws Exception {
        String id=generator();File source=fixture("wav");
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(activity(id))) {
            scenario.onActivity(a->a.load(Uri.fromFile(source)));ready(scenario);
            scenario.onActivity(a->((Button)text(a,"Play preview",View.FIND_VIEWS_WITH_TEXT)).performClick());
            waitFor(()->PreviewController.session()!=null&&PlaybackStatus.playing,8000);
            PreviewSession session=PreviewController.session();assertNotNull(session);
            waitFor(()->session.finished,10000);waitFor(()->!PlaybackStatus.active,8000);
            assertEquals(session.clip.frames/48000.0,session.seconds(),.01);assertNull(PreviewController.session());
        }
    }
    @Test public void leavingPreviewKeepsPreviouslyRunningNoiseAndNotificationStopsBoth() throws Exception {
        String id=generator();File source=fixture("wav");
        try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(a->a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START)));
            waitFor(()->PlaybackStatus.playing,8000);
        }
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(activity(id))) {
            scenario.onActivity(a->a.load(Uri.fromFile(source)));ready(scenario);
            scenario.onActivity(a->((Button)text(a,"Play preview",View.FIND_VIEWS_WITH_TEXT)).performClick());
            waitFor(()->PreviewController.session()!=null,8000);
            context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()[0].getNotification().actions[0].actionIntent.send();
            waitFor(()->!PlaybackStatus.active,8000);assertNull(PreviewController.session());
        }
        // A second preview can be closed while ordinary playback remains active.
        try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(a->a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START)));
            waitFor(()->PlaybackStatus.playing,8000);
        }
        try(ActivityScenario<OscillationActivity> scenario=ActivityScenario.launch(activity(id))) {
            scenario.onActivity(a->a.load(Uri.fromFile(source)));ready(scenario);
            scenario.onActivity(a->((Button)text(a,"Play preview",View.FIND_VIEWS_WITH_TEXT)).performClick());
            waitFor(()->PreviewController.session()!=null,8000);
        }
        assertNull(PreviewController.session());assertTrue(PlaybackStatus.playing);
    }
    private interface Condition{boolean get() throws Exception;}
    private static void waitFor(Condition condition,long timeout) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+timeout;
        while(android.os.SystemClock.elapsedRealtime()<deadline){if(condition.get())return;Thread.sleep(75);}
        assertTrue("Timed out",condition.get());
    }
}
