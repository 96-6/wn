package app.wn.audio;

import java.util.Random;

/** Generates fresh control events; never advances through or wraps the source contour. */
final class RandomMotion {
    private final MotionShape shape;
    private final Random random;
    private final double[] input=new double[4],hidden=new double[16],output=new double[15],sample=new double[2];
    private double p,e,dp,de,remaining;
    double pitch,energy;
    RandomMotion(MotionShape shape,long seed) {
        this.shape=shape;random=new Random(seed);anchor();publish(Oscillation.DEFAULT);
    }
    private void anchor() {
        int index=random.nextInt(shape.size);NeuralMotion model=shape.model;
        p=model==null?shape.logPitch(index):(shape.logPitch(index)-model.pitchMean)/model.pitchScale;
        e=model==null?shape.energy(index):(shape.energy(index)-model.energyMean)/model.energyScale;
        if(model!=null){p=Math.max(-3,Math.min(3,p));e=Math.max(-3,Math.min(3,e));}
        dp=de=0;
    }
    void advance(double seconds,Oscillation controls) {
        remaining-=seconds*controls.speed;
        while(remaining<=0) {
            double oldP=p,oldE=e;
            if(shape.model!=null && random.nextDouble()>.08) {
                input[0]=p;input[1]=e;input[2]=dp;input[3]=de;
                shape.model.sample(input,hidden,output,random,sample);p=sample[0];e=sample[1];
            } else if(shape.model!=null) anchor();
            else {
                // Conditional resampling for older profiles without a trained network.
                int best=random.nextInt(shape.size);double distance=Double.POSITIVE_INFINITY;
                for(int trial=0;trial<20;trial++) {
                    int i=random.nextInt(shape.size-1);
                    double d=Math.abs(shape.logPitch(i)-p)+.4*Math.abs(shape.energy(i)-e)+random.nextDouble()*.04;
                    if(d<distance){distance=d;best=i+1;}
                }
                p=shape.logPitch(best)+random.nextGaussian()*.012;e=shape.energy(best)+random.nextGaussian()*.025;
                if(random.nextDouble()<.1)anchor();
            }
            dp=Math.max(-2,Math.min(2,p-oldP));de=Math.max(-2,Math.min(2,e-oldE));
            remaining+=shape.stepSeconds*(.55+random.nextDouble()*1.1);
        }
        publish(controls);
    }
    private void publish(Oscillation controls) {
        NeuralMotion model=shape.model;
        double logPitch=model==null?p:model.pitchMean+p*model.pitchScale;
        double loudness=model==null?e:model.energyMean+e*model.energyScale;
        logPitch=shape.meanLogPitch+(logPitch-shape.meanLogPitch)*controls.variation;
        loudness=shape.meanEnergy+(loudness-shape.meanEnergy)*controls.variation;
        pitch=Math.max(50,Math.min(2000,Math.exp(logPitch)*Math.pow(2,controls.semitones/12)));
        energy=Math.max(0,Math.min(1,loudness));
    }
}
