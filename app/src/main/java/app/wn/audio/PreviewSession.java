package app.wn.audio;

/** Plays a clip once alongside live synthesis, with a measured RMS-relative source level. */
public final class PreviewSession {
    public final String id=java.util.UUID.randomUUID().toString();
    public enum Monitor { MIX, SOURCE, MASKER }
    public final PreviewClip clip;
    public volatile Monitor monitor=Monitor.MIX;
    public volatile double balanceDb=6;
    public volatile boolean finished;
    private volatile boolean resetCalibration;
    private volatile int position;
    private double maskPower,sourceGain,sourceMix=1,maskMix=1,limiter=1;
    private double calibrationSum;
    private long calibrationFrames,calibrationSamples;
    public PreviewSession(PreviewClip clip) {this.clip=clip;}
    public double seconds(){return position/48000.0;}
    public void recalibrate(){resetCalibration=true;}
    public void render(float[] stereo,double duck,double master) {
        if(finished)return;
        double power=0;for(float v:stereo)power+=(double)v*v;
        double base=duck*master;
        double dryPower=base>.00001?power/stereo.length/(base*base):0;
        if(resetCalibration){calibrationFrames=calibrationSamples=0;calibrationSum=0;resetCalibration=false;}
        if(calibrationFrames<48000) {
            int frames=stereo.length/2;
            if(calibrationFrames>=4800){calibrationSum+=dryPower*frames;calibrationSamples+=frames;}
            maskPower=calibrationSamples>0?calibrationSum/calibrationSamples:dryPower;
            calibrationFrames+=frames;
        }
        double ratio=Math.pow(10,Math.max(-12,Math.min(24,balanceDb))/20);
        double targetGain=base>0?Math.min(4,Math.sqrt(maskPower)/(clip.rms*ratio))*base:0;
        Monitor mode=monitor;double sourceTarget=mode==Monitor.MASKER?0:1,maskTarget=mode==Monitor.SOURCE?0:1;
        double peak=0;
        for(int i=0;i<stereo.length/2;i++) {
            sourceGain+=(targetGain-sourceGain)*.002;
            sourceMix+=(sourceTarget-sourceMix)*.002;maskMix+=(maskTarget-maskMix)*.002;
            double fade=Math.min(1,Math.max(0,(clip.frames-position)/480.0));
            for(int c=0;c<2;c++) {
                double sample=position<clip.frames?clip.sample(position,c):0;
                double value=stereo[i*2+c]*maskMix+sample*sourceGain*sourceMix*fade;
                stereo[i*2+c]=(float)value;peak=Math.max(peak,Math.abs(value));
            }
            if(position<clip.frames)position++;
        }
        double safe=peak>.96?.96/peak:1;limiter=safe<limiter?safe:Math.min(safe,limiter+.01);
        for(int i=0;i<stereo.length;i++)stereo[i]*=(float)limiter;
        finished=position>=clip.frames;
    }
}
