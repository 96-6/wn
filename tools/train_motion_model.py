#!/usr/bin/env python3
"""Train a tiny stochastic pitch/energy network for WN. CPU NumPy or optional CUDA PyTorch."""
import argparse
import copy
import json
import math
import os
from pathlib import Path
import time
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
import numpy as np
from profile_audio import validate_profile

HIDDEN, MIXTURES = 16, 3


def data_from_motion(motion):
    pitch=np.log(np.asarray(motion['pitchHz'],dtype=float)); energy=np.asarray(motion['energy'],dtype=float)
    if len(pitch)<16: raise ValueError('Training needs at least 16 motion points.')
    center=np.array([pitch.mean(),energy.mean()]); scale=np.array([max(pitch.std(),.12),max(energy.std(),.15)])
    states=np.column_stack([pitch,energy]); normalized=np.clip((states-center)/scale,-3,3)
    # Four inputs: current normalized state and the preceding state increment.
    x=np.column_stack([normalized[1:-1],np.clip(normalized[1:-1]-normalized[:-2],-2,2)])
    return x,normalized[2:],center,scale


def initial(seed):
    rng=np.random.default_rng(seed)
    return [rng.normal(0,.2,(HIDDEN,4)),np.zeros(HIDDEN),rng.normal(0,.08,(MIXTURES*5,HIDDEN)),
            np.tile([0,0,0,-.7,-.7],MIXTURES).astype(float)]


def numpy_loss(parameters,x,y,gradient=False):
    w1,b1,w2,b2=parameters; h=np.tanh(x@w1.T+b1); raw=(h@w2.T+b2).reshape(-1,MIXTURES,5)
    logits=raw[:,:,0]; logits=logits-logits.max(axis=1,keepdims=True)
    log_pi=logits-np.log(np.exp(logits).sum(axis=1,keepdims=True))
    means=raw[:,:,1:3]; log_std=np.clip(raw[:,:,3:5],-3,.5); variance=np.exp(2*log_std)
    difference=means-y[:,None,:]
    log_probability=log_pi-.5*(difference**2/variance).sum(axis=2)-log_std.sum(axis=2)-math.log(2*math.pi)
    maximum=log_probability.max(axis=1,keepdims=True)
    responsibility=np.exp(log_probability-maximum); totals=responsibility.sum(axis=1,keepdims=True)
    loss=-float(np.mean(maximum[:,0]+np.log(totals[:,0])))
    if not gradient:return loss
    responsibility/=totals
    derivative=np.empty_like(raw);derivative[:,:,0]=np.exp(log_pi)-responsibility
    derivative[:,:,1:3]=responsibility[:,:,None]*difference/variance
    derivative[:,:,3:5]=responsibility[:,:,None]*(1-difference**2/variance)*((raw[:,:,3:5]>-3)&(raw[:,:,3:5]<.5))
    derivative=derivative.reshape(-1,MIXTURES*5)/len(x)
    dh=(derivative@w2)*(1-h*h)
    gradients=[dh.T@x,dh.sum(axis=0),derivative.T@h,derivative.sum(axis=0)]
    return loss,gradients


def train_numpy(x,y,epochs,seed):
    p=initial(seed); first=numpy_loss(p,x,y);m=[np.zeros_like(a) for a in p];v=[a.copy() for a in m]
    for step in range(1,epochs+1):
        _,g=numpy_loss(p,x,y,True); norm=math.sqrt(sum(float((a*a).sum()) for a in g));factor=min(1,5/max(norm,1e-9))
        for i in range(4):
            g[i]=g[i]*factor+1e-4*p[i];m[i]=.9*m[i]+.1*g[i];v[i]=.999*v[i]+.001*g[i]**2
            p[i]-=.004*(m[i]/(1-.9**step))/(np.sqrt(v[i]/(1-.999**step))+1e-8)
    return p,first,numpy_loss(p,x,y)


def train_cuda(x,y,epochs,seed):
    import torch
    if not torch.cuda.is_available():raise ValueError('CUDA is unavailable. Choose --device cpu or --device auto.')
    torch.manual_seed(seed)
    p=[torch.nn.Parameter(torch.tensor(a,dtype=torch.float32,device='cuda')) for a in initial(seed)]
    a=torch.tensor(x,dtype=torch.float32,device='cuda');b=torch.tensor(y,dtype=torch.float32,device='cuda')
    optimizer=torch.optim.Adam(p,lr=.004,weight_decay=1e-4)
    def loss():
        w1,b1,w2,b2=p;raw=(torch.tanh(a@w1.T+b1)@w2.T+b2).reshape(-1,MIXTURES,5)
        log_pi=torch.log_softmax(raw[:,:,0],dim=1);std=raw[:,:,3:5].clamp(-3,.5)
        log_probability=log_pi-.5*((raw[:,:,1:3]-b[:,None,:])**2*torch.exp(-2*std)).sum(2)-std.sum(2)-math.log(2*math.pi)
        return -torch.logsumexp(log_probability,dim=1).mean()
    first=float(loss().detach().cpu())
    for _ in range(epochs):
        optimizer.zero_grad();result=loss();result.backward();torch.nn.utils.clip_grad_norm_(p,5);optimizer.step()
    return [a.detach().cpu().numpy().astype(float) for a in p],first,float(loss().detach().cpu())


def train(profile,epochs=1500,seed=17,device='auto'):
    profile=copy.deepcopy(validate_profile(profile)); motion=profile.get('motion')
    if not motion:raise ValueError('The profile needs motion data; use an audio input or build_adjusted_profile.py first.')
    x,y,center,scale=data_from_motion(motion); backend='numpy-cpu'
    if device in ['cuda','auto']:
        try:
            import torch
            if torch.cuda.is_available():backend='pytorch-cuda'
            elif device=='cuda':raise ValueError('CUDA is unavailable.')
        except ImportError:
            if device=='cuda':raise ValueError('Install PyTorch for CUDA, or use --device cpu.')
    started=time.monotonic();parameters,before,after=(train_cuda if backend=='pytorch-cuda' else train_numpy)(x,y,epochs,seed)
    model={'type':'mdn1','pitchMean':float(center[0]),'pitchScale':float(scale[0]),
           'energyMean':float(center[1]),'energyScale':float(scale[1])}
    for key,value in zip(['w1','b1','w2','b2'],parameters):model[key]=np.round(value.ravel(),7).tolist()
    motion['model']=model
    profile=validate_profile(profile)
    stats={'backend':backend,'epochs':epochs,'examples':len(x),'parameters':sum(a.size for a in parameters),
           'negative_log_likelihood_before':before,'negative_log_likelihood_after':after,
           'seconds':time.monotonic()-started,'seed':seed,'model':'4 → 16 tanh → 3 bivariate Gaussian mixtures'}
    return profile,stats


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path)
    p.add_argument('-o','--output',type=Path,required=True);p.add_argument('--report',type=Path)
    p.add_argument('--device',choices=['auto','cpu','cuda'],default='auto');p.add_argument('--epochs',type=int,default=1500)
    p.add_argument('--seed',type=int,default=17);p.add_argument('--force',action='store_true')
    args=p.parse_args()
    if not 1<=args.epochs<=100000:p.error('Epochs must be 1–100000.')
    for path in [args.output,args.report]:
        if path and path.exists() and not args.force:p.error(str(path)+' exists; use --force to replace it.')
    if args.input.suffix.lower()=='.json':profile=json.loads(args.input.read_text(encoding='utf-8-sig'))
    else:
        from build_adjusted_profile import build
        profile,*_=build(args.input)
    output,stats=train(profile,args.epochs,args.seed,args.device)
    if args.input.suffix.lower()!='.json':
        from voice_profile import enrich
        output,voice_stats=enrich(output,args.input,seed=args.seed)
        stats['voice_model']=voice_stats
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(stats,indent=2)+'\n')
    print(json.dumps(stats,indent=2))


if __name__=='__main__':main()
