package si.ros.RosKasa.ai;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.UUID;

/**
 * Glasovni odgovor aplikacije (TextToSpeech) v slovenščini (sl-SI) in angleščini (en-US).
 * Ob začetku in koncu govora obvesti poslušalca (da se mikrofon začasno pavzira).
 */
public class VoiceTtsManager {

    public interface Callback {
        void onSpeakStart();
        void onSpeakDone();
    }

    private TextToSpeech tts;
    private boolean ready = false;
    private final Callback callback;

    public VoiceTtsManager(Context context, Callback callback) {
        this.callback = callback;
        if (context == null) return;
        this.tts = new TextToSpeech(context.getApplicationContext(), status -> {
            ready = (status == TextToSpeech.SUCCESS);
            if (ready && tts != null) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        if (VoiceTtsManager.this.callback != null) {
                            VoiceTtsManager.this.callback.onSpeakStart();
                        }
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (VoiceTtsManager.this.callback != null) {
                            VoiceTtsManager.this.callback.onSpeakDone();
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        if (VoiceTtsManager.this.callback != null) {
                            VoiceTtsManager.this.callback.onSpeakDone();
                        }
                    }
                });
            }
        });
    }

    public void speak(String text, String langCode) {
        if (!ready || tts == null || text == null || text.trim().isEmpty()) {
            return;
        }
        Locale locale = "en".equalsIgnoreCase(langCode) ? Locale.US : new Locale("sl", "SI");
        int res = tts.setLanguage(locale);
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        String utteranceId = "ros_ai_" + UUID.randomUUID();
        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    public void stop() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
        }
        ready = false;
    }
}
