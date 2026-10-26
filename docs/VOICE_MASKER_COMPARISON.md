# Voice masker comparison · 2026-10-27

**Recommendation: Noise-first.** A gentle spectral rebalancing of continuous noise gave the lowest validation and final-test ESTOI. The light Speech-like blend was close, and had a slightly lower final-test STOI. Adding substantially more pseudo-voice content did not improve the primary score. Both selected new variants scored below the older modes on both final-test metrics and under both level-matching methods. Lower scores mean lower *predicted* intelligibility, not a percentage of words understood.

| Generator | STOI, RMS matched | ESTOI, RMS matched | STOI, LUFS matched | ESTOI, LUFS matched |
|---|---:|---:|---:|---:|
| Continuous (1.3) | 0.35959 | 0.21890 | 0.37417 | 0.23162 |
| Oscillating (1.3) | 0.35892 | 0.22296 | 0.37359 | 0.23576 |
| Voice masker · Noise-first | 0.35150 | 0.20998 | 0.34730 | 0.20640 |
| Voice masker · Speech-like blend | 0.35101 | 0.21050 | 0.34770 | 0.20767 |

The difference between Noise-first and Speech-like blend is small; this experiment does not establish a meaningful perceptual winner between them. Noise-first is the conservative default because it maintains coverage continuously and costs less CPU. Speech-like blend is one button away; voice level, noise level, speed, variation, pitch, spread and 1–4 voices remain adjustable. These are synthetic pseudo-voices with moving vocal-tract resonances and voiced/unvoiced excitation, not natural recorded babble or a speech clone.

## Protocol

- Source: supplied 32.99679-second recording, SHA-256 `e1f219c0455339b5aae01b52274a14f219f5c48147bb40e01672554498124ec0`.
- Fit spectrum, pitch/energy network and vocal-envelope codebook using 0–20 seconds.
- Select settings using 20–26 seconds: five spectral tilts × five voice power fractions plus the two existing modes, ten seeds, three masker/reference ratios (0, +3, +6 dB). Selection uses mean ESTOI at equal **full-band RMS**.
- Final scoring uses 26–32.99679 seconds, 20 different seeds and the same three ratios. A second scoring pass matches full-band integrated LUFS (differences 0, +3, +6 LU) instead of RMS.
- Each candidate is rendered alone, at full generator level/Master and Mono width. The Default reference-curve generator is excluded from these measurements.
- **1,290 scored mixtures** total. Generators are rendered from the actual Java Android audio core, with a two-second warmup. The shared noise bed uses the same seed across relevant candidates. The zero-tilt, zero-voice condition reproduces the existing continuous generator.
- Level matching is measured at 48 kHz, before resampling. Both reference and mixture receive the same final attenuation if needed to avoid clipping. STOI/ESTOI analysis then uses 10 kHz, as required by the implementation.
- Four pseudo-voices are used for the voice candidates. Each independently samples neural pitch/energy controls and learned vocal-tract spectral states. Formant/envelope transitions and event durations are randomized; no source timeline wraps and no speech waveform is replayed.
- Chosen defaults: noise tilt −0.25; Noise-first voice fraction 0; light Speech-like blend fraction 0.05. The UI maps these to independent voice/noise gain controls. Existing oscillating settings are preserved separately.

The shipping spectral bands and pitch model remain compatible with the existing Adjusted profile. The new 24-state vocal-tract model is fitted to the complete recording after configuration selection. Therefore these split-based scores support choosing a configuration, not independent validation of the final full-recording fit.

## Scope and limits

This is an engineering comparison within one recording. The final portion is excluded from model fitting and parameter selection, but the recording was also used in exploratory development. It is not an independent speaker, session or sentence corpus. The recording is used as the reference and is not guaranteed to be studio-isolated clean speech.

STOI and ESTOI are prediction tools. They do not capture every effect of competing voices, linguistic distraction, human familiarity, binaural separation or room acoustics. There was no human word-recognition test and no proof of physical phase cancellation. Phone frequency response, output level, distance and placement can change real results. LUFS is a digital loudness estimate, not a calibrated room-level listening test.

The JSON includes paired ESTOI differences and seed-bootstrap intervals. Those intervals describe random-seed variability for this fixed clip only; they are not population-level confidence intervals for listeners. Full per-trial data is in [voice-comparison-trials.csv](voice-comparison-trials.csv). No source audio is published.

## Reproduce

Install JDK 17 and the Python evaluation requirements. Supply an `org.json:json:20240303` JAR (e.g. the one downloaded by Gradle):

```sh
python -m pip install -r tools/requirements-evaluation.txt
python tools/compare_maskers.py recording.flac --output comparison --json-jar /path/to/json-20240303.jar
```

The CLI defaults reproduce the split, grid and seeds above. `--java-home` selects a particular JDK. It writes the full comparison, summary JSON and CSV, and fits only the first 20 seconds before selection. For a shorter source, set suitable `--fit-seconds` and `--select-end` values, keeping at least three seconds each for selection and testing.

## References

- Jensen & Taal (2016), *An Algorithm for Predicting the Intelligibility of Speech Masked by Modulated Noise Maskers*. https://doi.org/10.1109/TASLP.2016.2585878
- Python STOI/ESTOI implementation: https://github.com/mpariente/pystoi
- BS.1770 loudness implementation: https://github.com/csteinmetz1/pyloudnorm

Local metric versions: numpy 2.3.5, scipy 1.17.0, pystoi 0.4.1, pyloudnorm 0.2.0.
