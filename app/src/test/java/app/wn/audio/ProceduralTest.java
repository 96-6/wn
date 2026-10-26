package app.wn.audio;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ProceduralTest {
    private String resource(String name) throws Exception {
        try(java.io.InputStream in=getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private NoisePreset profile() throws Exception{return NoisePreset.parse(resource("adjusted.json"));}
    @Test public void neuralInferenceMatchesTheTrainingExporter() throws Exception {
        NoisePreset p=profile();assertNotNull(p.motion.model);
        JSONObject reference=new JSONObject(resource("motion-model-reference.json"));
        double[] input=new double[4],output=new double[15];
        for(int i=0;i<4;i++)input[i]=reference.getJSONArray("input").getDouble(i);
        p.motion.model.predict(input,new double[16],output);
        for(int i=0;i<15;i++)assertEquals(reference.getJSONArray("output").getDouble(i),output[i],1e-8);
        assertNotNull(NoisePreset.parse(p.toJson()).motion.model);
    }
    @Test public void neuralControlsDoNotRepeatAtTheRecordingPeriod() throws Exception {
        MotionShape shape=profile().motion;RandomMotion motion=new RandomMotion(shape,51);
        int period=shape.size;double[] pitch=new double[period*8];double difference=0,mean=0,var=0;java.util.HashSet<Integer> distinct=new java.util.HashSet<>();
        for(int i=0;i<pitch.length;i++) {
            motion.advance(shape.stepSeconds,Oscillation.DEFAULT);pitch[i]=motion.pitch;
            assertTrue(Double.isFinite(pitch[i]));assertTrue(pitch[i]>=50&&pitch[i]<=2000);
            assertTrue(motion.energy>=0&&motion.energy<=1);distinct.add((int)Math.round(pitch[i]));mean+=pitch[i];
            if(i>=period)difference+=Math.abs(pitch[i]-pitch[i-period]);
        }
        mean/=pitch.length;for(double v:pitch)var+=(v-mean)*(v-mean);
        assertTrue(distinct.size()>80);assertTrue(difference/(pitch.length-period)>15);assertTrue(Math.sqrt(var/pitch.length)>25);
    }
    @Test public void olderProfilesAlsoGenerateFreshControls() {
        NoisePreset p=NoisePreset.parse("{\"version\":1,\"name\":\"Old\",\"bands\":[{\"lowHz\":100,\"highHz\":3000,\"level\":1}],\"motion\":{\"stepMs\":80,\"pitchHz\":[200,300,400,250],\"energy\":[0.2,1,0.5,0.3]}}");
        RandomMotion a=new RandomMotion(p.motion,1),b=new RandomMotion(p.motion,2);double difference=0;
        for(int i=0;i<100;i++){a.advance(.08,Oscillation.DEFAULT);b.advance(.08,Oscillation.DEFAULT);difference+=Math.abs(a.pitch-b.pitch);}
        assertTrue(difference>100);
    }
    @Test public void malformedNeuralWeightsAreRejectedBeforePlayback() throws Exception {
        JSONObject p=new JSONObject(profile().toJson());p.getJSONObject("motion").getJSONObject("model").put("w1",new JSONArray().put(1));
        try{NoisePreset.parse(p.toString());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void fineControlsKeepDefaultsAndExtremeMixesStayFinite() throws Exception {
        Oscillation d=Oscillation.parse(null);assertEquals(1,d.tone,0);assertEquals(1,d.noise,0);assertEquals(1,d.speed,0);assertEquals(0,d.semitones,0);
        Oscillation c=Oscillation.parse(new Oscillation(2,2,4,2,12,2).toJson());assertEquals(4,c.speed,0);
        NoisePreset p=profile();ArrayList<MixerConfig.Generator> layers=new ArrayList<>();
        for(int i=0;i<8;i++)layers.add(new MixerConfig.Generator(i==0?"default":"g"+i,p,true,1,MixerConfig.Texture.OSCILLATING,c));
        NoiseMixer mixer=new NoiseMixer(98);float[] out=new float[NoiseMixer.FRAMES*2];MixerConfig config=new MixerConfig(layers,1,MixerConfig.Width.WIDE);
        for(int i=0;i<50;i++){mixer.render(out,config,1);for(float value:out){assertTrue(Float.isFinite(value));assertTrue(Math.abs(value)<=.960001);}}
        assertFalse(new MixerConfig(java.util.Collections.singletonList(new MixerConfig.Generator("default",p,true,1,MixerConfig.Texture.OSCILLATING,new Oscillation(0,0,1,1,0,1))),1,MixerConfig.Width.DEFAULT).hasAudibleLayer());
    }
    private PreviewClip clip(int rate,int seconds) {
        PreviewClip.Builder builder=new PreviewClip.Builder();float[] pcm=new float[rate*seconds*2];
        for(int i=0;i<rate*seconds;i++){pcm[i*2]=(float)(.2*Math.sin(2*Math.PI*1000*i/rate));pcm[i*2+1]=-pcm[i*2];}
        for(int offset=0;offset<pcm.length;offset+=2048){int count=Math.min(2048,pcm.length-offset);builder.accept(java.util.Arrays.copyOfRange(pcm,offset,offset+count),count,rate,2);}
        return builder.finish();
    }
    @Test public void previewResamplingPreservesOppositePhaseStereoAndDuration() {
        PreviewClip clip=clip(44100,2);assertEquals(96000,clip.frames,2);assertEquals(.2/Math.sqrt(2),clip.rms,.001);
        for(int i=0;i<clip.frames;i+=47)assertEquals(-clip.sample(i,0),clip.sample(i,1),1e-7);
    }
    @Test public void previewBalanceIsApproximatelyTwoTimesSourceRms() {
        PreviewClip clip=clip(48000,6);double[] powers=new double[2];
        for(int mode=0;mode<2;mode++) {
            PreviewSession s=new PreviewSession(clip);s.monitor=mode==0?PreviewSession.Monitor.MASKER:PreviewSession.Monitor.SOURCE;
            float[] block=new float[4096];
            for(int n=0;n<100;n++) {
                for(int i=0;i<2048;i++)block[i*2]=block[i*2+1]=(float)(.1*Math.sin(2*Math.PI*2000*(n*2048L+i)/48000));
                s.render(block,1,1);if(n>70)for(float v:block)powers[mode]+=v*v;
            }
        }
        assertEquals(Math.pow(10,6.0/20),Math.sqrt(powers[0]/powers[1]),.03);
    }
    @Test public void previewPlaysOnceAndTheSumNeverClips() {
        PreviewSession s=new PreviewSession(clip(48000,1));s.balanceDb=-12;float[] block=new float[4096];
        for(int i=0;i<24;i++){java.util.Arrays.fill(block,.95f);s.render(block,1,1);for(float v:block)assertTrue(Math.abs(v)<=.960001);}
        assertTrue(s.finished);double position=s.seconds();s.render(block,1,1);assertEquals(position,s.seconds(),0);
    }
    @Test public void previewKeepsTheSourceReferenceFixedWhileMaskerGainsChange() {
        PreviewSession s=new PreviewSession(clip(48000,6));s.monitor=PreviewSession.Monitor.SOURCE;
        float[] block=new float[4096];double before=0,after=0;
        for(int n=0;n<100;n++) {
            java.util.Arrays.fill(block,n<60?.1f:.02f);s.render(block,1,1);
            if(n>=40&&n<60)for(float value:block)before+=value*value;
            if(n>=80)for(float value:block)after+=value*value;
        }
        assertEquals(1,Math.sqrt(after/before),.01);
    }
}
