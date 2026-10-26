#!/usr/bin/env python3
"""Build the padded, full-spectrum Adjusted profile and its procedural motion data.

No waveform is included in the JSON. Requires NumPy and FFmpeg, like profile_audio.py.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import numpy as np
from profile_audio import ffmpeg_path, validate_profile, _float_wave_header


def decode(path, rate, duration, ffmpeg):
    command = [ffmpeg, '-v', 'error', '-nostdin', '-protocol_whitelist', 'file,pipe,crypto,data', '-i', str(path)]
    if duration:
        command += ['-t', str(duration)]
    command += ['-vn', '-map', '0:a:0', '-ar', str(rate), '-c:a', 'pcm_f32le', '-f', 'wav', 'pipe:1']
    stream = io.BytesIO(subprocess.check_output(command))
    actual_rate, channels = _float_wave_header(stream)
    if actual_rate != rate:
        raise ValueError('Unexpected decoded sample rate.')
    return np.frombuffer(stream.read(), dtype='<f4').reshape(-1, channels).astype(float)


def motion_contour(x, rate=16000, step=.08):
    # YIN cumulative mean normalized difference, 64 ms frames, 70–700 Hz.
    count = max(2, int(np.ceil(len(x) / (rate * step))))
    minimum, maximum, size = int(rate / 700), int(rate / 70), 1024
    pitches, energies, confidence = [], [], []
    padded = np.pad(x, (size // 2, size // 2))
    for i in range(count):
        start = int(i * rate * step)
        frame = padded[start:start + size]
        if len(frame) < size:
            frame = np.pad(frame, (0, size - len(frame)))
        energies.append(float(np.sqrt(np.mean(frame ** 2))))
        frame = frame - frame.mean()
        autocorrelation = np.fft.irfft(abs(np.fft.rfft(frame, 2048)) ** 2, 2048)[:maximum + 1]
        cumulative = np.r_[0, np.cumsum(frame ** 2)]
        lags = np.arange(maximum + 1)
        difference = np.maximum(0, cumulative[size-lags] + cumulative[size] - cumulative[lags] - 2 * autocorrelation)
        normalized = np.ones(maximum + 1)
        normalized[1:] = difference[1:] * lags[1:] / np.maximum(np.cumsum(difference[1:]), 1e-20)
        candidates = np.flatnonzero(normalized[minimum:maximum] < .2) + minimum
        lag = int(candidates[0]) if len(candidates) else int(np.argmin(normalized[minimum:maximum]) + minimum)
        while lag + 1 < maximum and normalized[lag + 1] < normalized[lag]:
            lag += 1
        quality = 1 - normalized[lag]
        refined = float(lag)
        if 0 < lag < maximum:
            a, b, c = normalized[lag-1:lag+2]
            if abs(a - 2*b + c) > 1e-10:
                refined += float(np.clip(.5 * (a-c) / (a-2*b+c), -.5, .5))
        pitches.append(rate / refined if quality > .6 and energies[-1] > .005 else np.nan)
        confidence.append(float(quality))
    valid = np.isfinite(pitches)
    if np.count_nonzero(valid) < 3:
        raise ValueError('Not enough periodic content to derive a pitch contour.')
    pitch = np.interp(np.arange(count), np.flatnonzero(valid), np.asarray(pitches)[valid])
    pitch = np.array([np.median(pitch[max(0, i-1):i+2]) for i in range(count)])
    energy = np.asarray(energies)
    energy = np.clip(energy / max(np.quantile(energy, .9), 1e-9), 0, 1) ** .7
    energy = np.convolve(np.pad(energy, (1, 1), mode='edge'), [.2, .6, .2], mode='valid')
    return {'stepMs': int(step * 1000), 'pitchHz': np.round(pitch, 1).tolist(),
            'energy': np.round(energy, 3).tolist()}, {
                'voiced_fraction': float(np.mean(valid)),
                'pitch_hz_percentiles_10_50_90': np.round(np.quantile(pitch, [.1, .5, .9]), 1).tolist()}


def build(path, duration=120, ffmpeg=None):
    ffmpeg = ffmpeg_path(ffmpeg)
    x = decode(path, 48000, duration, ffmpeg)
    if len(x) < 48000 or not np.isfinite(x).all():
        raise ValueError('Supply at least one second of finite audio.')
    frames = np.lib.stride_tricks.sliding_window_view(x, 4096, axis=0)[::1024]
    rms = np.sqrt(sum(np.mean(frames[:, c, :] ** 2, axis=1) for c in range(x.shape[1])) / x.shape[1])
    used = rms >= max(.001, float(rms.max()) * 10 ** (-30 / 20))
    power = np.zeros((int(used.sum()), 2049))
    for channel in range(x.shape[1]):
        active = frames[used, channel, :]
        power += abs(np.fft.rfft((active-active.mean(axis=1, keepdims=True)) * np.hanning(4096), axis=1)) ** 2 / x.shape[1]
    frequencies = np.fft.rfftfreq(4096, 1/48000)
    mean, upper = power.mean(axis=0), np.quantile(power, .95, axis=0)
    edges = np.r_[np.arange(80, 22000, 100), 22000]
    centers = (edges[:-1] + edges[1:]) / 2
    # Upper-percentile power keeps briefly bright harmonics and consonants.
    density = np.array([np.mean((.5*mean+.5*upper)[(frequencies >= a) & (frequencies < b)])
                        for a, b in zip(edges[:-1], edges[1:])])
    bright = density >= density.max() * 10 ** (-36 / 10)
    selected = np.zeros_like(bright)
    for i in np.flatnonzero(bright):
        kerf = max(150, centers[i] * .04)
        selected |= (edges[:-1] < edges[i+1] + kerf) & (edges[1:] > edges[i] - kerf)
    # Close only short gaps; keep genuinely unoccupied high frequencies empty.
    for i in range(1, len(selected)-2):
        if selected[i-1] and selected[i+2]:
            selected[i:i+2] = True
    levels = np.maximum(.22, np.minimum(1, density / density.max()) ** .25)
    bands = []
    for i in np.flatnonzero(selected):
        gain = round(float(levels[i]), 4)
        if bands and bands[-1]['highHz'] == edges[i] and abs(20*np.log10(gain / bands[-1]['level'])) < 1.25:
            previous = bands[-1]
            previous['level'] = max(previous['level'], gain)
            previous['highHz'] = float(edges[i+1])
        else:
            bands.append({'lowHz': float(edges[i]), 'highHz': float(edges[i+1]), 'level': gain})
    if len(bands) > 64:
        raise ValueError('Profile needs more than 64 bands; use coarser bins.')
    speech = decode(path, 16000, duration, ffmpeg)
    channel = int(np.argmax(np.mean(speech ** 2, axis=0)))
    motion, motion_stats = motion_contour(speech[:, channel])
    profile = validate_profile({'version': 1, 'name': 'Adjusted', 'bands': bands, 'motion': motion})
    covered = np.zeros_like(frequencies, dtype=bool)
    for band in bands:
        covered |= (frequencies >= band['lowHz']) & (frequencies <= band['highHz'])
    stats = {'method': '100 Hz mean/p95 power, -36 dB threshold, 150 Hz or 4% kerf, compressed gains',
             'source_sha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(),
             'seconds_analyzed': len(x)/48000, 'sample_rate': 48000, 'channels': x.shape[1], 'active_frames': int(used.sum()),
             'bands': len(bands), 'range_hz': [bands[0]['lowHz'], bands[-1]['highHz']],
             'occupied_bandwidth_hz': sum(b['highHz']-b['lowHz'] for b in bands),
             'mean_spectral_energy_coverage': float(mean[covered].sum()/mean.sum()),
             'gain_floor': .22, 'motion_points': len(motion['pitchHz']), **motion_stats}
    return profile, stats, frequencies, mean, upper


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('-o', '--output', type=Path, required=True)
    p.add_argument('--report', type=Path)
    p.add_argument('--duration', type=float, default=120, help='Maximum seconds; 0 analyzes the complete file.')
    p.add_argument('--ffmpeg')
    args = p.parse_args()
    if args.duration < 0:
        p.error('Duration must be non-negative.')
    profile, stats, _, _, _ = build(args.input, args.duration, args.ffmpeg)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(profile, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(stats, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(stats, indent=2))


if __name__ == '__main__':
    main()
