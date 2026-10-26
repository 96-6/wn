# WN 1.4.0 validation · 2026-10-27

Package app.wn, version code 5, minimum API 26, target/compile API 35. Signed development APK; certificate matches 1.1–1.3 for in-place updates.

- JVM: **47 tests passed**, zero failures/errors. Includes learned vocal-state JSON validation/round trips, noise-baseline equivalence, fresh randomized voice audio, vocal spectral bounds, eight-generator extreme controls, independent mode controls and crossfades, neural exporter parity, and preview/RMS checks.
- PC: **26 tests passed**. Includes LPC estimation, valid codebooks for stationary voices, opposite-phase stereo, voice JSON preservation/rejection, neural gradient verification, training and codec/container profiling.
- Android lint: zero errors; one existing advisory about a newer Android test dependency.
- App and instrumentation APKs compiled. The **21-test Android suite** adds migration preservation, voice preset/count controls, live preview type changes without resetting the source, and background speech-like playback/notification Stop. See [current CI results](https://github.com/96-6/wn/actions/workflows/android.yml).
- APK signature verified with apksigner; packaged voice data matches the source JSON.

APK SHA-256: 0ea77be6e267c915aa5fcb9cd62c098194a75f4be273586551c4f251d2396833

The comparison scores 1,290 mixtures from the real Java synthesis core, with separate fit/selection/test portions of the supplied recording, 10 selection seeds, 20 different test seeds, 0/+3/+6 masker ratios, full-band RMS matching and a LUFS-matched secondary check. Both selected new variants improve the final-test STOI/ESTOI means over the older modes. Noise-first is the primary ESTOI recommendation; the speech-like blend is close. See [protocol, numbers and limits](VOICE_MASKER_COMPARISON.md), voice-comparison.json and the complete CSV. Final-core reference re-renders reproduce the archived metric cases.

The supplied profile keeps its original spectral bands and pitch/energy network, adding a 24-state vocal-envelope/transition model fitted to the full recording. Each speech stream samples fresh stochastic states and timings. No source waveform or timeline loops are used. Noise-first avoids ongoing voice synthesis when voice level is zero. Scene-only changes do not repeatedly serialize the profile catalogue. Existing oscillator controls, levels, profile names, stereo width and generator choices are preserved separately from voice settings.

Generated-only MP3 samples are provided for listening. They are not used for quantitative scoring. The original recording and signing key are not published. PC/Colab audio training now creates voice-capable JSON; existing JSON keeps its voice data. On-device sparse profiling continues to create spectral-noise profiles.

These are digital monaural intelligibility predictions for one recording, not human word scores, proof of speaker-specific cancellation or calibrated room tests. Real performance depends on the speaker, level, placement and room. Hardware microphone capture and manufacturer battery behavior still require actual-phone checks.
