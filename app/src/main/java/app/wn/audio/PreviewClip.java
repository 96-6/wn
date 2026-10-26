package app.wn.audio;

import java.util.ArrayList;

/** Bounded, in-memory mono/stereo preview resampled to the output rate. */
public final class PreviewClip {
    private static final int CHUNK=2048,MAX_FRAMES=60*48000;
    private final float[][] chunks;
    public final int frames;
    public final double rms;
    private PreviewClip(ArrayList<float[]> chunks,int frames,double energy) {
        this.chunks=chunks.toArray(new float[0][]);this.frames=frames;rms=Math.sqrt(energy/Math.max(1,frames*2));
    }
    float sample(int frame,int channel) {return chunks[frame/CHUNK][(frame%CHUNK)*2+channel];}
    public static final class Builder {
        private final ArrayList<float[]> chunks=new ArrayList<>();
        private int rate,channels,frames;
        private long inputFrame;
        private double next,left,right,energy;
        public void accept(float[] data,int length,int sampleRate,int channelCount) {
            if(channelCount<1||channelCount>2)throw new IllegalArgumentException("Preview supports mono/stereo audio. Convert surround files first.");
            if(sampleRate<8000||sampleRate>384000||length%channelCount!=0)throw new IllegalArgumentException("Invalid preview PCM.");
            if(rate==0){rate=sampleRate;channels=channelCount;}
            if(rate!=sampleRate||channels!=channelCount)throw new IllegalArgumentException("Preview audio changed format.");
            double step=rate/48000.0;
            for(int i=0;i<length;i+=channels,inputFrame++) {
                double l=data[i],r=channels==1?l:data[i+1];
                if(!Double.isFinite(l)||!Double.isFinite(r))throw new IllegalArgumentException("Non-finite preview audio.");
                if(inputFrame==0){left=l;right=r;}
                while(next<=inputFrame && frames<MAX_FRAMES) {
                    double t=inputFrame==0?1:Math.max(0,Math.min(1,next-(inputFrame-1)));
                    append(left+(l-left)*t,right+(r-right)*t);next+=step;
                }
                left=l;right=r;
            }
        }
        private void append(double l,double r) {
            if(frames%CHUNK==0)chunks.add(new float[CHUNK*2]);
            float[] block=chunks.get(chunks.size()-1);int index=(frames%CHUNK)*2;
            block[index]=(float)l;block[index+1]=(float)r;energy+=l*l+r*r;frames++;
        }
        public PreviewClip finish() {
            if(frames<24000||energy<1e-15)throw new IllegalArgumentException("Choose at least half a second of audible audio.");
            return new PreviewClip(chunks,frames,energy);
        }
    }
}
