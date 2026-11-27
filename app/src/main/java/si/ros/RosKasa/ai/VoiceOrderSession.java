package si.ros.RosKasa.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Upravlja aktivno glasovno sejo naročanja čez več gostov in pavz
 * ter omogoča gladek prehod iz MizeFragment v NarocilaFragment.
 */
public class VoiceOrderSession {

    public static class ChatTurn {
        public final String role; // "user" | "model"
        public final String text;

        public ChatTurn(String role, String text) {
            this.role = role;
            this.text = text != null ? text : "";
        }
    }

    private static final int MAX_HISTORY_TURNS = 6;
    private static volatile VoiceOrderSession instance;

    private boolean active = false;
    private VoiceOrderModels.VoiceOrderMode mode = VoiceOrderModels.VoiceOrderMode.CONTINUE;
    private String activeMiza = "";
    private String lastDetectedLang = "sl";
    private final List<ChatTurn> history = new ArrayList<>();
    private VoiceOrderModels.AiActionResult pendingResultForNarocila = null;
    private boolean autoStartListeningOnNarocila = false;

    private VoiceOrderSession() {}

    public static VoiceOrderSession getInstance() {
        if (instance == null) {
            synchronized (VoiceOrderSession.class) {
                if (instance == null) {
                    instance = new VoiceOrderSession();
                }
            }
        }
        return instance;
    }

    public synchronized void startNewSession(String miza, VoiceOrderModels.VoiceOrderMode mode) {
        this.active = true;
        this.activeMiza = miza != null ? miza.trim() : "";
        this.mode = mode != null ? mode : VoiceOrderModels.VoiceOrderMode.CONTINUE;
        this.history.clear();
        this.pendingResultForNarocila = null;
        this.autoStartListeningOnNarocila = false;
    }

    public synchronized void ensureActiveForMiza(String miza, VoiceOrderModels.VoiceOrderMode defaultMode) {
        String cleanMiza = miza != null ? miza.trim() : "";
        if (!this.active || (!cleanMiza.isEmpty() && !this.activeMiza.isEmpty() && !cleanMiza.equalsIgnoreCase(this.activeMiza))) {
            startNewSession(cleanMiza, defaultMode);
        } else {
            if (!cleanMiza.isEmpty()) {
                this.activeMiza = cleanMiza;
            }
            if (defaultMode != null) {
                this.mode = defaultMode;
            }
        }
    }

    public synchronized void reset() {
        this.active = false;
        this.activeMiza = "";
        this.mode = VoiceOrderModels.VoiceOrderMode.CONTINUE;
        this.history.clear();
        this.pendingResultForNarocila = null;
        this.autoStartListeningOnNarocila = false;
    }

    public synchronized void addTurn(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        history.add(new ChatTurn(role, text.trim()));
        while (history.size() > MAX_HISTORY_TURNS) {
            history.remove(0);
        }
    }

    public synchronized List<ChatTurn> getHistorySnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
    }

    public synchronized VoiceOrderModels.VoiceOrderMode getMode() {
        return mode;
    }

    public synchronized void setMode(VoiceOrderModels.VoiceOrderMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    public synchronized String getActiveMiza() {
        return activeMiza;
    }

    public synchronized void setActiveMiza(String activeMiza) {
        this.activeMiza = activeMiza != null ? activeMiza.trim() : "";
    }

    public synchronized String getLastDetectedLang() {
        return lastDetectedLang;
    }

    public synchronized void setLastDetectedLang(String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            this.lastDetectedLang = "en";
        } else if ("sl".equalsIgnoreCase(lang)) {
            this.lastDetectedLang = "sl";
        }
    }

    public synchronized void setPendingResultForNarocila(VoiceOrderModels.AiActionResult result, boolean autoListen) {
        this.pendingResultForNarocila = result;
        this.autoStartListeningOnNarocila = autoListen;
    }

    public synchronized VoiceOrderModels.AiActionResult consumePendingResultForNarocila() {
        VoiceOrderModels.AiActionResult res = this.pendingResultForNarocila;
        this.pendingResultForNarocila = null;
        return res;
    }

    public synchronized boolean consumeAutoStartListeningOnNarocila() {
        boolean val = this.autoStartListeningOnNarocila;
        this.autoStartListeningOnNarocila = false;
        return val;
    }
}
