package com.irwinlabs.recallvault;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Meeting recorder (v0.8.0): AudioRecord PCM straight into a WAV
 * file (16 kHz mono 16-bit - exactly what Whisper eats), so live
 * captions can read finished chunks from the file while recording
 * continues. The WAV header sizes are re-patched after every buffer
 * write, so even a killed app leaves a playable file.
 *
 * MediaRecorder's M4A cannot do this (a growing M4A is not safely
 * decodable mid-recording), which is why meetings moved to WAV.
 * Cost: ~1.9 MB/min of audio. Voice notes still use M4A.
 */
public final class PcmRecorder {

    public static final int RATE = 16000;
    public static final int BYTES_PER_SEC = RATE * 2;

    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running = false;
    private RandomAccessFile raf;
    private volatile long dataBytes = 0;
    private File file;

    public synchronized void start(File wav) throws Exception {
        if (running) throw new Exception("already recording");
        int minBuf = AudioRecord.getMinBufferSize(RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new Exception("AudioRecord unavailable");
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBuf, RATE)); // ~1s of samples
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            rec = null;
            throw new Exception("AudioRecord failed to initialize");
        }
        file = wav;
        raf = new RandomAccessFile(wav, "rw");
        raf.setLength(0);
        writeHeader(0);
        dataBytes = 0;
        running = true;
        rec.startRecording();
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "pcm-recorder");
        thread.start();
    }

    private void loop() {
        short[] buf = new short[4096];
        byte[] bytes = new byte[buf.length * 2];
        while (running) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) {
                bytes[2 * i] = (byte) (buf[i] & 0xff);
                bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff);
            }
            try {
                raf.write(bytes, 0, n * 2);
                dataBytes += n * 2L;
                patchSizes();
            } catch (Exception e) {
                running = false;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(3000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
            rec = null;
        }
        if (raf != null) {
            try { patchSizes(); raf.close(); } catch (Exception ignored) {}
            raf = null;
        }
    }

    public boolean isRunning() { return running; }

    public long dataBytes() { return dataBytes; }

    /** Read recorded PCM (little-endian shorts) starting at a byte offset. */
    public byte[] readPcm(long fromByte, int len) throws Exception {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            in.seek(44 + fromByte);
            byte[] out = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(out, got, len - got);
                if (n < 0) break;
                got += n;
            }
            if (got < len) {
                byte[] trimmed = new byte[got];
                System.arraycopy(out, 0, trimmed, 0, got);
                return trimmed;
            }
            return out;
        } finally {
            in.close();
        }
    }

    public static float[] toFloats(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int s = (pcm[2 * i] & 0xff) | ((pcm[2 * i + 1]) << 8);
            out[i] = s / 32768f;
        }
        return out;
    }

    private void writeHeader(long dataLen) throws Exception {
        raf.seek(0);
        raf.write(new byte[]{
            'R', 'I', 'F', 'F',
            0, 0, 0, 0, // patched below
            'W', 'A', 'V', 'E',
            'f', 'm', 't', ' ',
            16, 0, 0, 0,
            1, 0, // PCM
            1, 0, // mono
            (byte) (RATE & 0xff), (byte) ((RATE >> 8) & 0xff), 0, 0,
            (byte) (BYTES_PER_SEC & 0xff), (byte) ((BYTES_PER_SEC >> 8) & 0xff), 0, 0,
            2, 0, // block align
            16, 0, // bits
            'd', 'a', 't', 'a',
            0, 0, 0, 0 // patched below
        });
        patchSizes(dataLen);
        raf.seek(44 + dataLen);
    }

    private void patchSizes() throws Exception {
        patchSizes(dataBytes);
    }

    private void patchSizes(long dataLen) throws Exception {
        long pos = raf.getFilePointer();
        raf.seek(4);
        writeIntLE((int) (36 + dataLen));
        raf.seek(40);
        writeIntLE((int) dataLen);
        raf.seek(pos);
    }

    private void writeIntLE(int v) throws Exception {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
        raf.write((v >> 16) & 0xff);
        raf.write((v >> 24) & 0xff);
    }
}
