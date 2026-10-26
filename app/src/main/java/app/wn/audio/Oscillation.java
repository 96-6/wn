package app.wn.audio;

import org.json.JSONException;
import org.json.JSONObject;

/** Per-generator controls. 100% tone/noise/spread retains the 1.2 balance. */
public final class Oscillation {
    public static final Oscillation DEFAULT = new Oscillation(1,1,1,1,0,1);
    public final double tone, noise, speed, variation, semitones, spread;
    public Oscillation(double tone,double noise,double speed,double variation,double semitones,double spread) {
        this.tone=bound(tone,0,2,1);this.noise=bound(noise,0,2,1);this.speed=bound(speed,.25,4,1);
        this.variation=bound(variation,0,2,1);this.semitones=bound(semitones,-12,12,0);this.spread=bound(spread,0,2,1);
    }
    private static double bound(double v,double lo,double hi,double fallback) {
        return Double.isFinite(v)?Math.max(lo,Math.min(hi,v)):fallback;
    }
    public double value(int field) {
        switch(field){case 0:return tone;case 1:return noise;case 2:return speed;case 3:return variation;
            case 4:return semitones;case 5:return spread;default:throw new IllegalArgumentException("Unknown control");}
    }
    public Oscillation with(int field,double value) {
        return new Oscillation(field==0?value:tone,field==1?value:noise,field==2?value:speed,
                field==3?value:variation,field==4?value:semitones,field==5?value:spread);
    }
    public static Oscillation parse(JSONObject object) {
        return object==null?DEFAULT:new Oscillation(object.optDouble("tone",1),object.optDouble("noise",1),
                object.optDouble("speed",1),object.optDouble("variation",1),object.optDouble("semitones",0),object.optDouble("spread",1));
    }
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("tone",tone).put("noise",noise).put("speed",speed).put("variation",variation)
                .put("semitones",semitones).put("spread",spread);
    }
}
