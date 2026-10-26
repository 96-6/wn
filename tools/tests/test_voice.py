import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import voice_profile
import profile_audio as wn
import build_adjusted_profile


class VoiceTests(unittest.TestCase):
    def profile(self):return json.loads((Path(__file__).resolve().parents[2]/'presets/adjusted.json').read_text())
    def test_import_export_preserves_voice_model(self):
        profile=self.profile();self.assertIn('voice',profile)
        self.assertEqual(wn.validate_profile(profile),wn.validate_profile(json.loads(json.dumps(profile))))
        self.assertGreater(np.std([s['gains'] for s in profile['voice']['states']]),.1)
    def test_bad_state_or_transition_data_is_rejected(self):
        profile=self.profile()
        for field,value in [('centersHz',[100]),('states',[]),('mix',1.1),('weights',[-1]*24),('version',True)]:
            broken=copy.deepcopy(profile);broken['voice'][field]=value
            with self.assertRaises(wn.ProfileError):wn.validate_profile(broken)
    def test_lpc_estimation_is_finite_for_quiet_and_voiced_frames(self):
        centers=np.geomspace(100,7000,24)
        for frame in [np.zeros(1024),np.random.default_rng(1).normal(size=1024),np.sin(2*np.pi*250*np.arange(1024)/16000)]:
            envelope=voice_profile.lpc_envelope(frame,centers)
            self.assertEqual((24,),envelope.shape);self.assertTrue(np.isfinite(envelope).all())
    def test_short_stationary_voice_fits_a_valid_noncyclic_model(self):
        rate=48000;t=np.arange(rate*3)/rate;x=.4*np.sin(2*np.pi*280*t)+.15*np.sin(2*np.pi*560*t)
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'voice.wav'
            with wave.open(str(path),'wb') as f:
                f.setnchannels(1);f.setsampwidth(2);f.setframerate(rate);f.writeframes((x*32767).astype('<i2').tobytes())
            profile,*_=build_adjusted_profile.build(path)
            model,stats=voice_profile.enrich(profile,path)
        self.assertGreaterEqual(len(model['voice']['states']),4);self.assertEqual(model,wn.validate_profile(model))
        self.assertEqual(stats['frames']-1,sum(sum(row) for row in model['voice']['transitions']))
    def test_opposite_phase_stereo_keeps_the_same_vocal_model(self):
        rate=48000;t=np.arange(rate*3)/rate;x=.4*np.sin(2*np.pi*280*t)+.12*np.sin(2*np.pi*560*t)
        with tempfile.TemporaryDirectory() as d:
            paths=[]
            for channels in [1,2]:
                path=Path(d)/str(channels)
                with wave.open(str(path),'wb') as f:
                    f.setnchannels(channels);f.setsampwidth(2);f.setframerate(rate)
                    pcm=x if channels==1 else np.column_stack([x,-x]);f.writeframes((pcm*32767).astype('<i2').tobytes())
                paths.append(path)
            base,*_=build_adjusted_profile.build(paths[0]);a,_=voice_profile.enrich(base,paths[0]);b,_=voice_profile.enrich(base,paths[1])
        self.assertEqual(a['voice'],b['voice'])
