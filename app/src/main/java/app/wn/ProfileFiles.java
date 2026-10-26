package app.wn;

import app.wn.audio.NoisePreset;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class ProfileFiles {
    private ProfileFiles() {}
    public static NoisePreset read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("The selected file could not be opened.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > NoisePreset.MAX_JSON_BYTES) throw new IOException("Profile is too large (maximum 256 KiB).");
            bytes.write(buffer, 0, count);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return NoisePreset.parse(text);
        } catch (CharacterCodingException e) { throw new IOException("Save the JSON profile using UTF-8 encoding.", e); }
    }
}
