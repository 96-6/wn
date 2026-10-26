package app.wn.audio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;

public class MotionProfileTest {
    private static final String JSON = "{\"version\":1,\"name\":\"Motion\",\"bands\":[{\"lowHz\":80,\"highHz\":6800,\"level\":1}],"
            + "\"motion\":{\"stepMs\":500,\"pitchHz\":[240,380,300,240],\"energy\":[0.2,1,0.6,0.2]}}";
    @Test public void motionSurvivesExportAndHasContinuousLoopControls() {
        NoisePreset a = NoisePreset.parse(JSON), b = NoisePreset.parse(a.toJson());
        assertEquals(4, b.motion.size);
        for (double t=0; t<5; t+=.071) {
            assertEquals(a.motion.pitchAt(t), b.motion.pitchAt(t), 1e-9);
            assertEquals(a.motion.energyAt(t), b.motion.energyAt(t), 1e-9);
        }
        assertEquals(b.motion.pitchAt(0), b.motion.pitchAt(2), 0);
        assertEquals(b.motion.energyAt(0), b.motion.energyAt(2), 0);
    }
    @Test public void invalidMotionCannotEnterTheRenderer() {
        for(String invalid : new String[]{JSON.replace("[240,380,300,240]","[240]"),
                JSON.replace("380", "2000"), JSON.replace("500", "2"),
                JSON.replace("[0.2,1,0.6,0.2]", "[0.2,2,0.6,0.2]"),
                JSON.replace("[0.2,1,0.6,0.2]", "[0.2,1]"), JSON.replace("240", "\"240\"")}) {
            try { NoisePreset.parse(invalid); fail("Accepted invalid motion"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void adjacentBandsHaveNoArtificialSpectralHoles() {
        NoisePreset p=NoisePreset.parse("{\"version\":1,\"name\":\"Joined\",\"bands\":[{\"lowHz\":100,\"highHz\":500,\"level\":1},"
                +"{\"lowHz\":500,\"highHz\":900,\"level\":0.5}]}");
        for(double f=150; f<=850; f+=.5) assertTrue("Gap at "+f,p.gainAt(f)>=.5);
        assertEquals(0,p.gainAt(100),0);assertEquals(0,p.gainAt(900),0);
    }
    @Test public void oscillatingHarmonicsStayInTheSelectedSpectrum() {
        MotionOscillator oscillator=new MotionOscillator(1234); NoisePreset p=NoisePreset.parse(JSON);
        double[] m=new double[NoiseMixer.FRAMES],s=m.clone(),pulse=m.clone();
        double rms=0;
        for(int block=0;block<30;block++) oscillator.render(p,m,s,pulse);
        double[] re=new double[NoiseMixer.FFT_SIZE],im=re.clone();
        for(int i=0;i<m.length;i++) { assertTrue(Double.isFinite(m[i]));rms+=m[i]*m[i]; re[i]=m[i]*(.5-.5*Math.cos(2*Math.PI*i/m.length)); }
        Fft.transform(re,im,false); double inside=0,outside=0;
        for(int k=1;k<re.length/2;k++) { double power=re[k]*re[k]+im[k]*im[k];
            if(k*48000.0/re.length>8000)outside+=power;else inside+=power; }
        assertTrue(rms/m.length>.001);assertTrue(outside/inside<.001);
        for(double v:pulse)assertTrue(v>=.72 && v<=1);
    }
    @Test public void bothTypesMixSwitchAndRemainBoundedWithEightGenerators() {
        NoisePreset p=NoisePreset.parse(JSON);NoiseMixer mixer=new NoiseMixer(55);
        ArrayList<MixerConfig.Generator> layers=new ArrayList<>();
        for(int i=0;i<8;i++)layers.add(new MixerConfig.Generator(i==0?"default":"g"+i,p,true,1,
                i%2==0?MixerConfig.Texture.CONTINUOUS:MixerConfig.Texture.OSCILLATING));
        MixerConfig config=new MixerConfig(layers,1,MixerConfig.Width.WIDE);
        float[] out=new float[NoiseMixer.FRAMES*2];double energy=0;
        for(int block=0;block<100;block++) {
            mixer.render(out,config,1);
            for(float v:out){assertTrue(Float.isFinite(v));assertTrue(Math.abs(v)<=.960001);energy+=v*v;}
        }
        assertTrue(energy>1);
        layers.set(1,new MixerConfig.Generator("g1",p,true,1,MixerConfig.Texture.CONTINUOUS));
        mixer.render(out,new MixerConfig(layers,1,MixerConfig.Width.MONO),1);
        for(float v:out)assertTrue(Float.isFinite(v));
        assertEquals(MixerConfig.Texture.CONTINUOUS,new MixerConfig.Generator("x",NoisePreset.DEFAULT,true,1,MixerConfig.Texture.OSCILLATING).texture);
    }
}
