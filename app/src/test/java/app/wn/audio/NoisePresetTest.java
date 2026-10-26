package app.wn.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class NoisePresetTest {
    private final String valid = "{\"version\":1,\"name\":\"Voice bands\",\"bands\":[{\"lowHz\":700,\"highHz\":1000,\"gainDb\":-6}]}";
    @Test public void explicitBandsHaveZeroGainOutsideSelection() {
        NoisePreset p = NoisePreset.parse(valid);
        assertEquals(0, p.gainAt(300), 0); assertEquals(0, p.gainAt(1200), 0);
        assertEquals(Math.pow(10, -6.0 / 20), p.gainAt(850), 1e-9);
        assertEquals(0, p.gainAt(700), 0); assertEquals(0, p.gainAt(1000), 0);
    }
    @Test public void savedProfileRoundTripsWithoutLosingSpectralValues() {
        NoisePreset a = NoisePreset.parse(valid), b = NoisePreset.parse(a.toJson());
        assertEquals(a.name, b.name);
        for (double f = 20; f < 22000; f += 23) assertEquals(a.gainAt(f), b.gainAt(f), 1e-12);
    }
    @Test public void tenBandReferenceRoundTripsAndMatchesImageApproximation() {
        NoisePreset copy = NoisePreset.parse(NoisePreset.DEFAULT.toJson());
        assertArrayEquals(new double[]{.17,.29,.38,.56,.65,.59,.38,.28,.16,.04}, copy.octaveLevels(), 0);
        assertEquals(.65, copy.gainAt(500), 1e-9);
    }
    @Test public void utf8BomIsAccepted() { assertNotNull(NoisePreset.parse("\ufeff" + valid)); }
    @Test public void invalidProfilesFailBeforeReplacement() {
        String[] invalid = {"", "[]", valid + "garbage", valid.replace("700", "5"),
                valid.replace("1000", "705"), valid.replace("-6", "4"), valid.replace("700", "\"700\""),
                valid.replace("\"version\":1", "\"version\":2"), valid.replace("\"Voice bands\"", "\"\""),
                "{\"version\":1,\"name\":\"Empty\",\"bands\":[]}",
                "{\"version\":1,\"name\":\"Both\",\"bands\":[],\"levels\":[]}",
                "{\"version\":1,\"name\":\"Overlap\",\"bands\":[{\"lowHz\":500,\"highHz\":1000,\"level\":1},{\"lowHz\":900,\"highHz\":1200,\"level\":1}]}",
                "{\"version\":1,\"name\":\"Silent\",\"levels\":[0,0,0,0,0,0,0,0,0,0]}",
                "{\"version\":1,\"name\":\"Bad level\",\"levels\":[1,1,1,1,1,1,1,1,1,2]}"};
        for (String json : invalid) {
            try { NoisePreset.parse(json); fail("Accepted invalid profile: " + json); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }
    @Test public void publicArraysCannotMutateAnExistingPreset() {
        double before = NoisePreset.DEFAULT.gainAt(1000);
        double[] levels = NoisePreset.DEFAULT.octaveLevels(); levels[5] = 1;
        assertEquals(before, NoisePreset.DEFAULT.gainAt(1000), 0);
    }
}
