# WN 1.1

Offline Android noise mixer with saved spectral profiles, microphone/file profiling, and a desktop Python profiler. Android 8+ (API 26); target API 35. No account or network permission.

## Downloads

- [Install WN 1.1.0 APK](https://github.com/96-6/wn/raw/refs/heads/main/apk/WN-1.1.0.apk)
- [PC profiler](tools/profile_audio.py)
- [JSON example](presets/adjusted-example.json)

The installer is a signed development APK. For version 1.0, export its adjusted JSON first, uninstall the old app, then install 1.1 and import that JSON. The previous temporary environment's signing key was unavailable, so 1.1 cannot replace 1.0 in place. Clearing/uninstalling an app removes its private data.

## Android

**Mixer:** Default is always present. Add up to seven more generators (eight total), choose a saved profile for each, and set each generator's level and enabled state independently. Remove any additional generator to return to Default alone. Removing a generator never deletes its profile. Master controls the final mix; stereo width is Mono, Narrow, Default or Wide. Android chooses the media output route.

**Profiles:** Import JSON, create a profile from microphone/audio, rename, export, or delete unused profiles. Up to 128 profiles are stored privately on the device and committed to disk before the app reports success. Repeated imports create new saved profiles rather than overwriting existing ones.

**Microphone:** Choose a name, duration (1–120 seconds) and Narrow/Balanced/Broad selection. Recording requests microphone permission only when needed, stops WN playback, and ends if the screen leaves the foreground. Finish recording early with the button. No raw microphone recording is retained. The generated profile saves automatically; then add a generator or export JSON.

**Audio file:** Choose a local audio file or video containing audio. Start offset and duration select a segment; the first supported audio track is analyzed. Playback may continue during file analysis. Decoding uses Android's installed codecs: commonly WAV/PCM, MP3, AAC/M4A, FLAC, Ogg/Vorbis, Opus and AMR, depending on the device. For unsupported formats, use the PC tool and import its JSON.

**Background playback:** A media foreground service owns audio synthesis, notification/media controls, audio focus and the CPU wake lock. Closing the mixer does not stop it. Stop releases audio resources and the wake lock. Transient focus interruptions pause/duck playback; permanent loss stops it. If a manufacturer restricts it, use Battery settings → Unrestricted. Force-stop, reboot and device-specific battery policies can stop any ordinary app; playback does not auto-start at boot.

## PC profiler

Python 3.10+ and NumPy. FFmpeg is found on PATH, supplied with --ffmpeg, or obtained from the optional imageio-ffmpeg wheel.

~~~sh
python -m pip install -r tools/requirements-profiler.txt
python tools/profile_audio.py --gui
~~~

No arguments also opens the small Tkinter GUI. Windows/macOS Python installers commonly provide Tk; Linux may need the distribution's python3-tk package. The GUI has file selection, microphone capture, profile naming, selection modes, JSON output and diagnostic reports.

~~~sh
# Profile a file. No extension whitelist; FFmpeg detects its actual container/codec.
python tools/profile_audio.py voice.m4a -o voice.json --name "Voice" --diagnostics debug

# Analyze the whole file, or a segment.
python tools/profile_audio.py voice.flac -o full.json --duration 0
python tools/profile_audio.py video.mkv -o segment.json --start 12 --duration 30 --stream 0

# Headerless raw PCM needs its missing format information.
python tools/profile_audio.py capture.raw -o raw.json --raw-format s24le --raw-sample-rate 48000 --raw-channels 2

# Inspect/normalize an existing profile.
python tools/profile_audio.py --import-json voice.json --print
python tools/profile_audio.py --import-json voice.json -o copy.json

# Show what this FFmpeg build actually supports.
python tools/profile_audio.py --formats
~~~

For microphone input:

~~~sh
python -m pip install -r tools/requirements-microphone.txt
python tools/profile_audio.py --list-devices
python tools/profile_audio.py --mic --duration 15 --device 0 -o microphone.json
~~~

Sounddevice/PortAudio is optional and used only for microphone capture. Linux may need libportaudio2. Ctrl+C finishes a CLI microphone capture early. GUI Cancel discards the unfinished analysis.

Modes match Android. Advanced CLI parameters are --coverage, --occupancy and --max-bands. Diagnostic output includes analysis.json, spectrum.csv and spectrum.svg. Existing JSON outputs are protected unless --force is given or replacement is confirmed in the GUI.

File support follows the **actual FFmpeg build's** demuxers/decoders, including audio tracks inside video containers. It is not limited to WAV/MP3; tests include FLAC, AAC/M4A, Ogg, Opus, WMA, AIFF, CAF, float/24-bit WAV, extensionless files and raw PCM. Encrypted/DRM-protected data and formats missing from that FFmpeg build cannot be decoded. Source audio is processed locally.

## Portable profile JSON

The schema remains compatible with version 1.0:

~~~json
{
  "version": 1,
  "name": "Voice",
  "bands": [
    {"lowHz": 180, "highHz": 340, "level": 0.65},
    {"lowHz": 650, "highHz": 1100, "gainDb": -3},
    {"lowHz": 1800, "highHz": 2900, "level": 1}
  ]
}
~~~

Use 1–64 sorted non-overlapping bands within 20–22000 Hz, each at least 20 Hz wide. Each band uses level (linear amplitude, 0–1) or gainDb (-60–0), not both. Names have 1–80 readable characters; UTF-8 files may have a BOM. File size limit: 256 KiB. At least one level must be nonzero.

A ten-band profile can use levels instead of bands, at 31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000 and 16000 Hz:

~~~json
{"version":1,"name":"Flat","levels":[1,1,1,1,1,1,1,1,1,1]}
~~~

The fixed Default curve approximates the unlabeled reference image: levels .17, .29, .38, .56, .65, .59, .38, .28, .16, .04. It is shaped white noise, not an exact reconstruction of unknown original frequency labels/amplitude units.

![Reference](docs/reference-curve.png)

## Profiling method

Both tools use 4096-sample Hann windows with 50% overlap; spectra from individual channels are averaged as **power**, so opposite-phase stereo does not cancel. An RMS histogram rejects frames below -60 dBFS or 25 dB below the strongest frame. In the 80–8000 Hz range (limited by source Nyquist), power is grouped into 40-Hz bins, smoothed and ranked. Sparse ranges are selected up to the occupancy cap, then limited by the band-count cap.

| Mode | Requested energy | Maximum in-range occupancy | Maximum bands |
|---|---:|---:|---:|
| Narrow | 65% | 20% | 12 |
| Balanced | 80% | 35% | 16 |
| Broad | 90% | 50% | 24 |

Reported energy retention is measured in the analyzed frequency range. A diffuse recording may not reach the requested energy within the bandwidth cap. Exported gains follow the measured relative spectral density. This is static spectral masking, not speaker identification or selective voice cancellation; voices and other sounds can share the same frequencies. Use a clean sample of the desired speech.

## Development

JDK 17, Gradle 8.11.1 wrapper, Android SDK Platform 35/build-tools 35.0.0:

~~~sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
python -m unittest discover -s tools/tests -v
~~~

The checked-in APK is the locally signed deliverable; CI artifacts are separate development builds and may use a different debug certificate. Never publish a signing keystore. Supply your own private release key for a public production release.

GitHub Actions runs JVM/PC checks and Android device tests using an emulator with hardware acceleration. Microphone acoustics still require a real-device test. See [validation](docs/VALIDATION.md).
