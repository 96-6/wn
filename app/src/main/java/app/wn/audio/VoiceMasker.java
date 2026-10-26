package app.wn.audio;

import java.util.Arrays;
import java.util.Random;

/** Random source/filter pseudo-voices plus a continuous, speech-weighted noise bed. */
final class VoiceMasker {
    private static final int N=NoiseMixer.FFT_SIZE,F=NoiseMixer.FRAMES,RATE=NoiseMixer.SAMPLE_RATE;
    private final long seed;
    private final NoiseMixer.Source bedM,bedS,breathM,breathS;
    private final double[] bm=new double[F],bs=new double[F],um=new double[F],us=new double[F];
    private final double[] noiseGains=new double[N/2],breathGains=new double[N/2];
    private final double[] frequencies=new double[N/2],profileGains=new double[N/2];
    private final PseudoVoice[] voices=new PseudoVoice[4];
    private NoisePreset previous;
    private double referencePower,voicePower,normalizer=1,tone=1,noise=1;
    VoiceMasker(long seed) {
        this.seed=seed;bedM=new NoiseMixer.Source(seed^0x12345678L);bedS=new NoiseMixer.Source(seed^0x76a2b345L);
        breathM=new NoiseMixer.Source(seed^17);breathS=new NoiseMixer.Source(seed^18);
        for(int k=0;k<N/2;k++)frequencies[k]=k*(double)RATE/N;
    }
    void render(NoisePreset p,double[] mid,double[] side,Oscillation c) {
        if(p.voice==null||p.motion==null)throw new IllegalArgumentException("This profile has no voice model.");
        if(previous!=p) {
            double squares=0,tilted=0;
            for(int k=0;k<N/2;k++){double g=p.gainAt(frequencies[k]);profileGains[k]=g;squares+=g*g;
                double eq=Math.max(.25,Math.min(4,Math.pow(Math.max(80,frequencies[k])/800,p.voice.tilt)));
                noiseGains[k]=g*eq;tilted+=noiseGains[k]*noiseGains[k];}
            double scale=tilted>0?Math.sqrt(squares/tilted):0;
            for(int k=0;k<N/2;k++)noiseGains[k]*=scale;
            bedM.setGains(noiseGains);bedS.setGains(noiseGains);referencePower=2*squares/N;voicePower=referencePower;
            for(int i=0;i<4;i++)voices[i]=new PseudoVoice(p,seed^(0x1a2b3c4dL*(i+1)),i,referencePower);
            tone=c.tone;noise=c.noise;
            previous=p;
        }
        bedM.render(bm);bedS.render(bs);Arrays.fill(mid,0);Arrays.fill(side,0);Arrays.fill(breathGains,0);
        if(c.tone==0&&tone<.00001){for(int i=0;i<F;i++){noise+=(c.noise-noise)*.0005;mid[i]=bm[i]*noise;side[i]=bs[i]*noise;}return;}
        double unvoiced=0;
        for(int i=0;i<4;i++) {
            PseudoVoice v=voices[i];boolean active=i<c.voices;
            v.render(mid,side,c,active?1:0,1/Math.sqrt(c.voices));
            if(active) {
                double weight=(.2+.8*v.motion.energy)*Math.sqrt(Math.max(0,1-v.model.voicing[v.state]*v.model.voicing[v.state]));
                unvoiced+=weight*weight/c.voices;
                for(int k=1;k<N/2;k++)breathGains[k]+=v.model.binGain(v.shape,k)*weight/c.voices;
            }
        }
        double squares=0;for(int k=1;k<N/2;k++){breathGains[k]*=profileGains[k];squares+=breathGains[k]*breathGains[k];}
        double desired=referencePower*N/2,scale=squares>1e-15?Math.sqrt(desired/squares):0;
        for(int k=1;k<N/2;k++)breathGains[k]*=scale;
        breathM.setGains(breathGains);breathS.setGains(breathGains);breathM.render(um);breathS.render(us);
        double breath=.75*Math.sqrt(unvoiced),energy=0;
        for(int i=0;i<F;i++){mid[i]+=um[i]*breath;side[i]+=us[i]*breath;energy+=mid[i]*mid[i]+side[i]*side[i];}
        voicePower+=(energy/(2*F)-voicePower)*.08;
        double target=voicePower>1e-12?Math.max(.25,Math.min(4,Math.sqrt(referencePower/voicePower))):1;
        double a=1,b=.65;
        for(int i=0;i<F;i++) {
            normalizer+=(target-normalizer)*.0005;tone+=(c.tone-tone)*.0005;noise+=(c.noise-noise)*.0005;
            mid[i]=bm[i]*a*noise+mid[i]*normalizer*b*tone;
            side[i]=bs[i]*a*noise+side[i]*normalizer*b*tone;
        }
    }
    private static final class PseudoVoice {
        private static final int H=128;
        final VoiceModel model;
        final RandomMotion motion;
        final Random random;
        final double[] probabilities,weights=new double[H+1],targets=new double[H+1];
        final double[] shape=new double[24];
        final NoisePreset preset;
        final double reference,offset,maxHz;
        int state=-1;
        double remaining,phase,pitch,level,voicing,activeGain;
        PseudoVoice(NoisePreset p,long seed,int index,double referencePower) {
            model=p.voice;preset=p;random=new Random(seed);motion=new RandomMotion(p.motion,seed^1234567);
            probabilities=new double[model.count];reference=Math.sqrt(referencePower);offset=(index-1.5)/2.2;
            state=model.choose(random,-1,motion.pitch,motion.energy,probabilities);phase=random.nextDouble()*Math.PI*2;
            pitch=motion.pitch;level=motion.energy;voicing=model.voicing[state];
            NoisePreset.Band[] bands=p.frequencyBands();maxHz=bands.length>0?bands[bands.length-1].highHz:22000;
        }
        void render(double[] mid,double[] side,Oscillation c,double active,double streamsGain) {
            double span=F/(double)RATE,a=motion.pitch;motion.advance(span,c);double b=motion.pitch;
            remaining-=span*c.speed;
            while(remaining<=0){state=model.choose(random,state,b,motion.energy,probabilities);remaining+=preset.motion.stepSeconds*(.6+random.nextDouble()*1.4);}
            for(int k=0;k<24;k++)shape[k]=Math.pow(model.gains[state][k],c.variation);
            double detune=Math.pow(2,offset*c.spread*.06);a*=detune;b*=detune;
            double center=(a+b)*.5;
            int harmonics=Math.max(1,Math.min(H,Math.min((int)Math.ceil(maxHz/Math.min(a,b))+1,(int)(21500/Math.max(pitch,Math.max(a,b))))));
            double squares=0;
            for(int h=1;h<=harmonics;h++) {double hz=center*h;targets[h]=preset.gainAt(hz)*model.gain(shape,hz);squares+=targets[h]*targets[h];}
            double scale=squares>1e-15?reference/Math.sqrt(squares*.5):0;
            double pan=Math.max(-1,Math.min(1,offset*c.spread)),m=Math.cos(pan*Math.PI/4),s=Math.sin(pan*Math.PI/4);
            for(int i=0;i<F;i++) {
                pitch+=((a+(b-a)*i/F)-pitch)*.001;level+=(motion.energy-level)*.001;voicing+=(model.voicing[state]-voicing)*.001;
                activeGain+=(active-activeGain)*.001;phase+=2*Math.PI*pitch/RATE;if(phase>=2*Math.PI)phase-=2*Math.PI;
                double current=Math.sin(phase),last=0,twice=2*Math.cos(phase),sample=0;
                for(int h=1;h<=harmonics;h++){weights[h]+=(targets[h]-weights[h])*.001;sample+=weights[h]*current;double next=twice*current-last;last=current;current=next;}
                double value=sample*scale*voicing*(.2+.8*level)*activeGain*streamsGain;
                mid[i]+=value*m;side[i]+=value*s;
            }
        }
    }
}
