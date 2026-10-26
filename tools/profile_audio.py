#!/usr/bin/env python3
"""WN desktop profiler. No arguments opens a small GUI; --help documents the CLI."""
from __future__ import annotations
import argparse
import csv
import dataclasses
import datetime
import html
import json
import math
import os
from pathlib import Path
import queue
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
from typing import Callable
import numpy as np

SIZE, HOP, RATE = 4096, 2048, 48000
LOW_HZ, HIGH_HZ, BIN_HZ = 80.0, 8000.0, 40.0
MODES = {"narrow": (.65, .20, 12), "balanced": (.80, .35, 16), "broad": (.90, .50, 24)}

class ProfileError(RuntimeError):
    pass

class Cancelled(ProfileError):
    pass

@dataclasses.dataclass
class Result:
    profile: dict
    stats: dict
    frequencies: np.ndarray
    power: np.ndarray
    selected: np.ndarray

def validate_profile(value):
    if not isinstance(value, dict) or type(value.get("version")) not in (int, float) or value["version"] != 1:
        raise ProfileError("Expected a WN JSON object with version 1.")
    name = value.get("name")
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
        raise ProfileError("Profile name must contain 1–80 readable characters.")
    if ("bands" in value) == ("levels" in value):
        raise ProfileError("Provide bands or levels, not both.")
    out = {"version": 1, "name": name.strip()}
    def number(v):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
            raise ProfileError("Profile values must be finite numbers.")
        return float(v)
    if "levels" in value:
        levels = value["levels"]
        if not isinstance(levels, list) or len(levels) != 10:
            raise ProfileError("Ten-band profiles need exactly 10 levels.")
        levels = [number(v) for v in levels]
        if min(levels) < 0 or max(levels) > 1 or max(levels) == 0:
            raise ProfileError("Levels must be 0–1, with at least one above zero.")
        out["levels"] = levels
    else:
        bands = value["bands"]
        if not isinstance(bands, list) or not 1 <= len(bands) <= 64:
            raise ProfileError("Use 1–64 bands.")
        previous, converted = 0.0, []
        for b in bands:
            if not isinstance(b, dict) or ("level" in b) == ("gainDb" in b):
                raise ProfileError("Each band needs lowHz, highHz, and either level or gainDb.")
            low, high = number(b.get("lowHz")), number(b.get("highHz"))
            if low < 20 or high > 22000 or high - low < 20 or low < previous:
                raise ProfileError("Bands must be sorted, non-overlapping, 20–22000 Hz, and at least 20 Hz wide.")
            if "level" in b:
                gain = number(b["level"])
            else:
                db = number(b["gainDb"])
                if not -60 <= db <= 0:
                    raise ProfileError("gainDb must be -60–0.")
                gain = 10 ** (db / 20)
            if not 0 <= gain <= 1:
                raise ProfileError("Band level must be 0–1.")
            converted.append({"lowHz": low, "highHz": high, "level": gain})
            previous = high
        if not any(b["level"] > 0 for b in converted):
            raise ProfileError("At least one band must be above zero.")
        out["bands"] = converted
    if 'motion' in value:
        motion = value['motion']
        if not isinstance(motion, dict):
            raise ProfileError('Motion must be an object.')
        step = number(motion.get('stepMs'))
        pitch, energy = motion.get('pitchHz'), motion.get('energy')
        if not 20 <= step <= 1000 or not isinstance(pitch, list) or not isinstance(energy, list) or not 2 <= len(pitch) <= 2048 or len(pitch) != len(energy):
            raise ProfileError('Motion needs 2–2048 matching points and a 20–1000 ms step.')
        pitch, energy = [number(v) for v in pitch], [number(v) for v in energy]
        if min(pitch) < 60 or max(pitch) > 1000 or min(energy) < 0 or max(energy) > 1:
            raise ProfileError('Motion pitch must be 60–1000 Hz and energy 0–1.')
        out['motion'] = {'stepMs': step, 'pitchHz': pitch, 'energy': energy}
        if 'model' in motion:
            model=motion['model']
            if not isinstance(model,dict) or model.get('type')!='mdn1':
                raise ProfileError('Unsupported motion model.')
            clean={'type':'mdn1'}
            for key,low,high in [('pitchMean',math.log(60),math.log(1000)),('pitchScale',.005,2),
                                 ('energyMean',0,1),('energyScale',.005,2)]:
                normalized=number(model.get(key))
                if not low<=normalized<=high:raise ProfileError('Invalid model normalization: '+key)
                clean[key]=normalized
            for key,count in [('w1',64),('b1',16),('w2',240),('b2',15)]:
                values=model.get(key)
                if not isinstance(values,list) or len(values)!=count:raise ProfileError('Invalid model matrix: '+key)
                values=[number(v) for v in values]
                if max(abs(v) for v in values)>100:raise ProfileError('Model weights are too large.')
                clean[key]=values
            out['motion']['model']=clean
    if 'voice' in value:
        voice=value['voice']
        if not isinstance(voice,dict) or type(voice.get('version')) not in (int,float) or voice.get('version')!=1 or 'motion' not in out:
            raise ProfileError('Voice model requires version 1 and motion data.')
        centers=voice.get('centersHz');states=voice.get('states')
        if not isinstance(centers,list) or len(centers)!=24 or not isinstance(states,list) or not 4<=len(states)<=32:
            raise ProfileError('Voice model needs 24 frequency centers and 4–32 states.')
        centers=[number(v) for v in centers]
        if centers[0]<60 or centers[-1]>22000 or any(b<=a for a,b in zip(centers,centers[1:])):
            raise ProfileError('Invalid voice frequency centers.')
        clean_states=[]
        for state in states:
            if not isinstance(state,dict):raise ProfileError('Invalid voice state.')
            pitch,energy,voicing=(number(state.get(k)) for k in ['pitchHz','energy','voicing'])
            gains=state.get('gains')
            if not 60<=pitch<=1000 or not 0<=energy<=1 or not 0<=voicing<=1 or not isinstance(gains,list) or len(gains)!=24:
                raise ProfileError('Invalid voice state controls.')
            gains=[number(v) for v in gains]
            if min(gains)<.05 or max(gains)>5:raise ProfileError('Invalid voice spectral envelope.')
            clean_states.append({'pitchHz':pitch,'energy':energy,'voicing':voicing,'gains':gains})
        k=len(states);weights=voice.get('weights');transitions=voice.get('transitions')
        if not isinstance(weights,list) or len(weights)!=k or not isinstance(transitions,list) or len(transitions)!=k:
            raise ProfileError('Invalid voice transition dimensions.')
        weights=[number(v) for v in weights]
        if min(weights)<0 or max(weights)>100000 or sum(weights)<=0:raise ProfileError('Invalid voice weights.')
        matrix=[]
        for row in transitions:
            if not isinstance(row,list) or len(row)!=k:raise ProfileError('Invalid voice transition row.')
            row=[number(v) for v in row]
            if min(row)<0 or max(row)>100000:raise ProfileError('Invalid voice transitions.')
            matrix.append(row)
        mix,tilt,speech_mix=number(voice.get('mix',0)),number(voice.get('tilt',-.25)),number(voice.get('speechMix',.05))
        if not 0<=mix<=1 or not 0<=speech_mix<=1 or not -2<=tilt<=2:raise ProfileError('Invalid voice defaults.')
        out['voice']={'version':1,'centersHz':centers,'states':clean_states,'transitions':matrix,'weights':weights,'mix':mix,'speechMix':speech_mix,'tilt':tilt}
    return out

class Profiler:
    """Same bounded-memory histogram and sparse-band algorithm as SpectrumProfiler.java."""
    def __init__(self, sample_rate=RATE, channels=1):
        if not 8000 <= sample_rate <= 384000 or not 1 <= channels <= 32:
            raise ProfileError("Unsupported PCM rate or channel count (1–32).")
        self.rate, self.channels = int(sample_rate), int(channels)
        self.bins = int(math.floor((min(HIGH_HZ, sample_rate / 2) - LOW_HZ) / BIN_HZ))
        self.window = .5 - .5 * np.cos(2 * np.pi * np.arange(SIZE) / SIZE)
        self.window_energy = float(np.sum(self.window ** 2))
        self.buffer = np.zeros((SIZE, channels), dtype=np.float64)
        self.histogram = np.zeros((97, self.bins), dtype=np.float64)
        self.histogram_frames = np.zeros(97, dtype=np.int64)
        frequencies = np.arange(1, SIZE // 2) * sample_rate / SIZE
        groups = np.floor((frequencies - LOW_HZ) / BIN_HZ).astype(int)
        self.valid = (groups >= 0) & (groups < self.bins)
        self.groups = groups[self.valid]
        self.filled = self.samples = self.frames = self.over_scale = 0
        self.peak = self.energy = 0.0
        self.peak_frame_db = -300.0

    @property
    def seconds(self):
        return self.samples / self.rate

    def accept(self, data):
        x = np.asarray(data, dtype=np.float64)
        if x.ndim == 1:
            if self.channels != 1:
                raise ProfileError("Expected interleaved multichannel PCM.")
            x = x[:, None]
        if x.ndim != 2 or x.shape[1] != self.channels or not np.isfinite(x).all():
            raise ProfileError("Invalid or non-finite PCM.")
        if len(x) == 0:
            return
        self.samples += len(x)
        self.energy += float(np.sum(x * x))
        self.peak = max(self.peak, float(np.max(np.abs(x))))
        self.over_scale += int(np.count_nonzero(np.abs(x) >= .999))
        position = 0
        while position < len(x):
            count = min(SIZE - self.filled, len(x) - position)
            self.buffer[self.filled:self.filled+count] = x[position:position+count]
            self.filled += count
            position += count
            if self.filled == SIZE:
                self._analyse()
                self.buffer[:SIZE-HOP] = self.buffer[HOP:].copy()
                self.filled = SIZE - HOP

    def _analyse(self):
        frame_energy = float(np.mean(self.buffer * self.buffer))
        db = 10 * math.log10(max(frame_energy, 1e-30))
        self.peak_frame_db = max(self.peak_frame_db, db)
        spectrum = np.fft.rfft((self.buffer - self.buffer.mean(axis=0)) * self.window[:, None], axis=0)
        power = (np.abs(spectrum[1:SIZE//2]) ** 2).mean(axis=1) * 2 / (SIZE * self.window_energy)
        binned = np.bincount(self.groups, weights=power[self.valid], minlength=self.bins)
        bucket = max(-96, min(0, math.floor(db))) + 96
        self.histogram[bucket] += binned
        self.histogram_frames[bucket] += 1
        self.frames += 1

    def finish(self, name, coverage=.8, occupancy=.35, max_bands=16):
        if not .1 <= coverage <= 1 or not .01 <= occupancy <= 1 or not 1 <= max_bands <= 64:
            raise ProfileError("Invalid coverage, occupancy or band limit.")
        if self.seconds < .5 or self.frames < 4 or self.peak_frame_db < -60:
            raise ProfileError("Not enough audible audio. Supply at least one second of clear speech.")
        gate = max(-96, min(0, math.ceil(max(-60, self.peak_frame_db - 25)))) + 96
        used = int(self.histogram_frames[gate:].sum())
        if used < 2:
            raise ProfileError("Not enough non-silent frames.")
        power = self.histogram[gate:].sum(axis=0)
        smooth = .5 * power + .25 * np.r_[power[0], power[:-1]] + .25 * np.r_[power[1:], power[-1]]
        total, smoothed_total, strongest = float(power.sum()), float(smooth.sum()), float(smooth.max())
        if total < 1e-18 or not math.isfinite(total):
            raise ProfileError("No usable energy in the speech-frequency range.")
        order = np.lexsort((np.arange(self.bins), -smooth))
        selected = np.zeros(self.bins, dtype=bool)
        cap, count, captured = max(1, math.ceil(self.bins * occupancy)), 0, 0.0
        for b in order:
            if count >= cap or captured >= smoothed_total * coverage or smooth[b] < strongest * .001:
                break
            selected[b] = True
            captured += float(smooth[b])
            count += 1
        runs, b = [], 0
        while b < self.bins:
            if not selected[b]:
                b += 1
                continue
            start = b
            while b < self.bins and selected[b]:
                b += 1
            runs.append((start, b))
        runs = sorted(sorted(runs, key=lambda r: (-float(smooth[r[0]:r[1]].sum()), r[0]))[:max_bands])
        strongest_mean = max(float(smooth[a:b].mean()) for a, b in runs)
        selected[:] = False
        bands = []
        for a, b in runs:
            selected[a:b] = True
            bands.append({"lowHz": LOW_HZ + a * BIN_HZ, "highHz": LOW_HZ + b * BIN_HZ,
                          "level": min(1.0, math.sqrt(float(smooth[a:b].mean()) / strongest_mean))})
        profile = validate_profile({"version": 1, "name": name, "bands": bands})
        stats = {"algorithm": "welch4096-hann-rms-gated-40hz-v1", "sample_rate": self.rate,
                 "channels": self.channels, "seconds": self.seconds, "analysed_frames": self.frames,
                 "used_frames": used, "rms_gate_dbfs": gate - 96,
                 "peak_dbfs": 20 * math.log10(max(self.peak, 1e-15)),
                 "rms_dbfs": 10 * math.log10(max(self.energy / max(1, self.samples*self.channels), 1e-30)),
                 "above_full_scale_samples": self.over_scale, "energy_coverage": float(power[selected].sum()/total),
                 "bandwidth_hz": int(np.count_nonzero(selected) * BIN_HZ), "bands": len(bands),
                 "analysis_low_hz": LOW_HZ, "analysis_high_hz": LOW_HZ + self.bins * BIN_HZ,
                 "coverage_target": coverage, "occupancy_limit": occupancy}
        return Result(profile, stats, LOW_HZ + (np.arange(self.bins)+.5)*BIN_HZ, power/used, selected.copy())

def ffmpeg_path(explicit=None):
    if explicit:
        p = str(explicit)
        if Path(p).is_file() or shutil.which(p):
            return p
        raise ProfileError("FFmpeg executable was not found: " + p)
    found = os.environ.get("WN_FFMPEG") or shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError) as e:
        raise ProfileError("Install FFmpeg or run: python -m pip install imageio-ffmpeg") from e

def _exact(stream, count):
    pieces, left = [], count
    while left:
        data = stream.read(left)
        if not data:
            raise ProfileError("Decoder returned an incomplete WAV stream.")
        pieces.append(data)
        left -= len(data)
    return b"".join(pieces)

def _float_wave_header(stream):
    head = _exact(stream, 12)
    if head[:4] != b"RIFF" or head[8:] != b"WAVE":
        raise ProfileError("FFmpeg did not return the expected PCM WAV stream.")
    fmt, skipped = None, 0
    while True:
        label, size = struct.unpack("<4sI", _exact(stream, 8))
        if label == b"data":
            if fmt is None:
                raise ProfileError("WAV stream has no format header.")
            return fmt
        skipped += size
        if size > 1024*1024 or skipped > 2*1024*1024:
            raise ProfileError("Unexpectedly large WAV header.")
        chunk = _exact(stream, size)
        if size & 1:
            _exact(stream, 1)
        if label == b"fmt ":
            if len(chunk) < 16:
                raise ProfileError("Invalid PCM header.")
            tag, channels, rate, _, align, bits = struct.unpack_from("<HHIIHH", chunk)
            if tag == 0xfffe and len(chunk) >= 40:
                tag = struct.unpack_from("<I", chunk, 24)[0]
            if tag != 3 or bits != 32 or align != channels*4 or not 1 <= channels <= 32:
                raise ProfileError("Expected interleaved float32 PCM (1–32 channels).")
            fmt = (rate, channels)

def analyse_file(args, cancel=None, progress=None):
    source = Path(args.input).expanduser().resolve()
    if not source.is_file():
        raise ProfileError("Audio file does not exist: " + str(source))
    command = [ffmpeg_path(args.ffmpeg), "-hide_banner", "-loglevel", "error", "-nostdin",
               "-protocol_whitelist", "file,pipe,crypto,data"]
    if args.raw_format:
        command += ["-f", args.raw_format, "-ar", str(args.raw_sample_rate), "-ac", str(args.raw_channels)]
    if args.start:
        command += ["-ss", str(args.start)]
    command += ["-i", str(source), "-map", f"0:a:{args.stream}", "-vn", "-sn", "-dn"]
    if args.duration:
        command += ["-t", str(args.duration)]
    command += ["-map_metadata", "-1", "-ar", str(RATE), "-c:a", "pcm_f32le", "-f", "wav", "pipe:1"]
    if args.debug:
        print("Decoder command:", repr(command), file=sys.stderr)
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=errors)
        try:
            rate, channels = _float_wave_header(process.stdout)
            profiler = Profiler(rate, channels)
            while True:
                if cancel is not None and cancel.is_set():
                    raise Cancelled("Cancelled")
                data = process.stdout.read(4096 * channels * 4)
                if not data:
                    break
                if len(data) % (channels*4):
                    raise ProfileError("Decoder returned an incomplete PCM frame.")
                profiler.accept(np.frombuffer(data, dtype="<f4").reshape(-1, channels))
                if progress:
                    progress(profiler.seconds)
            code = process.wait(timeout=30)
            errors.seek(0)
            decoder_errors = errors.read().decode("utf-8", errors="replace")[-8000:]
            if code:
                raise ProfileError("FFmpeg could not decode this file.\n" + decoder_errors)
            name = (args.name or source.stem).strip()[:80]
            result = profiler.finish(name, *tuning(args))
            result.stats.update({"source": source.name, "decoder": "FFmpeg"})
            if decoder_errors:
                result.stats["decoder_messages"] = decoder_errors
            return result
        except Exception as error:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            if isinstance(error, ProfileError):
                errors.seek(0)
                detail = errors.read().decode("utf-8", errors="replace")[-4000:]
                if detail:
                    raise ProfileError(str(error) + "\n" + detail) from error
            raise
        finally:
            if process.stdout:
                process.stdout.close()

def analyse_microphone(args, cancel=None, progress=None):
    try:
        import sounddevice as sd
    except (ImportError, OSError) as error:
        raise ProfileError("Microphone capture needs sounddevice and PortAudio. Install: python -m pip install sounddevice") from error
    if not 0 < args.duration <= 3600:
        raise ProfileError("Microphone duration must be greater than 0 and at most 3600 seconds.")
    device = int(args.device) if args.device and str(args.device).isdigit() else args.device
    rate = RATE
    try:
        sd.check_input_settings(device=device, channels=args.channels, samplerate=rate, dtype="float32")
    except Exception:
        rate = int(sd.query_devices(device, "input")["default_samplerate"])
    profiler = Profiler(rate, args.channels)
    try:
        with sd.InputStream(device=device, samplerate=rate, channels=args.channels, dtype="float32") as stream:
            while profiler.seconds < args.duration:
                if cancel is not None and cancel.is_set():
                    raise Cancelled("Cancelled")
                count = min(2048, int(math.ceil((args.duration-profiler.seconds)*rate)))
                data, overflow = stream.read(count)
                if overflow and args.debug:
                    print("Microphone overflow reported.", file=sys.stderr)
                profiler.accept(data)
                if progress:
                    progress(profiler.seconds)
    except KeyboardInterrupt:
        print("\nFinishing captured audio…", file=sys.stderr)
    result = profiler.finish((args.name or "Microphone profile").strip()[:80], *tuning(args))
    result.stats["source"] = "microphone"
    return result

def tuning(args):
    coverage, occupancy, maximum = MODES[args.mode]
    return (coverage if args.coverage is None else args.coverage,
            occupancy if args.occupancy is None else args.occupancy,
            maximum if args.max_bands is None else args.max_bands)

def read_profile(path):
    p = Path(path)
    if p.stat().st_size > 262144:
        raise ProfileError("JSON profile exceeds 256 KiB.")
    try:
        return validate_profile(json.loads(p.read_text(encoding="utf-8-sig")))
    except (ValueError, UnicodeError) as error:
        raise ProfileError("Invalid UTF-8 JSON: " + str(error)) from error

def write_profile(profile, output, force=False):
    output = Path(output).expanduser()
    if output.exists() and not force:
        raise ProfileError(f"Output already exists: {output}. Use --force to replace it.")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, delete=False, suffix=".tmp") as f:
            temporary = Path(f.name)
            json.dump(validate_profile(profile), f, ensure_ascii=False, indent=2, allow_nan=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        if output.exists() and not force:
            raise ProfileError("Output appeared while processing; refusing to replace it.")
        os.replace(temporary, output)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()
    return output.resolve()

def diagnostics(result, directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory/"analysis.json").write_text(json.dumps(result.stats, indent=2, ensure_ascii=False, allow_nan=False)+"\n", encoding="utf-8")
    peak = max(float(result.power.max()), 1e-30)
    db = 10*np.log10(np.maximum(result.power/peak, 1e-12))
    with (directory/"spectrum.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["frequency_hz", "relative_power_db", "selected"])
        writer.writerows(zip(result.frequencies, db, result.selected.astype(int)))
    low, high = float(result.frequencies[0]), float(result.frequencies[-1])
    def x(hz):
        return 58+math.log(max(low, min(high, hz))/low)/math.log(high/low)*880
    def y(v):
        return 60-max(-60, min(0, float(v)))*3.5
    points = " ".join(f"{x(hz):.2f},{y(v):.2f}" for hz,v in zip(result.frequencies,db))
    rectangles = []
    for band in result.profile.get("bands", []):
        left, right = x(band["lowHz"]), x(band["highHz"])
        rectangles.append(f'<rect x="{left:.2f}" y="60" width="{max(.2,right-left):.2f}" height="210" fill="#b5ebde"/>')
    ticks = "".join(f'<text x="{x(f):.1f}" y="292" text-anchor="middle">{f:g}</text>' for f in (100,250,500,1000,2000,4000,8000) if low<=f<=high)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="980" height="330" viewBox="0 0 980 330">
<rect width="980" height="330" fill="white"/><g font-family="sans-serif" font-size="12" fill="#172c36">
<text x="58" y="28" font-size="18">{html.escape(result.profile["name"])}</text>
<text x="58" y="48">Relative spectrum; highlighted ranges are exported bands</text>
{"".join(rectangles)}<path d="M58,60V270H938" fill="none" stroke="#526870"/>
<polyline points="{points}" fill="none" stroke="#167d6e" stroke-width="1.5"/>{ticks}
<text x="500" y="318" text-anchor="middle">Frequency (Hz, logarithmic axis)</text>
<text x="8" y="65">0 dB</text><text x="8" y="273">−60 dB</text></g></svg>'''
    (directory/"spectrum.svg").write_text(svg, encoding="utf-8")

def parser():
    p = argparse.ArgumentParser(description="Create WN frequency profiles from any local audio/video file FFmpeg can decode, or a microphone.")
    p.add_argument("input", nargs="?", help="Audio/video file; extension is not restricted")
    p.add_argument("-o", "--output", type=Path, help="Portable WN JSON output")
    p.add_argument("--mic", action="store_true", help="Capture microphone; Ctrl+C finishes early")
    p.add_argument("--import-json", type=Path, help="Validate and optionally re-export an existing WN profile")
    p.add_argument("--name", help="Profile name, at most 80 characters")
    p.add_argument("--duration", type=float, default=30, help="Seconds to analyze; 0 means the whole file (default 30)")
    p.add_argument("--start", type=float, default=0, help="File segment start in seconds")
    p.add_argument("--mode", choices=MODES, default="balanced")
    p.add_argument("--coverage", type=float, help="Requested fraction of in-range energy, .1–1")
    p.add_argument("--occupancy", type=float, help="Maximum fraction of in-range frequencies occupied, .01–1")
    p.add_argument("--max-bands", type=int, help="Maximum exported bands, 1–64")
    p.add_argument("--stream", type=int, default=0, help="Audio-track index for multitrack containers")
    p.add_argument("--ffmpeg", help="Explicit FFmpeg executable")
    p.add_argument("--raw-format", help="Headerless input format, e.g. s16le, s24le, f32le")
    p.add_argument("--raw-sample-rate", type=int, default=48000)
    p.add_argument("--raw-channels", type=int, default=1)
    p.add_argument("--device", help="Microphone device ID or name")
    p.add_argument("--channels", type=int, default=1, help="Microphone channels")
    p.add_argument("--list-devices", action="store_true")
    p.add_argument("--formats", action="store_true", help="List actual formats and decoders in the installed FFmpeg build")
    p.add_argument("--diagnostics", type=Path, help="Write analysis.json, spectrum.csv and spectrum.svg")
    p.add_argument("--print", dest="print_json", action="store_true", help="Print portable profile JSON to stdout")
    p.add_argument("--force", action="store_true", help="Replace an existing JSON output")
    p.add_argument("--debug", action="store_true")
    p.add_argument("--gui", action="store_true", help="Open the optional Tk file/microphone UI")
    return p

def validate_args(args):
    if not math.isfinite(args.duration) or args.duration < 0 or not math.isfinite(args.start) or args.start < 0 or args.stream < 0:
        raise ProfileError("Duration, start and stream index must be nonnegative and finite.")
    c,o,m = tuning(args)
    if not math.isfinite(c) or not .1<=c<=1 or not math.isfinite(o) or not .01<=o<=1 or not 1<=m<=64:
        raise ProfileError("Invalid coverage, occupancy or band limit.")
    if not 1<=args.channels<=32 or not 1<=args.raw_channels<=32 or not 8000<=args.raw_sample_rate<=384000:
        raise ProfileError("Unsupported channel count or raw sample rate.")

def launch_gui(args):
    try:
        import tkinter as tk
        from tkinter import filedialog, messagebox, ttk
    except ImportError as e:
        raise ProfileError("Tkinter is unavailable. Use the CLI, or install your Python distribution's Tk package.") from e
    root = tk.Tk()
    root.title("WN profile tool")
    root.geometry("710x430")
    frame = ttk.Frame(root, padding=16); frame.pack(fill="both", expand=True)
    path = tk.StringVar(value=args.input or "")
    output = tk.StringVar(value=str(args.output or ""))
    name = tk.StringVar(value=args.name or "")
    duration = tk.StringVar(value=str(args.duration))
    mode = tk.StringVar(value=args.mode)
    events = queue.Queue()
    cancellation = threading.Event()
    running = False
    def browse():
        value = filedialog.askopenfilename(filetypes=[("All audio / video files", "*")])
        if value:
            path.set(value)
            if not name.get(): name.set(Path(value).stem[:80])
            if not output.get(): output.set(str(Path(value).with_suffix(".wn.json")))
    def save_as():
        value = filedialog.asksaveasfilename(defaultextension=".json", filetypes=[("WN JSON", "*.json")])
        if value: output.set(value)
    for row,(label,var) in enumerate([("Audio file",path),("Output JSON",output),("Profile name",name),("Seconds (0 = full file)",duration)]):
        ttk.Label(frame,text=label).grid(row=row,column=0,sticky="w",pady=6)
        ttk.Entry(frame,textvariable=var,width=56).grid(row=row,column=1,sticky="ew",padx=8,pady=6)
    ttk.Button(frame,text="Browse",command=browse).grid(row=0,column=2)
    ttk.Button(frame,text="Save as",command=save_as).grid(row=1,column=2)
    ttk.Label(frame,text="Band selection").grid(row=4,column=0,sticky="w",pady=6)
    ttk.Combobox(frame,textvariable=mode,values=list(MODES),state="readonly").grid(row=4,column=1,sticky="w",padx=8)
    info = tk.StringVar(value="Choose a file, or record the default microphone.")
    ttk.Label(frame,textvariable=info,wraplength=650).grid(row=6,column=0,columnspan=3,sticky="w",pady=16)
    buttons = ttk.Frame(frame); buttons.grid(row=5,column=0,columnspan=3,sticky="w",pady=12)
    def job(microphone=False):
        nonlocal running
        if running: return
        if not microphone and not path.get(): browse()
        if not microphone and not path.get(): return
        if not output.get(): save_as()
        if not output.get(): return
        try:
            values = argparse.Namespace(**vars(args))
            values.input, values.output, values.name = path.get(), Path(output.get()), name.get() or None
            values.duration, values.mode, values.mic = float(duration.get()), mode.get(), microphone
            validate_args(values)
            if values.output.exists() and not messagebox.askyesno("Replace JSON?", str(values.output)): return
        except (ValueError,ProfileError) as e:
            messagebox.showerror("WN",str(e)); return
        running=True; cancellation.clear(); info.set("Recording…" if microphone else "Analyzing…")
        def work():
            try:
                last_update=[0.0]
                def callback(seconds):
                    now=time.monotonic()
                    if now-last_update[0]>=.2:
                        events.put(("progress",f"{seconds:.1f} seconds analyzed")); last_update[0]=now
                result=(analyse_microphone if microphone else analyse_file)(values,cancellation,callback)
                destination=write_profile(result.profile,values.output,force=True)
                diagnostics(result,destination.with_name(destination.stem+"-debug"))
                events.put(("done",f"Saved {destination}\n{result.stats['bands']} bands; {result.stats['bandwidth_hz']} Hz; {result.stats['energy_coverage']:.0%} energy retained."))
            except Exception as e: events.put(("error",str(e)))
        threading.Thread(target=work,daemon=True).start()
    ttk.Button(buttons,text="Analyze file",command=lambda:job(False)).pack(side="left",padx=(0,8))
    ttk.Button(buttons,text="Record mic",command=lambda:job(True)).pack(side="left",padx=(0,8))
    ttk.Button(buttons,text="Cancel",command=cancellation.set).pack(side="left")
    def poll():
        nonlocal running
        try:
            while True:
                kind,text=events.get_nowait(); info.set(text)
                if kind in ("done","error"): running=False
        except queue.Empty: pass
        root.after(100,poll)
    frame.columnconfigure(1,weight=1)
    def close(): cancellation.set(); root.destroy()
    root.protocol("WM_DELETE_WINDOW",close); poll(); root.mainloop()

def main(argv=None):
    actual=sys.argv[1:] if argv is None else list(argv)
    args=parser().parse_args(actual)
    try:
        if args.gui or not actual:
            launch_gui(args); return 0
        if args.formats:
            executable=ffmpeg_path(args.ffmpeg)
            subprocess.run([executable,"-hide_banner","-formats"],check=True)
            subprocess.run([executable,"-hide_banner","-decoders"],check=True)
            return 0
        if args.list_devices:
            try:
                import sounddevice as sd
                print(sd.query_devices())
            except (ImportError,OSError) as e: raise ProfileError("Install sounddevice to list microphones.") from e
            return 0
        validate_args(args)
        if sum(bool(x) for x in (args.input,args.mic,args.import_json))!=1:
            raise ProfileError("Choose one input file, --mic, or --import-json.")
        if args.import_json:
            profile=read_profile(args.import_json)
            if args.name: profile=validate_profile(dict(profile,name=args.name))
            if args.output: print("Saved",write_profile(profile,args.output,args.force),file=sys.stderr)
            if args.print_json or not args.output: print(json.dumps(profile,ensure_ascii=False,indent=2,allow_nan=False))
            return 0
        result=(analyse_microphone if args.mic else analyse_file)(args)
        output=args.output
        if output is None and not args.print_json:
            output=Path("microphone-profile.json") if args.mic else Path(args.input).with_suffix(".wn.json")
        if output: print("Saved",write_profile(result.profile,output,args.force),file=sys.stderr)
        if args.print_json: print(json.dumps(result.profile,ensure_ascii=False,indent=2,allow_nan=False))
        if args.diagnostics: diagnostics(result,args.diagnostics)
        print(json.dumps(result.stats,indent=2,ensure_ascii=False,allow_nan=False),file=sys.stderr)
        return 0
    except (ProfileError,ValueError,OSError,subprocess.SubprocessError) as e:
        print("WN:",str(e),file=sys.stderr); return 2

if __name__=="__main__":
    raise SystemExit(main())

