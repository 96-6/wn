package app.wn.audio;

import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Learned vocal-tract envelope states and transition counts, without an audio timeline. */
public final class VoiceModel {
    public final int count;
    public final double mix,speechMix,tilt;
    final double[] centers,pitch,energy,voicing,weights;
    final double[][] gains,transitions;
    private final int[] fftLow=new int[NoiseMixer.FFT_SIZE/2];
    private final double[] fftBlend=new double[NoiseMixer.FFT_SIZE/2];
    private VoiceModel(JSONObject o) throws JSONException {
        if(number(o,"version",1,1)!=1)throw new IllegalArgumentException("Unsupported voice model.");
        JSONArray states=o.getJSONArray("states");count=states.length();
        if(count<4||count>32)throw new IllegalArgumentException("Voice model needs 4–32 states.");
        centers=array(o.getJSONArray("centersHz"),24,60,22000);
        for(int i=1;i<24;i++)if(centers[i]<=centers[i-1])throw new IllegalArgumentException("Voice centers must increase.");
        for(int k=0;k<fftLow.length;k++) {double hz=k*(double)NoiseMixer.SAMPLE_RATE/NoiseMixer.FFT_SIZE;int low=0;
            while(low<22&&hz>centers[low+1])low++;fftLow[k]=low;
            fftBlend[k]=Math.max(0,Math.min(1,Math.log(Math.max(centers[0],hz)/centers[low])/Math.log(centers[low+1]/centers[low])));}
        mix=o.has("mix")?number(o,"mix",0,1):0;tilt=o.has("tilt")?number(o,"tilt",-2,2):-.25;
        speechMix=o.has("speechMix")?number(o,"speechMix",0,1):.05;
        pitch=new double[count];energy=new double[count];voicing=new double[count];gains=new double[count][];
        for(int i=0;i<count;i++) {
            JSONObject s=states.getJSONObject(i);pitch[i]=number(s,"pitchHz",60,1000);energy[i]=number(s,"energy",0,1);
            voicing[i]=number(s,"voicing",0,1);gains[i]=array(s.getJSONArray("gains"),24,.05,5);
        }
        weights=array(o.getJSONArray("weights"),count,0,100000);double total=0;for(double v:weights)total+=v;
        if(total<=0)throw new IllegalArgumentException("Voice weights must be positive.");
        for(int i=0;i<count;i++)weights[i]/=total;
        JSONArray rows=o.getJSONArray("transitions");if(rows.length()!=count)throw new IllegalArgumentException("Invalid voice transition matrix.");
        transitions=new double[count][];
        for(int i=0;i<count;i++){transitions[i]=array(rows.getJSONArray(i),count,0,100000);double sum=0;for(double v:transitions[i])sum+=v;
            for(int j=0;j<count;j++)transitions[i][j]=sum>0?transitions[i][j]/sum:weights[j];}
    }
    static VoiceModel parse(JSONObject o) throws JSONException{return o==null?null:new VoiceModel(o);}
    public Oscillation defaults(){return new Oscillation(Math.sqrt(mix)/.65,Math.sqrt(1-mix),1,1,0,1,4);}
    public Oscillation speechDefaults(){return new Oscillation(Math.sqrt(speechMix)/.65,Math.sqrt(1-speechMix),1,1,0,1,4);}
    private static double number(JSONObject o,String key,double lo,double hi) throws JSONException {
        Object raw=o.get(key);if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid voice value: "+key);
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<lo||value>hi)throw new IllegalArgumentException("Invalid voice value: "+key);return value;
    }
    private static double[] array(JSONArray a,int length,double lo,double hi) throws JSONException {
        if(a.length()!=length)throw new IllegalArgumentException("Invalid voice array dimensions.");double[] values=new double[length];
        for(int i=0;i<length;i++){Object raw=a.get(i);if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid voice array value.");
            values[i]=((Number)raw).doubleValue();if(!Double.isFinite(values[i])||values[i]<lo||values[i]>hi)throw new IllegalArgumentException("Voice array value out of range.");}return values;
    }
    double gain(int state,double hz) {
        return gain(gains[state],hz);
    }
    double gain(double[] shape,double hz) {if(hz<=centers[0])return shape[0];
        for(int i=1;i<24;i++)if(hz<=centers[i]){double t=Math.log(hz/centers[i-1])/Math.log(centers[i]/centers[i-1]);return shape[i-1]*(1-t)+shape[i]*t;}return shape[23];}
    double binGain(double[] shape,int k){int i=fftLow[k];return shape[i]*(1-fftBlend[k])+shape[i+1]*fftBlend[k];}
    int choose(Random random,int previous,double f0,double loudness,double[] probabilities) {
        double sum=0;
        for(int i=0;i<count;i++) {
            double p=previous<0?weights[i]:.88*transitions[previous][i]+.12*weights[i];
            double distance=Math.log(f0/pitch[i]);p*=.3+.7*Math.exp(-distance*distance/ .35-.8*Math.abs(loudness-energy[i]));
            probabilities[i]=p;sum+=p;
        }
        double pick=random.nextDouble()*sum;
        for(int i=0;i<count-1;i++){pick-=probabilities[i];if(pick<=0)return i;}return count-1;
    }
    JSONObject toJson() throws JSONException {
        JSONObject o=new JSONObject().put("version",1).put("mix",mix).put("speechMix",speechMix).put("tilt",tilt).put("centersHz",json(centers)).put("weights",json(weights));
        JSONArray states=new JSONArray(),rows=new JSONArray();for(int i=0;i<count;i++){
            states.put(new JSONObject().put("pitchHz",pitch[i]).put("energy",energy[i]).put("voicing",voicing[i]).put("gains",json(gains[i])));rows.put(json(transitions[i]));}
        return o.put("states",states).put("transitions",rows);
    }
    private static JSONArray json(double[] values) throws JSONException{JSONArray a=new JSONArray();for(double value:values)a.put(value);return a;}
}
