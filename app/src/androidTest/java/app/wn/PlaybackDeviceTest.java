package app.wn;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.wn.audio.MixerConfig;
import app.wn.audio.NoisePreset;
import app.wn.audio.SpectrumProfiler;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @After public void stop() throws Exception {
        SettingsStore.get(context).setWantsPlayback(false);context.stopService(new Intent(context,NoiseService.class));
        waitFor(()->!PlaybackStatus.active,5000);
    }
    private SettingsStore reset() {
        SettingsStore s=SettingsStore.get(context);
        for(SettingsStore.Layer l:s.layers())if(!"default".equals(l.id))s.removeGenerator(l.id);
        s.updateGenerator("default",true,.55,null);s.output(.25,MixerConfig.Width.DEFAULT);return s;
    }
    @Test public void profilesAndMultipleGeneratorsPersistAndCanReturnToDefaultOnly() throws Exception {
        SettingsStore s=reset();
        String a=s.addProfile(NoisePreset.DEFAULT), b=s.addProfile(NoisePreset.parse("{\"version\":1,\"name\":\"Second\",\"bands\":[{\"lowHz\":700,\"highHz\":1400,\"level\":.8}]}"));
        String ga=s.addGenerator(a),gb=s.addGenerator(b);s.updateGenerator(ga,true,.41,null);s.updateGenerator(gb,true,.63,null);
        s.output(.25,MixerConfig.Width.WIDE);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.recreate();assertEquals(3,s.snapshot().generators.size());assertNotNull(s.findProfile(a));assertNotNull(s.findProfile(b));
            assertEquals(.41,s.snapshot().generators.get(1).level,.0001);assertEquals(.63,s.snapshot().generators.get(2).level,.0001);
            assertEquals(MixerConfig.Width.WIDE,s.snapshot().width);
        }
        s.removeGenerator(ga);s.removeGenerator(gb);assertEquals(1,s.layers().size());assertNotNull(s.findProfile(a));
        try{s.removeGenerator("default");fail();}catch(IllegalArgumentException expected){}
        assertEquals(1,s.layers().size());
    }
    @Test public void actualAudioContinuesAfterActivityClosesAndMediaControllerStopsIt() throws Exception {
        SettingsStore s=reset();String id=s.addProfile(NoisePreset.DEFAULT);s.addGenerator(id);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START)));
            waitFor(()->PlaybackStatus.playing,10000);waitFor(()->context.getSystemService(AudioManager.class).isMusicActive(),10000);
            scenario.onActivity(a->a.moveTaskToBack(true));Thread.sleep(2500);assertTrue(PlaybackStatus.playing);
            waitFor(()->context.getSystemService(AudioManager.class).isMusicActive(),12000);
        }
        assertTrue(PlaybackStatus.playing);
        waitFor(()->context.getSystemService(NotificationManager.class).getActiveNotifications().length>0,8000);
        Notification n=context.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification();
        MediaSession.Token token=n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);assertNotNull(token);
        new MediaController(context,token).getTransportControls().stop();
        waitFor(()->!PlaybackStatus.active,8000);assertFalse(s.wantsPlayback());
    }
    @Test public void notificationStopActionWorksInBackground() throws Exception {
        SettingsStore s=reset();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->a.startForegroundService(new Intent(a,NoiseService.class).setAction(NoiseService.START)));
            waitFor(()->PlaybackStatus.playing,10000);scenario.onActivity(a->a.moveTaskToBack(true));
            waitFor(()->context.getSystemService(NotificationManager.class).getActiveNotifications().length>0,8000);
            context.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification().actions[0].actionIntent.send();
            waitFor(()->!PlaybackStatus.active,8000);assertFalse(s.wantsPlayback());
        }
    }
    @Test public void importedJsonSurvivesSourceDeletionAndProfileExportRoundTrips() throws Exception {
        SettingsStore s=reset();File source=new File(context.getCacheDir(),"import.json");
        Files.write(source.toPath(),NoisePreset.DEFAULT.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String id;
        try(FileInputStream input=new FileInputStream(source)){id=s.addProfile(ProfileFiles.read(input));}
        assertTrue(source.delete());assertNotNull(s.findProfile(id));
        NoisePreset copy=NoisePreset.parse(s.findProfile(id).preset.toJson());assertEquals(.65,copy.gainAt(500),1e-9);
        String json=context.getSharedPreferences("noise_settings",Context.MODE_PRIVATE).getString("catalogue","");
        assertTrue(json.contains(id));
    }
    @Test public void inUseProfilesCannotBeDeletedAndInvalidImportsPreserveCatalogue() throws Exception {
        SettingsStore s=reset();String id=s.addProfile(NoisePreset.DEFAULT);String layer=s.addGenerator(id);int count=s.profiles().size();
        try{s.deleteProfile(id);fail();}catch(IllegalStateException expected){}
        try{s.addProfile(NoisePreset.parse("{}"));fail();}catch(IllegalArgumentException expected){}
        assertEquals(count,s.profiles().size());s.removeGenerator(layer);s.deleteProfile(id);assertNull(s.findProfile(id));
    }
    @Test public void wavMp3FlacAndAacFilesProducePortableSavedProfiles() throws Exception {
        SettingsStore s=reset();
        for(String extension:new String[]{"wav","mp3","flac","m4a"}){
            File file=new File(context.getCacheDir(),"voice."+extension);
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("voice."+extension)){
                Files.copy(in,file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            SpectrumProfiler.Result result=AndroidProfiler.audioFile(context,Uri.fromFile(file),"Decoded "+extension,0,2,
                    SpectrumProfiler.Tuning.balanced(),new AndroidProfiler.Control(),seconds->{});
            assertTrue(result.energyCoverage>.8);assertTrue(result.profile.frequencyBands().length>0);
            assertNotNull(NoisePreset.parse(result.profile.toJson()));
            String id=s.addProfile(result.profile);assertTrue(file.delete());assertNotNull(s.findProfile(id));
        }
    }
    @Test public void cancelledAnalysisDoesNotCreateProfile() throws Exception {
        SettingsStore s=reset();int count=s.profiles().size();
        File file=new File(context.getCacheDir(),"cancel.wav");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("voice.wav")){
            Files.copy(in,file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        AndroidProfiler.Control c=new AndroidProfiler.Control();c.cancelled=true;
        try{AndroidProfiler.audioFile(context,Uri.fromFile(file),"Cancelled",0,2,SpectrumProfiler.Tuning.balanced(),c,seconds->{});fail();}
        catch(java.util.concurrent.CancellationException expected){}
        assertEquals(count,s.profiles().size());
    }
    @Test public void generatorLimitAndMinimumAreEnforcedWithoutDeletingProfiles() {
        SettingsStore s=reset();String id=s.addProfile(NoisePreset.DEFAULT);
        for(int i=1;i<MixerConfig.MAX_GENERATORS;i++)s.addGenerator(id);
        assertEquals(8,s.layers().size());
        try{s.addGenerator(id);fail();}catch(IllegalStateException expected){}
        assertEquals(8,s.layers().size());
        for(SettingsStore.Layer layer:s.layers())if(!"default".equals(layer.id))s.removeGenerator(layer.id);
        assertEquals(1,s.layers().size());assertNotNull(s.findProfile(id));
    }
    private interface Condition{boolean ready();}
    private static void waitFor(Condition c,long timeout)throws Exception{
        long end=System.currentTimeMillis()+timeout;
        while(!c.ready()&&System.currentTimeMillis()<end)Thread.sleep(100);
        assertTrue("Condition not reached in "+timeout+" ms",c.ready());
    }
}
