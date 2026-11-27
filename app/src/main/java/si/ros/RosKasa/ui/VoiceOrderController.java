package si.ros.RosKasa.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.R;
import si.ros.RosKasa.ai.AudioCaptureVadManager;
import si.ros.RosKasa.ai.GeminiVoiceClient;
import si.ros.RosKasa.ai.VoiceOrderModels;
import si.ros.RosKasa.ai.VoiceOrderSession;
import si.ros.RosKasa.ai.VoiceTtsManager;

/**
 * Kontroler kompaktne glasovne vrstice za MizeFragment in NarocilaFragment.
 * Povezuje AudioRecord+VAD, GeminiVoiceClient (gemini-3.8-flash), TTS (sl/en)
 * in sprotno knjiženje neposredno v aktivno naročilo brez samodejnega shranjevanja.
 */
public class VoiceOrderController {

    public interface Host {
        boolean isTableSelectionScreen();
        String getCurrentMiza();
        List<VoiceOrderModels.CurrentOrderLine> getCurrentOrderLines();
        void onAiSelectTable(String mizaName, VoiceOrderModels.VoiceOrderMode mode, VoiceOrderModels.AiActionResult initialResult);
        int onAiAddItems(List<VoiceOrderModels.AiOrderItem> items);
        int onAiModifyItems(List<VoiceOrderModels.AiModifyItem> items);
        void onRequestRecordAudioPermission();
    }

    private final Context context;
    private final View rootView;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private GeminiVoiceClient geminiClient;

    private final TextView tvStatus;
    private final TextView tvTranscript;
    private final TextView tvReply;
    private final MaterialButton btnMode;
    private final MaterialButton btnPauseResume;
    private final MaterialButton btnClose;
    private final HorizontalScrollView scrollSuggestions;
    private final LinearLayout llSuggestions;

    private AudioCaptureVadManager audioManager;
    private VoiceTtsManager ttsManager;
    private boolean audioAndTtsInitialized = false;
    private boolean userManuallyPaused = false;
    private boolean destroyed = false;
    private final java.util.concurrent.atomic.AtomicLong requestGeneration = new java.util.concurrent.atomic.AtomicLong(0L);

    public VoiceOrderController(Context context, View barRoot, Host host) {
        this.context = context;
        this.rootView = barRoot;
        this.host = host;

        this.tvStatus = barRoot.findViewById(R.id.tvAiStatus);
        this.tvTranscript = barRoot.findViewById(R.id.tvAiTranscript);
        this.tvReply = barRoot.findViewById(R.id.tvAiReply);
        this.btnMode = barRoot.findViewById(R.id.btnAiMode);
        this.btnPauseResume = barRoot.findViewById(R.id.btnAiPauseResume);
        this.btnClose = barRoot.findViewById(R.id.btnAiClose);
        this.scrollSuggestions = barRoot.findViewById(R.id.scrollAiSuggestions);
        this.llSuggestions = barRoot.findViewById(R.id.llAiSuggestions);

        // Lazy init: ne ustvarjaj TextToSpeech / OkHttpClient / Executor ob navadnem preklopu Mize <-> Narocila!
        bindControls();
    }

    private void ensureInitialized() {
        if (audioAndTtsInitialized || destroyed) return;
        audioAndTtsInitialized = true;
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }
        if (geminiClient == null) {
            geminiClient = new GeminiVoiceClient();
        }
        initAudioAndTts();
    }

    private void initAudioAndTts() {
        this.ttsManager = new VoiceTtsManager(context, new VoiceTtsManager.Callback() {
            @Override
            public void onSpeakStart() {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (audioManager != null) audioManager.pause();
                    tvStatus.setText(R.string.ai_status_speaking);
                    tvStatus.setTextColor(Color.parseColor("#FFD54F"));
                });
            }

            @Override
            public void onSpeakDone() {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (!userManuallyPaused && audioManager != null && rootView.getVisibility() == View.VISIBLE) {
                        audioManager.resume();
                    }
                    updateStatusUi();
                });
            }
        });

        this.audioManager = new AudioCaptureVadManager(new AudioCaptureVadManager.Listener() {
            @Override
            public void onSpeechChunkReady(byte[] wavBytes) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    tvStatus.setText(R.string.ai_status_processing);
                    tvStatus.setTextColor(Color.parseColor("#81C784"));
                });
                processAudioChunkAsync(wavBytes);
            }

            @Override
            public void onAudioLevel(float normalizedLevel) {
                // Lahko uporabimo za vizualni indikator glasnosti
            }

            @Override
            public void onListeningStateChanged(boolean listening, boolean paused) {
                mainHandler.post(() -> {
                    if (!destroyed) updateStatusUi();
                });
            }

            @Override
            public void onError(String message) {
                Globals.getInstance().vpisiKronologijo("Napaka AI_VOICE mikrofon: " + message);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    tvReply.setVisibility(View.VISIBLE);
                    tvReply.setText(message);
                    updateStatusUi();
                });
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindControls() {
        btnMode.setOnClickListener(v -> {
            ensureInitialized();

        VoiceOrderSession session = VoiceOrderSession.getInstance();
            VoiceOrderModels.VoiceOrderMode next =
                    (session.getMode() == VoiceOrderModels.VoiceOrderMode.NEW)
                            ? VoiceOrderModels.VoiceOrderMode.CONTINUE
                            : VoiceOrderModels.VoiceOrderMode.NEW;
            session.setMode(next);
            updateModeButtonUi();
        });

        btnClose.setOnClickListener(v -> close());

        configurePauseOrPushButton();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configurePauseOrPushButton() {
        boolean isPush = "push".equalsIgnoreCase(Globals.getInstance().getAiLiveAudioMode());
        if (audioManager != null) {
            audioManager.setPushMode(isPush);
            audioManager.setVadSilenceMs(Globals.getInstance().getAiVadSilenceMs());
        }

        if (isPush) {
            btnPauseResume.setText(R.string.ai_btn_hold_talk);
            btnPauseResume.setOnClickListener(null);
            btnPauseResume.setOnTouchListener((v, event) -> {
                if (audioManager == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (ttsManager != null) ttsManager.stop();
                    audioManager.beginPushToTalk();
                    tvStatus.setText(R.string.ai_status_listening);
                    tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    audioManager.endPushToTalk();
                    return true;
                }
                return false;
            });
        } else {
            btnPauseResume.setOnTouchListener(null);
            btnPauseResume.setOnClickListener(v -> {
                if (audioManager == null) return;
                if (audioManager.isPaused() || !audioManager.isRunning()) {
                    userManuallyPaused = false;
                    if (ttsManager != null) ttsManager.stop();
                    audioManager.resume();
                } else {
                    userManuallyPaused = true;
                    audioManager.pause();
                }
                updateStatusUi();
            });
        }
    }

    public boolean isOpen() {
        return rootView.getVisibility() == View.VISIBLE;
    }

    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            openAndStart();
        }
    }

    public void openAndStart() {
        Globals g = Globals.getInstance();
        if (g.getAiGeminiApiKey().isEmpty()) {
            g.vpisiKronologijo("Napaka AI_VOICE: AI_GEMINI_API_KEY ni nastavljen v MOBINI");
            Toast.makeText(context, R.string.ai_err_no_api_key, Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            host.onRequestRecordAudioPermission();
            return;
        }

        ensureInitialized();

        VoiceOrderSession session = VoiceOrderSession.getInstance();
        VoiceOrderModels.VoiceOrderMode defaultMode = host.isTableSelectionScreen()
                ? VoiceOrderModels.VoiceOrderMode.NEW
                : VoiceOrderModels.VoiceOrderMode.CONTINUE;
        session.ensureActiveForMiza(host.getCurrentMiza(), defaultMode);

        configurePauseOrPushButton();
        updateModeButtonUi();
        rootView.setVisibility(View.VISIBLE);
        userManuallyPaused = false;
        if (audioManager != null) {
            audioManager.start();
        }
        updateStatusUi();
    }

    public void onPermissionGranted() {
        openAndStart();
    }

    public void close() {
        requestGeneration.incrementAndGet();
        userManuallyPaused = false;
        if (audioManager != null) audioManager.stop();
        if (ttsManager != null) ttsManager.stop();
        rootView.setVisibility(View.GONE);
        clearSuggestions();
    }

    /**
     * Uporabi predhodni rezultat iz MizeFragment po prehodu v NarocilaFragment.
     */
    public void applyHandedOffResult(VoiceOrderModels.AiActionResult result, boolean autoListen) {
        if (result == null) return;
        ensureInitialized();
        rootView.setVisibility(View.VISIBLE);
        configurePauseOrPushButton();
        updateModeButtonUi();
        handleActionResultOnUi(result);
        if (autoListen && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            userManuallyPaused = false;
            if (audioManager != null) audioManager.start();
            updateStatusUi();
        }
    }

    private void processAudioChunkAsync(byte[] wavBytes) {
        ensureInitialized();
        final long gen = requestGeneration.get();

        VoiceOrderSession session = VoiceOrderSession.getInstance();
        String currentMiza = host.getCurrentMiza();
        VoiceOrderModels.VoiceOrderMode mode = session.getMode();
        boolean inTables = host.isTableSelectionScreen();
        List<VoiceOrderModels.CurrentOrderLine> currentLines = host.getCurrentOrderLines();
        List<VoiceOrderSession.ChatTurn> history = session.getHistorySnapshot();

        executor.execute(() -> {
            VoiceOrderModels.AiActionResult result = geminiClient.sendAudioChunk(
                    wavBytes, currentMiza, mode, inTables, currentLines, history
            );
            mainHandler.post(() -> {
                if (destroyed || gen != requestGeneration.get()) return;
                handleActionResultOnUi(result);
            });
        });
    }

    private void handleActionResultOnUi(VoiceOrderModels.AiActionResult result) {
        if (result == null) {
            updateStatusUi();
            return;
        }

        if ("ERROR".equalsIgnoreCase(result.status)) {
            Globals.getInstance().vpisiKronologijo("Napaka AI_VOICE Gemini: " + result.errorMessage);
            tvReply.setVisibility(View.VISIBLE);
            tvReply.setText(context.getString(R.string.ai_err_network, result.errorMessage));
            updateStatusUi();
            return;
        }

        if ("NO_ACTION".equalsIgnoreCase(result.status) && result.replyText.isEmpty() && !result.hasActions()) {
            updateStatusUi();
            return;
        }

        ensureInitialized();

        VoiceOrderSession session = VoiceOrderSession.getInstance();
        session.setLastDetectedLang(result.lang);
        if (result.mode != null) {
            session.setMode(result.mode);
            updateModeButtonUi();
        }

        if (!result.transcript.isEmpty()) {
            tvTranscript.setVisibility(View.VISIBLE);
            tvTranscript.setText("»" + result.transcript + "«");
            session.addTurn("user", result.transcript);
        }

        // Če je Gemini prepoznal drugo mizo (iz MizeFragment ALI preklop mize iz NarocilaFragment)
        if (!result.targetMiza.isEmpty()
                && (host.isTableSelectionScreen() || !isSameTable(result.targetMiza, host.getCurrentMiza()))) {
            VoiceOrderModels.VoiceOrderMode targetMode = result.mode != null ? result.mode : session.getMode();
            if (!result.replyText.isEmpty()) {
                session.addTurn("model", result.replyText);
            }
            if (host.isTableSelectionScreen() && audioManager != null) {
                audioManager.stop();
            }
            host.onAiSelectTable(result.targetMiza, targetMode, result);
            return;
        }

        // Sproti knjižimo v aktivno naročilo (brez samodejnega shranjevanja!)
        int added = 0;
        int modified = 0;
        if (!result.addItems.isEmpty()) {
            added = host.onAiAddItems(result.addItems);
        }
        if (!result.modifyItems.isEmpty()) {
            modified = host.onAiModifyItems(result.modifyItems);
        }

        if (added > 0 || modified > 0) {
            Globals.getInstance().vpisiKronologijo(
                    buildVoiceKronologijaOpis(host.getCurrentMiza(), result.addItems, result.modifyItems, added, modified)
            );
        }

        // Če je Gemini poskušal izbrisati/popraviti artikel, pa nobena vrstica ni bila spremenjena,
        // uporabniku pošteno javimo napako namesto optimističnega "opravljeno".
        if (!result.modifyItems.isEmpty() && modified == 0 && added == 0) {
            String failMsg = context.getString(R.string.ai_modify_failed);
            tvReply.setVisibility(View.VISIBLE);
            tvReply.setText(failMsg);
            session.addTurn("model", failMsg);
            if (Globals.getInstance().isAiTtsEnabled() && ttsManager != null) {
                ttsManager.speak(failMsg, result.lang);
            }
            clearSuggestions();
            updateStatusUi();
            return;
        }

        // Prikaži pogovorni odgovor asistenta + TTS
        if (!result.replyText.isEmpty()) {
            tvReply.setVisibility(View.VISIBLE);
            tvReply.setText(result.replyText);
            session.addTurn("model", result.replyText);
            if (Globals.getInstance().isAiTtsEnabled() && ttsManager != null) {
                ttsManager.speak(result.replyText, result.lang);
            }
        } else if (added > 0) {
            tvReply.setVisibility(View.VISIBLE);
            tvReply.setText(context.getString(R.string.ai_booked_summary, added));
        }

        renderSuggestions(result.suggestions);
        updateStatusUi();
    }

    public void showAssistantMessage(String message, String lang) {
        if (destroyed || message == null || message.trim().isEmpty()) return;
        tvReply.setVisibility(View.VISIBLE);
        tvReply.setText(message);
        if (Globals.getInstance().isAiTtsEnabled() && ttsManager != null) {
            ttsManager.speak(message, lang != null ? lang : VoiceOrderSession.getInstance().getLastDetectedLang());
        }
    }

    static boolean isSameTable(String targetMiza, String currentMiza) {
        if (targetMiza == null || currentMiza == null) return false;
        String t = targetMiza.trim();
        String c = currentMiza.trim();
        if (t.isEmpty() || c.isEmpty()) return false;
        if (t.equalsIgnoreCase(c)) return true;
        String td = t.replaceAll("[^0-9]", "");
        String cd = c.replaceAll("[^0-9]", "");
        return !td.isEmpty() && td.equals(cd);
    }

    private void renderSuggestions(List<VoiceOrderModels.AiSuggestion> suggestions) {
        clearSuggestions();
        if (suggestions == null || suggestions.isEmpty()) return;

        scrollSuggestions.setVisibility(View.VISIBLE);
        for (VoiceOrderModels.AiSuggestion sug : suggestions) {
            MaterialButton btn = new MaterialButton(context);
            String epLabel = "";
            if (sug.ep > 0) {
                String epStr = (sug.ep == (long) sug.ep)
                        ? String.valueOf((long) sug.ep)
                        : String.valueOf(sug.ep).replace('.', ',');
                epLabel = " " + epStr;
            }
            final String finalEpLabel = epLabel;
            String label = "+ " + sug.naziv + finalEpLabel + (sug.cena.isEmpty() ? "" : " (" + sug.cena + " €)");
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(12f);
            btn.setTextColor(Color.WHITE);
            btn.setBackgroundColor(Color.parseColor("#2E7D32"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd(8);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                // Tap na predlog = takojšnje lokalno knjiženje!
                // 1) Preklic morebitnega tekočega AI zahtevka in trenutnega avdio medpomnilnika,
                //    da AI čez sekundo NE poknjiži istega artikla še enkrat.
                requestGeneration.incrementAndGet();
                if (ttsManager != null) ttsManager.stop();
                if (audioManager != null) audioManager.discardCurrentBuffer();

                VoiceOrderModels.AiOrderItem item = new VoiceOrderModels.AiOrderItem(
                        sug.nivo4Id, sug.naziv, sug.kolicina, sug.ep, sug.hod, sug.opomba, 0
                );
                int booked = host.onAiAddItems(Collections.singletonList(item));
                if (booked > 0) {
                    Globals.getInstance().vpisiKronologijo(
                            buildVoiceKronologijaOpis(host.getCurrentMiza(), Collections.singletonList(item), null, booked, 0)
                                    .replaceFirst("^AI_VOICE ", "AI_VOICE_TAP ")
                    );
                    VoiceOrderSession sess = VoiceOrderSession.getInstance();
                    sess.addTurn("user", "Izbral sem z gumbom: " + sug.naziv + finalEpLabel);
                    sess.addTurn("model", "V redu, poknjižil sem " + sug.naziv + finalEpLabel
                            + ". Prejšnje vprašanje je zaključeno; če gost kasneje izrecno naroči nov kos istega artikla, ga normalno poknjiži.");
                    tvReply.setVisibility(View.VISIBLE);
                    tvReply.setText("✓ Poknjiženo: " + sug.naziv + finalEpLabel);
                    clearSuggestions();
                    updateStatusUi();
                }
            });
            llSuggestions.addView(btn);
        }
    }

    private void clearSuggestions() {
        llSuggestions.removeAllViews();
        scrollSuggestions.setVisibility(View.GONE);
    }

    static String buildVoiceKronologijaOpis(
            String miza,
            List<VoiceOrderModels.AiOrderItem> addItems,
            List<VoiceOrderModels.AiModifyItem> modItems,
            int added,
            int modified
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI_VOICE miza=").append(miza != null ? miza : "");
        if (added > 0 && addItems != null && !addItems.isEmpty()) {
            sb.append(" +[");
            boolean first = true;
            for (VoiceOrderModels.AiOrderItem it : addItems) {
                if (it == null) continue;
                if (!first) sb.append("; ");
                first = false;
                sb.append(it.nivo4Id).append(" ").append(it.naziv)
                        .append(" x").append(formatCompactNumber(it.kolicina));
                if (it.ep > 0) sb.append(" ep=").append(formatCompactNumber(it.ep));
                if (it.hod != null && !it.hod.isEmpty()) sb.append(" hod=").append(it.hod);
                if (it.opomba != null && !it.opomba.isEmpty()) sb.append(" (").append(it.opomba).append(")");
            }
            sb.append("]");
        }
        if (modified > 0 && modItems != null && !modItems.isEmpty()) {
            sb.append(" ~[");
            boolean first = true;
            for (VoiceOrderModels.AiModifyItem m : modItems) {
                if (m == null) continue;
                if (!first) sb.append("; ");
                first = false;
                sb.append(m.remove ? "DEL " : "MOD ")
                        .append(m.matchNivo4Id).append(" ").append(m.matchNaziv);
                if (!m.remove && m.novaKolicina > 0) sb.append(" kol=").append(formatCompactNumber(m.novaKolicina));
                if (m.novNivo4Id > 0 || (m.novNaziv != null && !m.novNaziv.isEmpty())) {
                    sb.append(" -> ").append(m.novNivo4Id).append(" ").append(m.novNaziv);
                }
                if (m.novaOpomba != null && !m.novaOpomba.isEmpty()) sb.append(" (").append(m.novaOpomba).append(")");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private static String formatCompactNumber(double val) {
        if (val == (long) val) return String.valueOf((long) val);
        return String.valueOf(val);
    }

    private void updateModeButtonUi() {
        VoiceOrderModels.VoiceOrderMode mode = VoiceOrderSession.getInstance().getMode();
        btnMode.setText(mode == VoiceOrderModels.VoiceOrderMode.NEW
                ? R.string.ai_mode_new
                : R.string.ai_mode_continue);
    }

    private void updateStatusUi() {
        boolean isPush = "push".equalsIgnoreCase(Globals.getInstance().getAiLiveAudioMode());
        if (isPush) {
            tvStatus.setText(R.string.ai_status_push_hint);
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            return;
        }
        if (audioManager != null && audioManager.isRunning() && !audioManager.isPaused()) {
            tvStatus.setText(R.string.ai_status_listening);
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnPauseResume.setText(R.string.ai_btn_pause);
        } else {
            tvStatus.setText(R.string.ai_status_paused);
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            btnPauseResume.setText(R.string.ai_btn_resume);
        }
    }

    public void destroy() {
        destroyed = true;
        if (audioManager != null) {
            audioManager.stop();
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
