package app.wn;

import app.wn.audio.NoisePreset;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ProfileFilesTest {
    @Test public void readsCanonicalUtf8Profile() throws Exception {
        assertEquals(NoisePreset.DEFAULT.name, ProfileFiles.read(new ByteArrayInputStream(
                NoisePreset.DEFAULT.toJson().getBytes(StandardCharsets.UTF_8))).name);
    }
    @Test public void oversizedFileIsRejected() {
        try { ProfileFiles.read(new ByteArrayInputStream(new byte[NoisePreset.MAX_JSON_BYTES + 1])); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("large")); }
    }
    @Test public void invalidUtf8IsRejected() {
        try { ProfileFiles.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28})); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("UTF-8")); }
    }
}
