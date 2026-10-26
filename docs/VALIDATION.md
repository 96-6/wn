# WN 1.2.0 validation · 2026-10-26

Package app.wn, version code 3, minimum API 26, target/compile API 35. Signed development APK.

- Android application and instrumentation APKs: compiled successfully.
- JVM tests: **30 passed**, zero failures/errors. Includes motion JSON validation/round trips, joined spectral bands, oscillator spectral containment, both types mixed with eight generators and output peak limits.
- Android lint: **No issues found**.
- PC tests: **18 passed**, including ten codec/container variants, phase-safe stereo, known pitch/cadence tracking, padded spectral selection and motion JSON preservation.
- Android instrumentation suite: 12 tests. Four new tests cover first-install Adjusted, upgrade preservation, a full eight-generator scene, persistent type selection, removal back to Default and actual simultaneous continuous/oscillating background playback. The existing eight cover playback/media controls, saved profiles, JSON persistence, cancellation and file decoding. See [current CI results](https://github.com/96-6/wn/actions/workflows/android.yml).
- APK signature verified with apksigner. Signing certificate matches 1.1.0, allowing an in-place update.

APK SHA-256: 87a49a482d0c0f2b15e8ea16d361cb704209fc7a1755711a617bc80037fa7f22

The profile analyzes the complete supplied 32.997 seconds and contains 23 adjoining gain regions from 80 to 6,780 Hz, covering 99.89% of mean spectral energy. The motion template contains 413 pitch/energy points. See adjusted-analysis.json and adjusted-spectrum.png. The source audio and signing key are not published.

Hardware microphone capture and manufacturer battery management still require checking on an actual phone. Measured spectral coverage does not measure real-room speech intelligibility or prove masking effectiveness. Profiles are static spectral shapes with optional procedural motion, not speaker identification or phase cancellation.

1.2 updates 1.1 in place. Users coming directly from 1.0 must export their JSON before uninstalling 1.0 because its signing key is unavailable.
