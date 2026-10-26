#!/usr/bin/env python3
"""Compare the actual Java generators at equal RMS using STOI and ESTOI.

Uses a chronological fit/selection/test split. Lower scores mean less predicted
intelligibility. This is a digital, monaural proxy, not a human listening test.
"""
import argparse
import copy
import csv
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import numpy as np
from scipy.signal import resample_poly
from pystoi import stoi
import pyloudnorm as pyln
from build_adjusted_profile import build,decode
from profile_audio import ffmpeg_path
from train_motion_model import train
from voice_profile import enrich


def compile_renderer(root,out,java_home,json_jar):
    java=str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
    javac=str(Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
    if not java or not javac or not json_jar.is_file():raise ValueError('JDK 17 and --json-jar are required.')
    classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
    sources=list((root/'app/src/main/java/app/wn/audio').glob('*.java'))+[root/'tools/RenderMasker.java']
    subprocess.run([javac,'-cp',str(json_jar),'-d',str(classes),*[str(p) for p in sources]],check=True)
    return [java,'-cp',str(classes)+':'+str(json_jar),'RenderMasker']


def metric(clean,masker,db,clean_levels=None,masker_levels=None,normalization='rms'):
    clean_rms=np.sqrt(np.mean(clean**2));noise_rms=np.sqrt(np.mean(masker**2))
    if clean_rms<1e-8 or noise_rms<1e-8:raise ValueError('Silent signal cannot be compared.')
    if clean_levels is not None:
        gain=(clean_levels['rms']/masker_levels['rms'])*10**(db/20) if normalization=='rms' else 10**((clean_levels['lufs']+db-masker_levels['lufs'])/20)
    else:gain=(clean_rms/noise_rms)*10**(db/20)
    noise=masker*gain;mixture=clean+noise
    common_gain=.95/max(.95,float(np.max(np.abs(mixture))),float(np.max(np.abs(clean))))
    return {'stoi':float(stoi(clean*common_gain,mixture*common_gain,10000,extended=False)),
            'estoi':float(stoi(clean*common_gain,mixture*common_gain,10000,extended=True))}


def render(command,profile,mode,seed,seconds,out,label,voices=4):
    path=out/(label+'.json');path.write_text(json.dumps(profile))
    pcm=out/(label+'.f32');start=time.monotonic()
    subprocess.run([*command,str(path),str(pcm),str(seconds+2),str(seed),mode,str(voices)],check=True)
    data=np.fromfile(pcm,dtype='<f4')[96000:96000+round(seconds*48000)];pcm.unlink()
    data=data.astype(float)
    levels={'rms':float(np.sqrt(np.mean(data**2))),'lufs':float(pyln.Meter(48000).integrated_loudness(data))}
    return resample_poly(data,5,24),time.monotonic()-start,levels


def summarize(rows):
    return {key:float(np.mean([r[key] for r in rows])) for key in ['stoi','estoi']}


def write_summary(report,out):
    summary={k:v for k,v in report.items() if k!='trials'};summary['trial_count']=len(report['trials']);summary['paired_differences']=[]
    for candidate in [r['name'] for r in report['test'] if r['mode']=='VOICE']:
        for baseline in ['continuous-v1.3','oscillating-v1.3']:
            for normalization in ['rms','lufs']:
                differences=[]
                for seed in report['test_seeds']:
                    def scores(name):return [r['estoi'] for r in report['trials'] if r['stage']=='test' and r['candidate']==name and r['seed']==seed and r['normalization']==normalization]
                    differences.append(float(np.mean(scores(candidate))-np.mean(scores(baseline))))
                rng=np.random.default_rng(17);samples=np.mean(np.array(differences)[rng.integers(0,len(differences),size=(5000,len(differences)))],axis=1)
                summary['paired_differences'].append({'candidate':candidate,'baseline':baseline,'normalization':normalization,
                    'mean_estoi_difference':float(np.mean(differences)),'seed_bootstrap_95_interval':np.quantile(samples,[.025,.975]).tolist()})
    (out/'comparison-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    with (out/'trials.csv').open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=['stage','candidate','seed','normalization','masker_db','stoi','estoi']);writer.writeheader();writer.writerows(report['trials'])
    return summary


def compare(args):
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True);root=Path(__file__).resolve().parents[1]
    command=compile_renderer(root,out,args.java_home,args.json_jar.resolve())
    profile,_stats,*_=build(args.input,duration=args.fit_seconds)
    profile,training=train(profile,epochs=1800,device='cpu')
    profile,voice_stats=enrich(profile,args.input,duration=args.fit_seconds)
    (out/'fit-profile.json').write_text(json.dumps(profile,indent=2)+'\n')
    audio=decode(args.input,48000,0,ffmpeg_path());channel=int(np.argmax(np.mean(audio**2,axis=0)));audio=audio[:,channel]
    total=len(audio)/48000
    if total-args.select_end<3 or args.select_end-args.fit_seconds<3:raise ValueError('Need at least three seconds each for selection and test.')
    selection_full=audio[round(args.fit_seconds*48000):round(args.select_end*48000)];test_full=audio[round(args.select_end*48000):]
    selection=resample_poly(selection_full,5,24);test=resample_poly(test_full,5,24)
    def levels(x):return {'rms':float(np.sqrt(np.mean(x*x))),'lufs':float(pyln.Meter(48000).integrated_loudness(x))}
    selection_levels,test_levels=levels(selection_full),levels(test_full)
    candidates=[{'name':'continuous-v1.3','mode':'CONTINUOUS','mix':None,'tilt':None},
                {'name':'oscillating-v1.3','mode':'OSCILLATING','mix':None,'tilt':None}]
    for tilt in args.tilts:
        for mix in args.mixes:
            candidates.append({'name':f'voice-tilt{tilt:g}-mix{mix:g}','mode':'VOICE','mix':mix,'tilt':tilt})
    rows=[];results=[]
    for index,candidate in enumerate(candidates):
        current=copy.deepcopy(profile)
        if candidate['mode']=='VOICE':current['voice'].update(mix=candidate['mix'],tilt=candidate['tilt'])
        trials=[];compute=[]
        for seed in args.selection_seeds:
            noise,elapsed,noise_levels=render(command,current,candidate['mode'],seed,len(selection)/10000,out,f'select-{index}-{seed}',args.voices);compute.append(elapsed)
            for db in args.ratios:
                score=metric(selection,noise[:len(selection)],db,selection_levels,noise_levels);trials.append(score);rows.append({'stage':'selection','candidate':candidate['name'],'seed':seed,'normalization':'rms','masker_db':db,**score})
        result={**candidate,**summarize(trials),'compute_seconds_per_audio_second':float(np.mean(compute)/(len(selection)/10000+2))};results.append(result)
        print(json.dumps(result),flush=True)
    best=min(results,key=lambda x:x['estoi'])
    speech_best=min((r for r in results if r['mode']=='VOICE' and r['mix']>0),key=lambda x:x['estoi'])
    selected={r['name']:r for r in [results[0],results[1],best,speech_best]}
    test_results=[]
    for index,candidate in enumerate(selected.values()):
        current=copy.deepcopy(profile)
        if candidate['mode']=='VOICE':current['voice'].update(mix=candidate['mix'],tilt=candidate['tilt'])
        trials=[];secondary=[]
        for seed in args.test_seeds:
            noise,_,noise_levels=render(command,current,candidate['mode'],seed,len(test)/10000,out,f'test-{index}-{seed}',args.voices)
            for db in args.ratios:
                for normalization in ['rms','lufs']:
                    score=metric(test,noise[:len(test)],db,test_levels,noise_levels,normalization)
                    (trials if normalization=='rms' else secondary).append(score)
                    rows.append({'stage':'test','candidate':candidate['name'],'seed':seed,'normalization':normalization,'masker_db':db,**score})
        test_results.append({**candidate,**summarize(trials),'lufs_matched':summarize(secondary)})
    source_files=sorted((root/'app/src/main/java/app/wn/audio').glob('*.java'))+[root/'tools/RenderMasker.java']
    source_hash=hashlib.sha256(b''.join(p.read_bytes() for p in source_files)).hexdigest()
    report={'method':'full-band equal-RMS primary and LUFS-matched secondary, monaural actual Java synthesis, 2 s warmup, common base noise seeds, no clipping',
            'source_sha256':hashlib.sha256(args.input.read_bytes()).hexdigest(),'source_seconds':total,
            'fit_seconds':[0,args.fit_seconds],'selection_seconds':[args.fit_seconds,args.select_end],
            'test_seconds':[args.select_end,total],'selection_seeds':args.selection_seeds,'test_seeds':args.test_seeds,
            'audio_core_sha256':source_hash,'selection_reference_levels':selection_levels,'test_reference_levels':test_levels,
            'masker_over_source_db':args.ratios,'voice_streams':args.voices,'training':training,'voice_training':voice_stats,
            'selection':results,'selected_overall':best['name'],'selected_speech_like':speech_best['name'],'test':test_results,
            'trials':rows,'limitations':['One recording, not a separate speaker/session test.','Objective intelligibility proxies, not human word scores.',
                                       'No model of room acoustics, binaural listening, hardware limits, or linguistic/informational masking.'],
            'references':['https://doi.org/10.1109/TASLP.2016.2585878','https://github.com/mpariente/pystoi','https://github.com/csteinmetz1/pyloudnorm']}
    (out/'comparison.json').write_text(json.dumps(report,indent=2)+'\n')
    write_summary(report,out)
    print(json.dumps({'selected':best['name'],'speech_like':speech_best['name'],'held_out_test':test_results},indent=2),flush=True)
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--java-home');p.add_argument('--json-jar',type=Path,required=True)
    p.add_argument('--fit-seconds',type=float,default=20);p.add_argument('--select-end',type=float,default=26)
    p.add_argument('--tilts',type=float,nargs='+',default=[-1,-.5,-.25,0,.25]);p.add_argument('--mixes',type=float,nargs='+',default=[0,.05,.1,.25,.5])
    p.add_argument('--ratios',type=float,nargs='+',default=[0,3,6]);p.add_argument('--voices',type=int,default=4)
    p.add_argument('--selection-seeds',type=int,nargs='+',default=[11,22,33,44,55,66,77,88,99,110]);p.add_argument('--test-seeds',type=int,nargs='+',default=[101,202,303,404,505,606,707,808,909,1010,1111,1212,1313,1414,1515,1616,1717,1818,1919,2020])
    compare(p.parse_args())


if __name__=='__main__':main()
