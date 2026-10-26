package app.wn.audio;

import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 335-parameter mixture density network. Pure Java inference; all outputs are stochastic. */
final class NeuralMotion {
    final double pitchMean,pitchScale,energyMean,energyScale;
    private final double[] w1,b1,w2,b2;
    private NeuralMotion(JSONObject o) throws JSONException {
        if(!"mdn1".equals(o.getString("type")))throw new IllegalArgumentException("Unsupported motion model.");
        pitchMean=number(o,"pitchMean",Math.log(60),Math.log(1000));pitchScale=number(o,"pitchScale",.005,2);
        energyMean=number(o,"energyMean",0,1);energyScale=number(o,"energyScale",.005,2);
        w1=array(o,"w1",64);b1=array(o,"b1",16);w2=array(o,"w2",240);b2=array(o,"b2",15);
    }
    static NeuralMotion parse(JSONObject o) throws JSONException { return o==null?null:new NeuralMotion(o); }
    private static double number(JSONObject o,String key,double lo,double hi) throws JSONException {
        Object value=o.get(key);
        if(!(value instanceof Number))throw new IllegalArgumentException("Invalid model value: "+key);
        double v=((Number)value).doubleValue();
        if(!Double.isFinite(v)||v<lo||v>hi)throw new IllegalArgumentException("Invalid model value: "+key);
        return v;
    }
    private static double[] array(JSONObject o,String key,int count) throws JSONException {
        JSONArray a=o.getJSONArray(key);if(a.length()!=count)throw new IllegalArgumentException("Invalid model matrix: "+key);
        double[] values=new double[count];
        for(int i=0;i<count;i++) {
            Object value=a.get(i);
            if(!(value instanceof Number))throw new IllegalArgumentException("Invalid model weight.");
            values[i]=((Number)value).doubleValue();
            if(!Double.isFinite(values[i])||Math.abs(values[i])>100)throw new IllegalArgumentException("Invalid model weight.");
        }
        return values;
    }
    void predict(double[] input,double[] hidden,double[] output) {
        for(int h=0;h<16;h++){double v=b1[h];for(int j=0;j<4;j++)v+=w1[h*4+j]*input[j];hidden[h]=Math.tanh(v);}
        for(int k=0;k<15;k++){double v=b2[k];for(int h=0;h<16;h++)v+=w2[k*16+h]*hidden[h];output[k]=v;}
    }
    void sample(double[] input,double[] hidden,double[] output,Random random,double[] result) {
        predict(input,hidden,output);
        double maximum=Math.max(output[0],Math.max(output[5],output[10]));
        double a=Math.exp(output[0]-maximum),b=Math.exp(output[5]-maximum),c=Math.exp(output[10]-maximum);
        double choice=random.nextDouble()*(a+b+c);int offset=choice<a?0:choice<a+b?5:10;
        result[0]=Math.max(-3,Math.min(3,output[offset+1]+Math.exp(Math.max(-3,Math.min(.5,output[offset+3])))*random.nextGaussian()));
        result[1]=Math.max(-3,Math.min(3,output[offset+2]+Math.exp(Math.max(-3,Math.min(.5,output[offset+4])))*random.nextGaussian()));
    }
    JSONObject toJson() throws JSONException {
        JSONObject o=new JSONObject().put("type","mdn1").put("pitchMean",pitchMean).put("pitchScale",pitchScale)
                .put("energyMean",energyMean).put("energyScale",energyScale);
        String[] names={"w1","b1","w2","b2"};double[][] values={w1,b1,w2,b2};
        for(int i=0;i<4;i++){JSONArray a=new JSONArray();for(double v:values[i])a.put(v);o.put(names[i],a);}
        return o;
    }
}
