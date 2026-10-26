# WN 1.3.0 validation · 2026-10-26

Package app.wn, version code 4, minimum API 26, target/compile API 35. Signed development APK; signing certificate matches 1.1/1.2 for in-place updates.

- JVM: **39 tests passed**, zero failures/errors. Includes Java/exporter neural inference parity, long stochastic rollouts without the source-period repeat, random fallback for old profiles, extreme controls/mixes, spectral limits and preview resampling/RMS/limiting/reference stability.
- PC: **21 tests passed**. Includes numerical gradient verification for the NumPy trainer, improved training likelihood, portable weights validation, profiling and codec/container coverage.
- Android lint: zero errors; one advisory that an existing Android test dependency has a newer version.
- App and instrumentation APKs compiled. The 17-test Android device suite adds live slider/Reset wiring, settings persistence, four preview codecs, once-through playback, normal-playback restoration and notification Stop. See [current CI results](https://github.com/96-6/wn/actions/workflows/android.yml).
- APK signature verified with apksigner.

APK SHA-256: 3b30f79e53468a2e0401959083adb865572124865277b9312ff1f7c12f8d2c2f

The built-in 335-parameter mixture-density network was trained on 411 transitions from the supplied recording's measured pitch/energy controls, using NumPy/CPU for 1,800 epochs (about 0.93 seconds). See motion-training.json. Training likelihood reports fit, not a measurement of intelligibility or masking effectiveness. The Colab/PyTorch CUDA runner is provided; local training/testing used CPU.

Playback samples new stochastic control events and durations, with independent seeds and occasional data-distribution anchors. It never advances through or wraps the source timeline. Older untrained motion JSON uses conditional random resampling. The 80–6,780 Hz spectral selection remains the same. Fine-control defaults retain the previous balance, detuning and nominal speed; migration preserves mixer/profile choices.

Preview uses one AudioTrack and the existing foreground-service/focus/Stop path. Its file plays once, with a roughly +6 dB starting masker/source RMS ratio by default. After initial calibration, the source reference is held fixed while gains change; Rebalance is explicit. A linked peak limiter protects the sum. File data is held only in bounded memory. Source audio and the signing key are not published.

Real-room masking depends on speaker level, placement and acoustics. This is stochastic spectral masking, not phase cancellation or speaker identification. Hardware microphone capture and manufacturer battery management still require an actual phone check.
