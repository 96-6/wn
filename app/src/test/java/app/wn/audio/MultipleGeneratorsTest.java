package app.wn.audio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MultipleGeneratorsTest {
    private MixerConfig config(String[] ids, boolean[] enabled) {
        List<MixerConfig.Generator> g = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) g.add(new MixerConfig.Generator(ids[i], NoisePreset.DEFAULT, enabled[i], .3));
        return new MixerConfig(g, .3, MixerConfig.Width.DEFAULT);
    }
    @Test public void threeGeneratorsHaveIndependentAdditiveOutput() {
        String[] ids = {"default", "a", "b"};
        NoiseMixer combined = new NoiseMixer(71);
        NoiseMixer[] single = {new NoiseMixer(71), new NoiseMixer(71), new NoiseMixer(71)};
        MixerConfig mix = config(ids, new boolean[]{true, true, true});
        MixerConfig[] parts = {config(ids, new boolean[]{true,false,false}), config(ids, new boolean[]{false,true,false}), config(ids, new boolean[]{false,false,true})};
        float[] actual = new float[NoiseMixer.FRAMES * 2], sum = actual.clone(), block = actual.clone();
        for (int frame = 0; frame < 30; frame++) {
            combined.render(actual, mix, 1); java.util.Arrays.fill(sum, 0);
            for (int i = 0; i < single.length; i++) {
                single[i].render(block, parts[i], 1);
                for (int j = 0; j < block.length; j++) sum[j] += block[j];
            }
            for (int j = 0; j < actual.length; j++) assertEquals(sum[j], actual[j], 1e-7);
        }
    }
    @Test public void removingGeneratorsReturnsToOnlyDefaultWithoutResettingItsStream() {
        NoiseMixer mix = new NoiseMixer(3), baseline = new NoiseMixer(3);
        MixerConfig both = config(new String[]{"default","extra"}, new boolean[]{true,true});
        MixerConfig only = config(new String[]{"default"}, new boolean[]{true});
        float[] actual = new float[NoiseMixer.FRAMES * 2], expected = actual.clone();
        for (int i = 0; i < 20; i++) { mix.render(actual,both,1); baseline.render(expected,only,1); }
        for (int i = 0; i < 30; i++) { mix.render(actual,only,1); baseline.render(expected,only,1); }
        assertArrayEquals(expected, actual, 1e-6f);
    }
    @Test public void eightGeneratorsStayFiniteAndPeakLimited() {
        ArrayList<MixerConfig.Generator> layers = new ArrayList<>();
        NoisePreset flat = NoisePreset.parse("{\"version\":1,\"name\":\"Flat\",\"levels\":[1,1,1,1,1,1,1,1,1,1]}");
        for (int i = 0; i < 8; i++) layers.add(new MixerConfig.Generator(i == 0 ? "default" : "g"+i, flat, true, 1));
        MixerConfig c = new MixerConfig(layers,1,MixerConfig.Width.WIDE);
        NoiseMixer m = new NoiseMixer(11); float[] output = new float[NoiseMixer.FRAMES*2];
        for (int i = 0; i < 50; i++) {
            m.render(output,c,1);
            for (float v:output) { assertTrue(Float.isFinite(v)); assertTrue(Math.abs(v)<=.960001); }
        }
    }
    @Test public void defaultIsRequiredAndGeneratorIdsMustBeUnique() {
        try { new MixerConfig(Collections.emptyList(),1,null); fail(); } catch(IllegalArgumentException expected) { }
        try { config(new String[]{"extra"},new boolean[]{true}); fail(); } catch(IllegalArgumentException expected) { }
        try { config(new String[]{"default","default"},new boolean[]{true,true}); fail(); } catch(IllegalArgumentException expected) { }
        try {
            config(new String[]{"default","1","2","3","4","5","6","7","8"},new boolean[]{true,true,true,true,true,true,true,true,true}); fail();
        } catch(IllegalArgumentException expected) { }
    }
    @Test public void javaStringHashCollisionsDoNotShareRandomStreams() {
        assertEquals("Aa".hashCode(),"BB".hashCode());
        NoiseMixer a = new NoiseMixer(6), b = new NoiseMixer(6);
        MixerConfig ca = config(new String[]{"default","Aa"},new boolean[]{false,true});
        MixerConfig cb = config(new String[]{"default","BB"},new boolean[]{false,true});
        float[] x = new float[NoiseMixer.FRAMES*2], y=x.clone();
        for(int i=0;i<20;i++){a.render(x,ca,1);b.render(y,cb,1);}
        assertFalse(java.util.Arrays.equals(x,y));
    }
}
