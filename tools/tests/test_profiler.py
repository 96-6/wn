import argparse
import importlib.util
import io
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import wave
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import profile_audio as wn

class ProfilerTests(unittest.TestCase):
    def audio(self,seconds=2,stereo=False):
        t=np.arange(48000*seconds)/48000
        x=(.55*np.sin(2*np.pi*1000*t)+.35*np.sin(2*np.pi*2300*t)).astype(np.float32)
        return np.column_stack([x,-x]) if stereo else x
    def result(self,stereo=False):
        p=wn.Profiler(48000,2 if stereo else 1);p.accept(self.audio(stereo=stereo));return p.finish("Test")
    def test_known_frequencies_and_sparse_band_limit(self):
        r=self.result()
        self.assertTrue(any(b["lowHz"]<=1000<=b["highHz"] for b in r.profile["bands"]))
        self.assertTrue(any(b["lowHz"]<=2300<=b["highHz"] for b in r.profile["bands"]))
        self.assertFalse(any(b["lowHz"]<=5000<=b["highHz"] for b in r.profile["bands"]))
        self.assertGreater(r.stats["energy_coverage"],.85)
        self.assertLessEqual(r.stats["bandwidth_hz"],math.ceil(198*.35)*40)
        self.assertEqual(r.profile,wn.validate_profile(r.profile))
    def test_antiphase_channels_do_not_cancel(self):
        mono,stereo=self.result().profile,self.result(True).profile
        self.assertEqual(len(mono["bands"]),len(stereo["bands"]))
        for a,b in zip(mono["bands"],stereo["bands"]):
            self.assertEqual(a["lowHz"],b["lowHz"]);self.assertEqual(a["highHz"],b["highHz"])
            self.assertAlmostEqual(a["level"],b["level"],places=10)
    def test_chunk_boundaries_do_not_change_result(self):
        x=self.audio(stereo=True);a=wn.Profiler(48000,2);a.accept(x)
        b=wn.Profiler(48000,2)
        for i in range(0,len(x),317):b.accept(x[i:i+317])
        self.assertEqual(a.finish("X").profile,b.finish("X").profile)
    def test_silence_nonfinite_and_invalid_options_are_rejected(self):
        p=wn.Profiler();p.accept(np.zeros(48000))
        with self.assertRaises(wn.ProfileError):p.finish("Silent")
        with self.assertRaises(wn.ProfileError):wn.Profiler().accept(np.array([np.nan]))
        with self.assertRaises(wn.ProfileError):self.result().profile.update(wn.validate_profile({"version":1,"name":"Bad","bands":[]}))
        args=wn.parser().parse_args(["--mic","--coverage","nan"])
        with self.assertRaises(wn.ProfileError):wn.validate_args(args)
    def test_noise_obeys_occupancy_limit_and_reports_unmet_coverage(self):
        p=wn.Profiler();p.accept(np.random.default_rng(4).normal(0,.1,48000*3))
        r=p.finish("Noise")
        self.assertLessEqual(r.stats["bandwidth_hz"],math.ceil(198*.35)*40)
        self.assertLess(r.stats["energy_coverage"],.8)
        self.assertLessEqual(len(r.profile["bands"]),16)
    def test_quiet_frames_are_gated(self):
        p=wn.Profiler();p.accept(np.zeros(48000));p.accept(self.audio())
        r=p.finish("Gated")
        self.assertLess(r.stats["used_frames"],r.stats["analysed_frames"])
    def test_json_roundtrip_and_no_accidental_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            output=Path(d)/"profile.json";profile=self.result().profile
            wn.write_profile(profile,output)
            self.assertEqual(profile,wn.read_profile(output))
            with self.assertRaises(wn.ProfileError):wn.write_profile(profile,output)
            self.assertEqual(profile,wn.read_profile(output))
            wn.write_profile(profile,output,force=True)
    def test_db_import_and_bom(self):
        profile={"version":1,"name":"DB","bands":[{"lowHz":400,"highHz":800,"gainDb":-6}]}
        p=wn.validate_profile(profile);self.assertAlmostEqual(p["bands"][0]["level"],10**(-6/20))
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"p.json";f.write_text(json.dumps(profile),encoding="utf-8-sig")
            self.assertEqual(p,wn.read_profile(f))
    def test_diagnostics_are_finite_and_have_correct_axes(self):
        with tempfile.TemporaryDirectory() as d:
            wn.diagnostics(self.result(),d)
            self.assertTrue((Path(d)/"spectrum.svg").read_text().startswith("<svg"))
            self.assertIn("0 dB",(Path(d)/"spectrum.svg").read_text())
            stats=json.loads((Path(d)/"analysis.json").read_text())
            self.assertEqual(stats["bands"],2)
            self.assertEqual(199,len((Path(d)/"spectrum.csv").read_text().splitlines()))

@unittest.skipUnless(shutil.which("ffmpeg"),"FFmpeg required")
class FormatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
        cls.source=cls.root/"source.wav"
        x=ProfilerTests().audio(stereo=True)
        with wave.open(str(cls.source),"wb") as f:
            f.setnchannels(2);f.setsampwidth(2);f.setframerate(48000);f.writeframes((x*32767).astype("<i2").tobytes())
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def analyze(self,path,*more):
        args=wn.parser().parse_args([str(path),"--name","Decoded",*more])
        wn.validate_args(args);return wn.analyse_file(args)
    def test_common_codecs_and_containers(self):
        cases=[("flac",["-c:a","flac"]),("mp3",["-c:a","libmp3lame"]),
               ("m4a",["-c:a","aac"]),("ogg",["-c:a","libvorbis"]),
               ("opus",["-c:a","libopus"]),("wma",["-c:a","wmav2"]),
               ("aiff",["-c:a","pcm_s16be"]),("caf",["-c:a","pcm_s24le"]),
               ("float.wav",["-c:a","pcm_f32le"]),("24bit.wav",["-c:a","pcm_s24le"])]
        for suffix,codec in cases:
            with self.subTest(format=suffix):
                path=self.root/("test."+suffix)
                subprocess.run(["ffmpeg","-v","error","-y","-i",str(self.source),*codec,str(path)],check=True)
                r=self.analyze(path)
                self.assertGreater(r.stats["energy_coverage"],.8)
                self.assertTrue(any(b["lowHz"]<=1000<=b["highHz"] for b in r.profile["bands"]))
    def test_extensionless_and_unicode_paths(self):
        p=self.root/"тест without extension";shutil.copyfile(self.source,p)
        self.assertEqual(2,self.analyze(p).stats["channels"])
    def test_headerless_pcm_requires_explicit_format(self):
        p=self.root/"data.bin";p.write_bytes((ProfilerTests().audio()*32767).astype("<i2").tobytes())
        r=self.analyze(p,"--raw-format","s16le","--raw-sample-rate","48000","--raw-channels","1")
        self.assertTrue(r.profile["bands"])
    def test_video_audio_track_is_decoded(self):
        p=self.root/"video.mp4"
        subprocess.run(["ffmpeg","-v","error","-y","-f","lavfi","-i","color=c=black:s=16x16:r=1",
                        "-i",str(self.source),"-c:v","mpeg4","-c:a","aac","-shortest",str(p)],check=True)
        self.assertTrue(self.analyze(p).profile["bands"])
    def test_segment_and_missing_or_corrupt_audio(self):
        r=self.analyze(self.source,"--start","0.5","--duration","1")
        self.assertAlmostEqual(1,r.stats["seconds"],places=2)
        p=self.root/"broken.mp3";p.write_bytes(b"not an audio file")
        with self.assertRaises(wn.ProfileError):self.analyze(p)
        with self.assertRaises(wn.ProfileError):self.analyze(self.root/"missing.flac")

if __name__=="__main__":
    unittest.main()

