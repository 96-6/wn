package app.wn.audio;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class VoiceMaskerTest {
    private NoisePreset profile() throws Exception {
        try(java.io.InputStream in=getClass().getClassLoader().getResourceAsStream("adjusted.json")) {
            assertNotNull(in);return NoisePreset.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void vocalTractModelSurvivesJsonExportAndHasLearnedVariation() throws Exception {
        NoisePreset a=profile(),b=NoisePreset.parse(a.toJson());assertNotNull(a.voice);assertEquals(a.voice.count,b.voice.count);
        assertTrue(a.voice.count>=4);double variation=0;
        for(int s=0;s<a.voice.count;s++)for(int k=0;k<24;k++){assertEquals(a.voice.gains[s][k],b.voice.gains[s][k],1e-12);variation+=Math.abs(a.voice.gains[s][k]-1);}
        assertTrue(variation>10);assertEquals(a.voice.mix,b.voice.mix,0);assertEquals(a.voice.tilt,b.voice.tilt,0);
    }
    @Test public void invalidSpectralStateAndTransitionDimensionsFailBeforePlayback() throws Exception {
        String original=profile().toJson();
        for(int invalid=0;invalid<4;invalid++) {
            JSONObject o=new JSONObject(original),voice=o.getJSONObject("voice");
            if(invalid==0)voice.getJSONArray("states").getJSONObject(0).put("gains",new JSONArray().put(1));
            if(invalid==1)voice.put("transitions",new JSONArray());
            if(invalid==2)voice.put("mix",4);
            if(invalid==3)voice.getJSONArray("states").getJSONObject(0).put("voicing",-1);
            try{NoisePreset.parse(o.toString());fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void noiseOnlyAtZeroTiltMatchesTheExistingContinuousGenerator() throws Exception {
        JSONObject json=new JSONObject(profile().toJson());json.getJSONObject("voice").put("mix",0).put("tilt",0);
        NoisePreset p=NoisePreset.parse(json.toString());NoiseMixer a=new NoiseMixer(512),b=new NoiseMixer(512);
        MixerConfig normal=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.CONTINUOUS)),1,MixerConfig.Width.DEFAULT);
        MixerConfig voice=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE)),1,MixerConfig.Width.DEFAULT);
        float[] x=new float[4096],y=x.clone();for(int i=0;i<65;i++){a.render(x,normal,1);b.render(y,voice,1);}
        for(int i=0;i<x.length;i++)assertEquals(x[i],y[i],1e-7);
    }
    @Test public void speechBlendGeneratesFreshAudioWithDifferentSeeds() throws Exception {
        NoisePreset p=profile();Oscillation controls=p.voice.speechDefaults();
        MixerConfig config=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE,controls)),1,MixerConfig.Width.MONO);
        NoiseMixer a=new NoiseMixer(7),b=new NoiseMixer(8);float[] x=new float[4096],y=x.clone();double difference=0;
        for(int n=0;n<30;n++){a.render(x,config,1);b.render(y,config,1);for(int i=0;i<x.length;i++)difference+=Math.abs(x[i]-y[i]);}
        assertTrue(difference>10);
    }
    @Test public void dryPseudoVoicesAreAudibleAndKeepEnergyInTheSpeechBands() throws Exception {
        NoisePreset p=profile();Oscillation controls=new Oscillation(1,0,1,1,0,1,4);
        MixerConfig config=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE,controls)),1,MixerConfig.Width.MONO);
        NoiseMixer mixer=new NoiseMixer(32);float[] out=new float[4096];for(int i=0;i<40;i++)mixer.render(out,config,1);
        double[] re=new double[4096],im=re.clone();double energy=0;
        for(int i=0;i<2048;i++){energy+=out[i*2]*out[i*2];re[i]=out[i*2]*(.5-.5*Math.cos(2*Math.PI*i/2048));}
        Fft.transform(re,im,false);double inside=0,outside=0;
        for(int k=1;k<2048;k++){double power=re[k]*re[k]+im[k]*im[k];if(k*48000.0/4096>8500)outside+=power;else inside+=power;}
        assertTrue(energy>.001);assertTrue(outside/inside<.002);
    }
    @Test public void voiceCountAndExtremeControlsRemainBoundedWithEightGenerators() throws Exception {
        NoisePreset p=profile();ArrayList<MixerConfig.Generator> layers=new ArrayList<>();
        for(int i=0;i<8;i++)layers.add(new MixerConfig.Generator(i==0?"default":"voice"+i,p,true,1,MixerConfig.Texture.VOICE,new Oscillation(2,2,4,2,12,2,4)));
        MixerConfig config=new MixerConfig(layers,1,MixerConfig.Width.WIDE);NoiseMixer mixer=new NoiseMixer(933);float[] out=new float[4096];
        for(int n=0;n<35;n++){mixer.render(out,config,1);for(float value:out){assertTrue(Float.isFinite(value));assertTrue(Math.abs(value)<=.960001);}}
        assertEquals(1,Oscillation.DEFAULT.with(6,-100).voices);assertEquals(4,Oscillation.DEFAULT.with(6,100).voices);
    }
    @Test public void theVoiceControlsAreSeparateFromTheOlderOscillatingSettings() throws Exception {
        NoisePreset p=profile();Oscillation old=new Oscillation(.4,.8,1.7,.6,-2,1.2),voice=p.voice.defaults();
        MixerConfig.Generator g=new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE,old,voice);
        assertSame(voice,g.controls());assertSame(old,g.oscillation);assertEquals(.4,g.oscillation.tone,0);
        assertEquals(p.voice.mix,Math.pow(g.controls().tone*.65,2),1e-12);
    }
    @Test public void fadingVoiceOutDoesNotUseUnrelatedOscillatorSettings() throws Exception {
        NoisePreset p=profile();Oscillation voice=p.voice.speechDefaults(),a=new Oscillation(0,0,1,1,0,1),b=new Oscillation(2,2,1,1,0,1);
        NoiseMixer x=new NoiseMixer(39),y=new NoiseMixer(39);float[] first=new float[4096],second=first.clone();
        MixerConfig xa=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE,a,voice)),1,MixerConfig.Width.MONO);
        MixerConfig ya=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.VOICE,b,voice)),1,MixerConfig.Width.MONO);
        for(int n=0;n<35;n++){x.render(first,xa,1);y.render(second,ya,1);}
        xa=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.CONTINUOUS,a,voice)),1,MixerConfig.Width.MONO);
        ya=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.CONTINUOUS,b,voice)),1,MixerConfig.Width.MONO);
        x.render(first,xa,1);y.render(second,ya,1);for(int i=0;i<first.length;i++)assertEquals(first[i],second[i],1e-7);
    }
}
