package si.ros.RosKasa.ai;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android nativni ekvivalent AudioWorklet:
 * Zajem 16 kHz / 16-bit PCM mono na ozadni niti z lokalno RMS VAD zaznavo pavz
 * (za sprotno knjiženje po gostih med narekovanjem) in podporo za Push-to-Talk.
 */
public class AudioCaptureVadManager {

    public interface Listener {
        void onSpeechChunkReady(byte[] wavBytes);
        void onAudioLevel(float normalizedLevel);
        void onListeningStateChanged(boolean listening, boolean paused);
        void onError(String message);
    }

    private static final String TAG = "AudioCaptureVad";
    public static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // VAD pragovi za gostinsko okolje (hiter odziv s histerezo)
    private static final double SPEECH_START_RMS = 950.0;
    private static final double SPEECH_CONTINUE_RMS = 700.0;
    private static final long MIN_SPEECH_DURATION_MS = 300L;
    private static final long MAX_CHUNK_DURATION_MS = 15000L;

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean pushToTalkActive = new AtomicBoolean(false);
    private final AtomicBoolean discardRequested = new AtomicBoolean(false);

    private boolean pushMode = false;
    private int vadSilenceMs = 700;
    private Thread captureThread;

    public AudioCaptureVadManager(Listener listener) {
        this.listener = listener;
    }

    public void setPushMode(boolean pushMode) {
        this.pushMode = pushMode;
    }

    public void setVadSilenceMs(int vadSilenceMs) {
        this.vadSilenceMs = Math.max(350, Math.min(5000, vadSilenceMs));
    }

    /**
     * Takoj zavrže trenutno sneman avdio medpomnilnik (npr. ko uporabnik tapne gumb predloga).
     */
    public void discardCurrentBuffer() {
        discardRequested.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (running.get()) {
            resume();
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuf <= 0) {
            if (listener != null) listener.onError("AudioRecord ni podprt na tej napravi.");
            return;
        }
        final int bufferSize = Math.max(minBuf * 2, SAMPLE_RATE / 5); // ~100ms+

        running.set(true);
        paused.set(false);
        captureThread = new Thread(() -> runCaptureLoop(bufferSize), "RosKasa-AudioVAD");
        captureThread.start();
        if (listener != null) listener.onListeningStateChanged(true, false);
    }

    public void pause() {
        if (!running.get()) return;
        paused.set(true);
        if (listener != null) listener.onListeningStateChanged(true, true);
    }

    public void resume() {
        if (!running.get()) {
            start();
            return;
        }
        paused.set(false);
        if (listener != null) listener.onListeningStateChanged(true, false);
    }

    public void beginPushToTalk() {
        pushToTalkActive.set(true);
        paused.set(false);
    }

    public void endPushToTalk() {
        pushToTalkActive.set(false);
    }

    public synchronized void stop() {
        running.set(false);
        paused.set(false);
        pushToTalkActive.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (listener != null) listener.onListeningStateChanged(false, false);
    }

    @SuppressLint("MissingPermission")
    private void runCaptureLoop(int bufferSize) {
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                if (listener != null) listener.onError("Inicializacija mikrofona ni uspela.");
                running.set(false);
                return;
            }

            recorder.startRecording();
            short[] frame = new short[640]; // 40ms pri 16 kHz
            ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
            boolean speechStarted = false;
            long speechStartMs = 0L;
            long lastSpeechMs = 0L;
            int lastSpeechByteOffset = 0;

            while (running.get()) {
                int read = recorder.read(frame, 0, frame.length);
                if (read <= 0) continue;

                if (discardRequested.compareAndSet(true, false) || paused.get()) {
                    if (speechStarted) {
                        pcmBuffer.reset();
                        speechStarted = false;
                        lastSpeechByteOffset = 0;
                    }
                    continue;
                }

                double rms = computeRms(frame, read);
                float norm = (float) Math.min(1.0, rms / 4000.0);
                if (listener != null) listener.onAudioLevel(norm);

                long now = System.currentTimeMillis();

                if (pushMode) {
                    if (pushToTalkActive.get()) {
                        if (!speechStarted) {
                            speechStarted = true;
                            speechStartMs = now;
                            pcmBuffer.reset();
                            lastSpeechByteOffset = 0;
                        }
                        appendShortsAsLittleEndian(pcmBuffer, frame, read);
                        lastSpeechByteOffset = pcmBuffer.size();
                    } else if (speechStarted) {
                        speechStarted = false;
                        long dur = now - speechStartMs;
                        if (dur >= MIN_SPEECH_DURATION_MS && pcmBuffer.size() > 0) {
                            byte[] wav = wrapPcmAsWav(pcmBuffer.toByteArray(), SAMPLE_RATE);
                            if (listener != null) listener.onSpeechChunkReady(wav);
                        }
                        pcmBuffer.reset();
                        lastSpeechByteOffset = 0;
                    }
                } else {
                    // VAD način: histereza (začetek pri SPEECH_START_RMS, nadaljevanje pri SPEECH_CONTINUE_RMS)
                    double threshold = speechStarted ? SPEECH_CONTINUE_RMS : SPEECH_START_RMS;
                    if (rms >= threshold) {
                        if (!speechStarted) {
                            speechStarted = true;
                            speechStartMs = now;
                            pcmBuffer.reset();
                            lastSpeechByteOffset = 0;
                        }
                        lastSpeechMs = now;
                        appendShortsAsLittleEndian(pcmBuffer, frame, read);
                        lastSpeechByteOffset = pcmBuffer.size();
                    } else if (speechStarted) {
                        appendShortsAsLittleEndian(pcmBuffer, frame, read);
                        long silenceDur = now - lastSpeechMs;
                        long totalDur = now - speechStartMs;
                        if (silenceDur >= vadSilenceMs || totalDur >= MAX_CHUNK_DURATION_MS) {
                            speechStarted = false;
                            if (totalDur >= MIN_SPEECH_DURATION_MS && lastSpeechByteOffset > 0) {
                                // Obreži odvečno končno tišino (pusti ~160ms repa), da je upload 25-35% manjši
                                byte[] fullPcm = pcmBuffer.toByteArray();
                                int trimLen = Math.min(fullPcm.length, lastSpeechByteOffset + (SAMPLE_RATE * 2 / 6));
                                byte[] trimmed = (trimLen < fullPcm.length)
                                        ? java.util.Arrays.copyOf(fullPcm, trimLen)
                                        : fullPcm;
                                byte[] wav = wrapPcmAsWav(trimmed, SAMPLE_RATE);
                                if (listener != null) listener.onSpeechChunkReady(wav);
                            }
                            pcmBuffer.reset();
                            lastSpeechByteOffset = 0;
                        }
                    }
                }
            }
        } catch (SecurityException se) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", se);
            if (listener != null) listener.onError("Manjka dovoljenje za mikrofon.");
        } catch (Exception e) {
            Log.e(TAG, "Audio capture error", e);
            if (listener != null) listener.onError("Napaka mikrofona: " + e.getMessage());
        } finally {
            running.set(false);
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Exception ignored) {}
                try {
                    recorder.release();
                } catch (Exception ignored) {}
            }
            if (listener != null) listener.onListeningStateChanged(false, false);
        }
    }

    private static double computeRms(short[] samples, int count) {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double s = samples[i];
            sum += s * s;
        }
        return count > 0 ? Math.sqrt(sum / count) : 0.0;
    }

    private static void appendShortsAsLittleEndian(ByteArrayOutputStream out, short[] samples, int count) {
        for (int i = 0; i < count; i++) {
            short s = samples[i];
            out.write(s & 0xff);
            out.write((s >> 8) & 0xff);
        }
    }

    /**
     * Ovije surove 16-bit mono PCM podatke v standardni 44-bajtni RIFF/WAV format.
     */
    public static byte[] wrapPcmAsWav(byte[] pcmData, int sampleRate) {
        int dataLen = pcmData.length;
        int totalDataLen = dataLen + 36;
        int byteRate = sampleRate * 2; // 16-bit mono

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // PCM chunk size
        header[20] = 1; header[21] = 0; // AudioFormat = 1 (PCM)
        header[22] = 1; header[23] = 0; // Channels = 1 (Mono)
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0; // BlockAlign = 2
        header[34] = 16; header[35] = 0; // BitsPerSample = 16
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (dataLen & 0xff);
        header[41] = (byte) ((dataLen >> 8) & 0xff);
        header[42] = (byte) ((dataLen >> 16) & 0xff);
        header[43] = (byte) ((dataLen >> 24) & 0xff);

        byte[] wav = new byte[44 + dataLen];
        System.arraycopy(header, 0, wav, 0, 44);
        System.arraycopy(pcmData, 0, wav, 44, dataLen);
        return wav;
    }
}
