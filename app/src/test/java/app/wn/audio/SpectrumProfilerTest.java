package app.wn.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpectrumProfilerTest {
    private float[] tone(int seconds, boolean stereo) {
        float[] audio = new float[seconds*48000*(stereo?2:1)];
        for(int i=0;i<seconds*48000;i++) {
            float value=(float)(.55*Math.sin(2*Math.PI*1000*i/48000)+.35*Math.sin(2*Math.PI*2300*i/48000));
            if(stereo){audio[i*2]=value;audio[i*2+1]=-value;}else audio[i]=value;
        }
        return audio;
    }
    private SpectrumProfiler.Result result(boolean stereo) {
        SpectrumProfiler p=new SpectrumProfiler(48000,stereo?2:1);float[] x=tone(3,stereo);
        p.accept(x,0,x.length);return p.finish("Measured",SpectrumProfiler.Tuning.balanced());
    }
    private boolean covers(NoisePreset p,double frequency) {
        for(NoisePreset.Band b:p.frequencyBands())if(b.lowHz<=frequency&&b.highHz>=frequency)return true;return false;
    }
    @Test public void profilingFindsKnownFrequenciesAndLeavesMostOfTheRangeEmpty() {
        SpectrumProfiler.Result r=result(false);
        assertTrue(covers(r.profile,1000));assertTrue(covers(r.profile,2300));assertFalse(covers(r.profile,5000));
        assertTrue(r.energyCoverage>.85);assertTrue(r.bandwidthHz<=Math.ceil(198*.35)*40);
        assertTrue(r.profile.frequencyBands().length<=16);assertEquals(3,r.seconds,1e-9);
        assertNotNull(NoisePreset.parse(r.profile.toJson()));
    }
    @Test public void oppositePhaseStereoDoesNotCancelTheProfile() {
        NoisePreset.Band[] mono=result(false).profile.frequencyBands(),stereo=result(true).profile.frequencyBands();
        assertEquals(mono.length,stereo.length);
        for(int i=0;i<mono.length;i++){
            assertEquals(mono[i].lowHz,stereo[i].lowHz,0);assertEquals(mono[i].highHz,stereo[i].highHz,0);
            assertEquals(mono[i].level,stereo[i].level,1e-9);
        }
    }
    @Test public void quietFramesAreGatedOut() {
        SpectrumProfiler p=new SpectrumProfiler(48000,1);
        p.accept(new float[48000],0,48000);float[] x=tone(2,false);p.accept(x,0,x.length);
        SpectrumProfiler.Result r=p.finish("Gated",SpectrumProfiler.Tuning.narrow());
        assertTrue(r.usedFrames<r.analysedFrames);assertTrue(covers(r.profile,1000));assertTrue(r.bandwidthHz<=Math.ceil(198*.2)*40);
    }
    @Test public void silenceTooShortAndNonFiniteAudioAreRejected() {
        SpectrumProfiler p=new SpectrumProfiler(48000,1);p.accept(new float[48000],0,48000);
        try{p.finish("Silent",SpectrumProfiler.Tuning.balanced());fail();}catch(IllegalArgumentException expected){}
        try{new SpectrumProfiler(48000,1).accept(new float[]{Float.NaN},0,1);fail();}catch(IllegalArgumentException expected){}
        try{new SpectrumProfiler.Tuning(Double.NaN,.3,16);fail();}catch(IllegalArgumentException expected){}
        try{new SpectrumProfiler(1000,1);fail();}catch(IllegalArgumentException expected){}
    }
}
