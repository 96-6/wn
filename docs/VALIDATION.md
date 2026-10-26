# WN 1.1.0 validation · 2026-10-26

Package app.wn, version code 2, minimum API 26, target/compile API 35. Signed development APK.

- Clean compilation and installable APK: passed.
- JVM tests: **25 passed**, zero failures/errors. See unit-test-summary.json.
- Android lint: **No issues found**.
- PC tests: **14 passed**, including generated fixtures in ten codec/container variants, extensionless/Unicode filenames, raw PCM, audio inside video, JSON round trips, output protection, sparse profiling and opposite-phase stereo.
- Android instrumentation: **8 passed** on the Android 15 / API 35 emulator in [GitHub Actions](https://github.com/96-6/wn/actions/runs/40248359359). These cover saved profiles/generators, return to Default alone, background playback, media-controller/notification Stop, private JSON persistence, cancellation and WAV/MP3/FLAC/AAC file profiling.
- Installer signature verified with apksigner.

APK SHA-256: c65f289de500f30b107506c5d80908e5a449d32e27003c2876b05c92c385fc7d

Hardware microphone capture and manufacturer battery management require checking on an actual phone. No claim of speaker identification or selective cancellation is made. Profiles describe static spectral ranges.

The prior 1.0 signing key was not available in this environment. Export old JSON before uninstalling 1.0; then install 1.1 and import it. Current signing material is kept out of the public repository.
