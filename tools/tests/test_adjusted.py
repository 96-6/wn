import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import wave
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import profile_audio as wn
import build_adjusted_profile as adjusted


class MotionTests(unittest.TestCase):
    def test_motion_roundtrip_and_validation(self):
        profile = {'version': 1, 'name': 'Motion', 'bands': [{'lowHz': 100, 'highHz': 6800, 'level': 1}],
                   'motion': {'stepMs': 80, 'pitchHz': [240, 350, 280], 'energy': [.2, 1, .4]}}
        with tempfile.TemporaryDirectory() as d:
            path = Path(d)/'motion.json'
            wn.write_profile(profile, path)
            self.assertEqual(profile, wn.read_profile(path))
        for field, bad in [('stepMs', 0), ('pitchHz', [240]), ('pitchHz', [240, 2000, 280]),
                           ('energy', [1, float('nan'), .3]), ('energy', [.2, 2, .4])]:
            with self.subTest(field=field, bad=bad):
                value = copy.deepcopy(profile); value['motion'][field] = bad
                with self.assertRaises(wn.ProfileError): wn.validate_profile(value)

    def test_pitch_and_cadence_follow_known_modulated_harmonics(self):
        rate=16000; t=np.arange(rate*3)/rate
        pitch=280+45*np.sin(2*np.pi*.7*t)
        phase=np.cumsum(2*np.pi*pitch/rate)
        envelope=.4+.3*np.sin(2*np.pi*2*t)
        x=envelope*(np.sin(phase)+.5*np.sin(2*phase)+.3*np.sin(3*phase))
        motion, stats=adjusted.motion_contour(x)
        times=np.arange(len(motion['pitchHz']))*.08
        expected=280+45*np.sin(2*np.pi*.7*times)
        self.assertLess(np.median(np.abs(np.array(motion['pitchHz'])[2:-2]-expected[2:-2])), 8)
        self.assertGreater(np.corrcoef(motion['energy'][2:-2],(.4+.3*np.sin(2*np.pi*2*times))[2:-2])[0,1],.9)
        self.assertGreater(stats['voiced_fraction'], .9)

    @unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg required')
    def test_padded_bands_cover_speech_harmonics_and_leave_high_frequencies_free(self):
        rate=48000;t=np.arange(rate*3)/rate
        phase=np.cumsum(2*np.pi*(280+40*np.sin(2*np.pi*.6*t))/rate)
        x=sum(np.sin(h*phase)/h for h in range(1,10))*.25
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'test.wav'
            with wave.open(str(path),'wb') as f:
                f.setnchannels(1);f.setsampwidth(2);f.setframerate(rate);f.writeframes((x*32767).astype('<i2').tobytes())
            profile, stats, *_=adjusted.build(path)
        self.assertGreater(stats['mean_spectral_energy_coverage'], .99)
        self.assertTrue(any(b['lowHz']<250 and b['highHz']>300 for b in profile['bands']))
        self.assertLess(max(b['highHz'] for b in profile['bands']), 7000)
        self.assertEqual(profile, wn.validate_profile(profile))
        self.assertGreater(len(profile['motion']['pitchHz']), 10)

    @unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg required')
    def test_opposite_phase_channels_preserve_the_adjusted_profile(self):
        rate=48000;t=np.arange(rate*2)/rate
        x=.5*np.sin(2*np.pi*300*t)+.2*np.sin(2*np.pi*600*t)
        with tempfile.TemporaryDirectory() as d:
            profiles=[]
            for channels in [1,2]:
                path=Path(d)/str(channels)
                with wave.open(str(path),'wb') as f:
                    f.setnchannels(channels);f.setsampwidth(2);f.setframerate(rate)
                    pcm=x if channels==1 else np.column_stack([x,-x])
                    f.writeframes((pcm*32767).astype('<i2').tobytes())
                profiles.append(adjusted.build(path)[0])
        self.assertEqual(profiles[0],profiles[1])


if __name__=='__main__': unittest.main()
