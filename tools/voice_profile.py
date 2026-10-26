"""Compact source/filter speech-state fitting. Stores codebook statistics, never waveforms."""
import copy
import numpy as np
from build_adjusted_profile import decode
from profile_audio import ffmpeg_path,validate_profile


def lpc_envelope(frame,centers,rate=16000,order=16):
    x=frame-np.mean(frame);x=np.r_[x[0],x[1:]-.96*x[:-1]]*np.hanning(len(x))
    r=np.fft.irfft(abs(np.fft.rfft(x,2048))**2,2048)[:order+1]
    r[0]+=max(1e-8,float(r[0])*.001)
    a=np.array([1.]);error=max(r[0],1e-12)
    for i in range(1,order+1):
        reflection=float(np.clip(-(r[i]+np.dot(a[1:],r[i-1:0:-1]))/error,-.98,.98))
        a=np.r_[a,0]+reflection*np.r_[0,a[::-1]];error=max(1e-12,error*(1-reflection**2))
    response=np.exp(-2j*np.pi*centers[:,None]/rate*np.arange(len(a))[None,:])@a
    envelope=-np.log(np.maximum(abs(response),1e-8));return envelope-envelope.mean()


def enrich(profile,source,duration=120,ffmpeg=None,seed=17,states=24):
    profile=copy.deepcopy(profile);motion=profile['motion'];rate=16000
    audio=decode(source,rate,duration,ffmpeg_path(ffmpeg));channel=int(np.argmax(np.mean(audio**2,axis=0)));x=audio[:,channel]
    count=min(len(motion['pitchHz']),int(np.ceil(len(x)/rate/(motion['stepMs']/1000))))
    if count<16:raise ValueError('Voice training needs at least two seconds of audio.')
    centers=np.geomspace(100,7000,24);padded=np.pad(x,(512,512));envelopes=[];voicing=[]
    for i in range(count):
        start=int(i*motion['stepMs']/1000*rate);frame=padded[start:start+1024]
        if len(frame)<1024:frame=np.pad(frame,(0,1024-len(frame)))
        envelopes.append(lpc_envelope(frame,centers));lag=int(round(rate/motion['pitchHz'][i]))
        a,b=frame[:-lag],frame[lag:];correlation=np.dot(a,b)/max(1e-12,np.linalg.norm(a)*np.linalg.norm(b))
        voicing.append(float(np.clip((correlation-.15)/.7,0,1)))
    envelope=np.array(envelopes);residual=np.clip((envelope-envelope.mean(axis=0))*.8,np.log(.2),np.log(3))
    pitch=np.asarray(motion['pitchHz'][:count]);energy=np.asarray(motion['energy'][:count]);voicing=np.array(voicing)
    features=np.column_stack([residual,.6*(np.log(pitch)-np.log(pitch).mean()),energy-.5,voicing-.5])
    rng=np.random.default_rng(seed);k=min(states,max(4,count//6));centroids=features[rng.choice(count,k,replace=False)].copy()
    for _ in range(45):
        labels=np.argmin(((features[:,None,:]-centroids[None,:,:])**2).sum(axis=2),axis=1)
        updated=np.array([features[labels==i].mean(axis=0) if np.any(labels==i) else features[rng.integers(count)] for i in range(k)])
        if np.max(abs(updated-centroids))<1e-6:break
        centroids=updated
    active=np.unique(labels);mapping={int(old):i for i,old in enumerate(active)};labels=np.array([mapping[int(v)] for v in labels]);k=max(4,len(active))
    bank=[]
    for i in range(k):
        use=labels==i
        if not np.any(use):use=np.arange(count)==(i*count//k)%count
        bank.append({'pitchHz':round(float(np.exp(np.log(pitch[use]).mean())),2),'energy':round(float(energy[use].mean()),4),
                     'voicing':round(float(voicing[use].mean()),4),'gains':np.round(np.exp(residual[use].mean(axis=0)),4).tolist()})
    transitions=np.zeros((k,k),int)
    for a,b in zip(labels[:-1],labels[1:]):transitions[a,b]+=1
    profile['voice']={'version':1,'centersHz':np.round(centers,3).tolist(),'states':bank,
                      'transitions':transitions.tolist(),'weights':np.bincount(labels,minlength=k).tolist(),
                      'mix':0,'speechMix':.05,'tilt':-.25}
    profile=validate_profile(profile)
    return profile,{'states':k,'spectral_bands':24,'lpc_order':16,'frames':count,
                    'mean_voicing':float(voicing.mean()),'method':'LPC spectral-envelope codebook and non-cyclic transition statistics'}
