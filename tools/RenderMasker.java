import app.wn.audio.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Offline rendering of the actual Android synthesis core, for reproducible comparisons. */
public final class RenderMasker {
    public static void main(String[] args) throws Exception {
        if(args.length<5)throw new IllegalArgumentException("profile.json output.f32 seconds seed CONTINUOUS|OSCILLATING|VOICE [voices]");
        NoisePreset preset=NoisePreset.parse(Files.readString(Path.of(args[0])));
        int frames=(int)Math.ceil(Double.parseDouble(args[2])*48000),count=args.length>5?Integer.parseInt(args[5]):4;
        Oscillation controls="VOICE".equals(args[4])&&preset.voice!=null?preset.voice.defaults().with(6,count):new Oscillation(1,1,1,1,0,1,count);
        MixerConfig config=new MixerConfig(Collections.singletonList(new MixerConfig.Generator("default",preset,true,1,MixerConfig.Texture.valueOf(args[4]),controls)),1,MixerConfig.Width.MONO);
        NoiseMixer mixer=new NoiseMixer(Long.parseLong(args[3]));float[] buffer=new float[NoiseMixer.FRAMES*2];
        ByteBuffer bytes=ByteBuffer.allocate(NoiseMixer.FRAMES*4).order(ByteOrder.LITTLE_ENDIAN);
        try(OutputStream out=Files.newOutputStream(Path.of(args[1]))) {
            for(int written=0;written<frames;written+=NoiseMixer.FRAMES) {
                mixer.render(buffer,config,1);bytes.clear();int n=Math.min(NoiseMixer.FRAMES,frames-written);
                for(int i=0;i<n;i++)bytes.putFloat(buffer[i*2]);out.write(bytes.array(),0,n*4);
            }
        }
    }
}
