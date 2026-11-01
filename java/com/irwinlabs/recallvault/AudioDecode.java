package com.irwinlabs.recallvault;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a recording to the 16 kHz mono float PCM whisper.cpp eats.
 *
 * WAV (PCM16) is parsed directly; everything else (the app's own
 * M4A/AAC voice notes and meetings) goes through MediaExtractor +
 * MediaCodec. The PCM conversion - downmix + linear-interpolation
 * resample - is a pure static function so the JVM tests can pin it.
 *
 * v0.4.0: part of moving ALL processing on-device (no server).
 */
public final class AudioDecode {
    public static final int TARGET_RATE = 16000;

    private AudioDecode() {}

    /** Pure: interleaved little-endian PCM16 -> mono float at 16 kHz. */
    public static float[] convertPcm16(byte[] pcm, int channels, int sampleRate) {
        int frames = pcm.length / 2 / Math.max(1, channels);
        if (frames == 0 || sampleRate <= 0) return new float[0];
        double[] mono = new double[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int idx = (i * channels + c) * 2;
                int v = (pcm[idx] & 0xFF) | (pcm[idx + 1] << 8);
                sum += v;
            }
            mono[i] = (sum / (double) channels) / 32768.0;
        }
        if (sampleRate == TARGET_RATE) {
            float[] out = new float[frames];
            for (int i = 0; i < frames; i++) out[i] = (float) mono[i];
            return out;
        }
        int outLen = (int) Math.round(frames * (double) TARGET_RATE / sampleRate);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * (double) sampleRate / TARGET_RATE;
            int i0 = (int) Math.floor(pos);
            int i1 = Math.min(i0 + 1, frames - 1);
            double frac = pos - i0;
            out[i] = (float) (mono[i0] * (1 - frac) + mono[i1] * frac);
        }
        return out;
    }

    /** Entry point: any recording the app holds -> 16 kHz mono floats. */
    public static float[] decodeTo16kMono(File f) throws Exception {
        if (isWav(f)) return decodeWav(f);
        return decodeWithCodec(f);
    }

    private static boolean isWav(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[12];
            if (in.read(h) < 12) return false;
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        }
    }

    private static float[] decodeWav(File f) throws Exception {
        byte[] all;
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            all = bos.toByteArray();
        }
        int channels = 1, rate = TARGET_RATE, bits = 16;
        int dataOff = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int size = (all[pos + 4] & 0xFF) | ((all[pos + 5] & 0xFF) << 8)
                     | ((all[pos + 6] & 0xFF) << 16) | ((all[pos + 7] & 0xFF) << 24);
            if (id.equals("fmt ")) {
                channels = (all[pos + 10] & 0xFF) | ((all[pos + 11] & 0xFF) << 8);
                rate = (all[pos + 12] & 0xFF) | ((all[pos + 13] & 0xFF) << 8)
                     | ((all[pos + 14] & 0xFF) << 16) | ((all[pos + 15] & 0xFF) << 24);
                bits = (all[pos + 22] & 0xFF) | ((all[pos + 23] & 0xFF) << 8);
            } else if (id.equals("data")) {
                dataOff = pos + 8;
                dataLen = Math.min(size, all.length - dataOff);
                break;
            }
            pos += 8 + size + (size % 2);
        }
        if (dataOff < 0) throw new Exception("WAV without a data chunk");
        if (bits != 16) throw new Exception("Only 16-bit WAV is supported");
        byte[] pcm = new byte[dataLen];
        System.arraycopy(all, dataOff, pcm, 0, dataLen);
        return convertPcm16(pcm, channels, rate);
    }

    private static float[] decodeWithCodec(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = mf; break; }
            }
            if (track < 0 || fmt == null) throw new Exception("No audio track in the file");
            ex.selectTrack(track);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            String mime = fmt.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            List<byte[]> chunks = new ArrayList<>();
            int total = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long deadline = System.currentTimeMillis() + 10 * 60_000L;
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new Exception("Audio decode timed out");
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIdx);
                        byte[] pcm = new byte[info.size];
                        out.position(info.offset);
                        out.get(pcm, 0, info.size);
                        chunks.add(pcm);
                        total += info.size;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    codec.releaseOutputBuffer(outIdx, false);
                }
            }
            byte[] all = new byte[total];
            int off = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            return convertPcm16(all, channels, rate);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            ex.release();
        }
    }
}
