package app.wn;

import android.content.Context;
import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.SystemClock;
import app.wn.audio.SpectrumProfiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

/** Only called by a worker owned by the visible profiling screen. */
final class AndroidProfiler {
    static final class Control { volatile boolean cancelled, finish; }
    interface Progress { void update(double seconds); }
    private AndroidProfiler() {}
    static void check(Control c) { if (c.cancelled) throw new CancellationException("Cancelled"); }

    @SuppressLint("MissingPermission") // ProfilingActivity checks RECORD_AUDIO before starting this foreground-only operation.
    static SpectrumProfiler.Result microphone(String name, double seconds, SpectrumProfiler.Tuning tuning,
                                              Control control, Progress progress) {
        AudioRecord record = null;
        try {
            check(control);
            for (int rate : new int[]{48000, 44100, 16000}) {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                try {
                    record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(minimum * 2, 8192)).build();
                    if (record.getState() == AudioRecord.STATE_INITIALIZED) break;
                    record.release(); record = null;
                } catch (IllegalArgumentException e) { record = null; }
            }
            if (record == null) throw new IllegalStateException("Microphone is unavailable.");
            SpectrumProfiler profiler = new SpectrumProfiler(record.getSampleRate(), 1);
            short[] pcm = new short[2048]; float[] values = new float[pcm.length];
            check(control); record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Microphone did not start.");
            long deadline = SystemClock.elapsedRealtime() + (long) (seconds * 1000) + 2000, lastUpdate = 0;
            while (profiler.seconds() < seconds && !control.finish) {
                check(control);
                if (SystemClock.elapsedRealtime() > deadline) break;
                int count = record.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("Microphone read failed (" + count + ").");
                if (count == 0) { SystemClock.sleep(10); continue; }
                int remaining = Math.max(0, (int) Math.ceil((seconds - profiler.seconds()) * record.getSampleRate()));
                count = Math.min(count, remaining);
                for (int i = 0; i < count; i++) values[i] = pcm[i] / 32768f;
                profiler.accept(values, 0, count);
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate >= 200) { progress.update(profiler.seconds()); lastUpdate = now; }
            }
            check(control); return profiler.finish(name, tuning);
        } finally {
            if (record != null) {
                try { record.stop(); } catch (IllegalStateException ignored) { }
                record.release();
            }
        }
    }

    static SpectrumProfiler.Result audioFile(Context context, Uri uri, String name, double start, double seconds,
                                             SpectrumProfiler.Tuning tuning, Control control, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            check(control);
            extractor.setDataSource(context, uri, null);
            int selected = -1; MediaFormat format = null; String decoderName = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    String decoder = "audio/raw".equals(mime) ? null : new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(candidate);
                    if (!"audio/raw".equals(mime) && decoder == null) continue;
                    selected = i; format = candidate; decoderName = decoder; break;
                }
            }
            if (selected < 0 || format == null) throw new IOException("The file contains no supported audio track.");
            if (format.containsKey("crypto-key")) throw new IOException("Encrypted audio cannot be profiled.");
            extractor.selectTrack(selected);
            long startUs = (long) (start * 1_000_000), endUs = startUs + (long) (seconds * 1_000_000);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String mime = format.getString(MediaFormat.KEY_MIME);
            PcmSink sink = null;
            if ("audio/raw".equals(mime)) {
                sink = new PcmSink(format);
                int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 262144;
                ByteBuffer data = ByteBuffer.allocateDirect(Math.max(65536, Math.min(capacity, 8 * 1024 * 1024)));
                long lastUpdate = 0;
                while (true) {
                    check(control); long time = extractor.getSampleTime(); if (time < 0 || time >= endUs) break;
                    data.clear(); int size = extractor.readSampleData(data, 0); if (size < 0) break;
                    data.position(0).limit(size); sink.accept(data, time, startUs, endUs);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 200) { progress.update(sink.profiler.seconds()); lastUpdate = now; }
                    extractor.advance();
                }
            } else {
                codec = MediaCodec.createByCodecName(decoderName);
                codec.configure(format, null, null, 0); codec.start();
                boolean inputDone = false, outputDone = false;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long lastData = SystemClock.elapsedRealtime(), lastUpdate = 0;
                while (!outputDone) {
                    check(control);
                    if (!inputDone) {
                        int input = codec.dequeueInputBuffer(10000);
                        if (input >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(input);
                            if (buffer == null) throw new IOException("Decoder input unavailable.");
                            buffer.clear(); long time = extractor.getSampleTime();
                            if (time < 0 || time >= endUs) {
                                codec.queueInputBuffer(input, 0, 0, Math.max(0, time), MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true;
                            } else {
                                int size = extractor.readSampleData(buffer, 0);
                                if (size < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                                else { codec.queueInputBuffer(input, 0, size, time, 0); extractor.advance(); }
                            }
                        }
                    }
                    int output = codec.dequeueOutputBuffer(info, 10000);
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat next = codec.getOutputFormat();
                        if (sink == null) sink = new PcmSink(next);
                        else sink.checkFormat(next);
                    } else if (output >= 0) {
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (sink == null) sink = new PcmSink(codec.getOutputFormat());
                                ByteBuffer buffer = codec.getOutputBuffer(output);
                                if (buffer == null) throw new IOException("Decoder output unavailable.");
                                buffer.position(info.offset).limit(info.offset + info.size);
                                sink.accept(buffer, info.presentationTimeUs, startUs, endUs);
                                lastData = SystemClock.elapsedRealtime();
                                if (lastData - lastUpdate >= 200) { progress.update(sink.profiler.seconds()); lastUpdate = lastData; }
                                if (info.presentationTimeUs >= endUs || sink.profiler.seconds() >= seconds) outputDone = true;
                            }
                            outputDone |= (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } finally { codec.releaseOutputBuffer(output, false); }
                    }
                    if (SystemClock.elapsedRealtime() - lastData > 15000) throw new IOException("Audio decoder stalled.");
                }
            }
            check(control);
            if (sink == null) throw new IOException("The file contained no decoded audio.");
            return sink.profiler.finish(name, tuning);
        } catch (MediaCodec.CodecException e) {
            throw new IOException("This device could not decode the file. Use the PC profiler or convert it to PCM WAV. " + e.getMessage(), e);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            extractor.release();
        }
    }
    private static final class PcmSink {
        final int rate, channels, encoding, sampleBytes;
        final SpectrumProfiler profiler;
        final float[] block;
        PcmSink(MediaFormat format) throws IOException {
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            sampleBytes = encoding == 3 ? 1 : encoding == 2 ? 2 : encoding == 21 ? 3 : encoding == 4 || encoding == 22 ? 4 : 0;
            if (sampleBytes == 0) throw new IOException("Unsupported PCM encoding: " + encoding);
            profiler = new SpectrumProfiler(rate, channels); block = new float[2048 * channels];
        }
        void checkFormat(MediaFormat format) throws IOException {
            if (rate != format.getInteger(MediaFormat.KEY_SAMPLE_RATE) || channels != format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                throw new IOException("Audio changes format midstream. Use the PC profiler.");
        }
        void accept(ByteBuffer bytes, long timeUs, long startUs, long endUs) throws IOException {
            bytes.order(ByteOrder.LITTLE_ENDIAN);
            int frameBytes = sampleBytes * channels, frames = bytes.remaining() / frameBytes;
            if (bytes.remaining() % frameBytes != 0) throw new IOException("Incomplete PCM audio frame.");
            int from = Math.max(0, (int) Math.ceil((startUs - timeUs) * rate / 1_000_000.0));
            int to = Math.min(frames, (int) Math.ceil((endUs - timeUs) * rate / 1_000_000.0));
            if (from >= to) return;
            bytes.position(bytes.position() + from * frameBytes);
            int n = 0;
            for (int f = from; f < to; f++) {
                for (int c = 0; c < channels; c++) {
                    float value;
                    if (encoding == 4) value = bytes.getFloat();
                    else if (encoding == 3) value = ((bytes.get() & 255) - 128) / 128f;
                    else if (encoding == 2) value = bytes.getShort() / 32768f;
                    else if (encoding == 21) {
                        int v = (bytes.get() & 255) | (bytes.get() & 255) << 8 | bytes.get() << 16;
                        value = v / 8388608f;
                    } else value = (float) (bytes.getInt() / 2147483648.0);
                    block[n++] = value;
                }
                if (n == block.length) { profiler.accept(block, 0, n); n = 0; }
            }
            if (n > 0) profiler.accept(block, 0, n);
        }
    }
}
