package si.ros.RosKasa.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.models.DodatekTp;
import si.ros.RosKasa.models.MizaTp;
import si.ros.RosKasa.ui.CenikListAdapter;

/**
 * Neposreden HTTPS klient za Google Gemini API (privzeto gemini-3.8-flash).
 * Pošilja WAV zvočni segment ali tekst skupaj s kompaktnim cenikom, mizami, dodatki
 * in trenutnim stanjem naročila ter vrača strukturirane akcije (JSON).
 */
public class GeminiVoiceClient {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_CENIK_ITEMS_IN_PROMPT = 450;

    // Deljen OkHttpClient ohranja HTTP/2 + TLS povezavo (prihrani 300-600ms na klic!)
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // Izklop hidden thinking tokenov (prihrani 2-4 sekunde na klic!); ob HTTP 400 se samodejno izklopi
    private static volatile boolean useThinkingBudgetZero = true;

    private final OkHttpClient httpClient;

    public GeminiVoiceClient() {
        this.httpClient = SHARED_HTTP_CLIENT;
    }

    /**
     * Pošlje WAV zvočni posnetek na Gemini API (sinhrono – klicati iz ozadne niti).
     */
    public VoiceOrderModels.AiActionResult sendAudioChunk(
            byte[] wavBytes,
            String activeMiza,
            VoiceOrderModels.VoiceOrderMode mode,
            boolean inTableSelectionScreen,
            List<VoiceOrderModels.CurrentOrderLine> currentLines,
            List<VoiceOrderSession.ChatTurn> history
    ) {
        return executeRequest(wavBytes, null, activeMiza, mode, inTableSelectionScreen, currentLines, history);
    }

    /**
     * Pošlje tekstovno sporočilo na Gemini API (sinhrono – uporabno za teste ali hiter tekstovni ukaz).
     */
    public VoiceOrderModels.AiActionResult sendTextPrompt(
            String userText,
            String activeMiza,
            VoiceOrderModels.VoiceOrderMode mode,
            boolean inTableSelectionScreen,
            List<VoiceOrderModels.CurrentOrderLine> currentLines,
            List<VoiceOrderSession.ChatTurn> history
    ) {
        return executeRequest(null, userText, activeMiza, mode, inTableSelectionScreen, currentLines, history);
    }

    private VoiceOrderModels.AiActionResult executeRequest(
            byte[] wavBytes,
            String userText,
            String activeMiza,
            VoiceOrderModels.VoiceOrderMode mode,
            boolean inTableSelectionScreen,
            List<VoiceOrderModels.CurrentOrderLine> currentLines,
            List<VoiceOrderSession.ChatTurn> history
    ) {
        Globals g = Globals.getInstance();
        String apiKey = g.getAiGeminiApiKey();
        String model = g.getAiGeminiModel();

        if (apiKey == null || apiKey.trim().isEmpty()) {
            VoiceOrderModels.AiActionResult err = new VoiceOrderModels.AiActionResult();
            err.status = "ERROR";
            err.errorMessage = "AI_GEMINI_API_KEY ni nastavljen v MOBINI.";
            return err;
        }

        try {
            JSONObject payload = buildRequestPayload(
                    wavBytes,
                    userText,
                    activeMiza,
                    mode,
                    inTableSelectionScreen,
                    currentLines,
                    history,
                    g.getCachedCenik(),
                    g.getCachedMize(),
                    g.getCachedDodatki(),
                    g.getAiLang(),
                    g.isHodNarocila()
            );

            String url = GEMINI_BASE_URL + model + ":generateContent";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                // Če izbrani model ne podpira thinkingConfig, ga izklopimo in takoj ponovimo klic
                if (response.code() == 400 && useThinkingBudgetZero && body.toLowerCase(Locale.US).contains("thinking")) {
                    useThinkingBudgetZero = false;
                    JSONObject genCfg = payload.optJSONObject("generationConfig");
                    if (genCfg != null) genCfg.remove("thinkingConfig");
                    Request retryReq = new Request.Builder()
                            .url(url)
                            .addHeader("x-goog-api-key", apiKey)
                            .addHeader("Content-Type", "application/json")
                            .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
                            .build();
                    try (Response retryResp = httpClient.newCall(retryReq).execute()) {
                        String retryBody = retryResp.body() != null ? retryResp.body().string() : "";
                        if (!retryResp.isSuccessful()) {
                            VoiceOrderModels.AiActionResult err = new VoiceOrderModels.AiActionResult();
                            err.status = "ERROR";
                            err.errorMessage = "HTTP " + retryResp.code() + ": " + extractApiErrorMessage(retryBody);
                            return err;
                        }
                        return parseGeminiResponseJson(retryBody);
                    }
                }
                if (!response.isSuccessful()) {
                    VoiceOrderModels.AiActionResult err = new VoiceOrderModels.AiActionResult();
                    err.status = "ERROR";
                    err.errorMessage = "HTTP " + response.code() + ": " + extractApiErrorMessage(body);
                    return err;
                }
                return parseGeminiResponseJson(body);
            }
        } catch (IOException e) {
            VoiceOrderModels.AiActionResult err = new VoiceOrderModels.AiActionResult();
            err.status = "ERROR";
            err.errorMessage = e.getMessage() != null ? e.getMessage() : "Omrežna napaka";
            return err;
        } catch (Exception e) {
            VoiceOrderModels.AiActionResult err = new VoiceOrderModels.AiActionResult();
            err.status = "ERROR";
            err.errorMessage = e.getMessage() != null ? e.getMessage() : "Napaka pri obdelavi odgovora";
            return err;
        }
    }

    static JSONObject buildRequestPayload(
            byte[] wavBytes,
            String userText,
            String activeMiza,
            VoiceOrderModels.VoiceOrderMode mode,
            boolean inTableSelectionScreen,
            List<VoiceOrderModels.CurrentOrderLine> currentLines,
            List<VoiceOrderSession.ChatTurn> history,
            List<CenikListAdapter.CenikItem> cenik,
            List<MizaTp> mize,
            List<DodatekTp> dodatki,
            String preferredLang,
            boolean hodEnabled
    ) throws Exception {
        JSONObject root = new JSONObject();

        // System instruction
        String sysPrompt = buildSystemInstruction(
                activeMiza, mode, inTableSelectionScreen, currentLines, cenik, mize, dodatki, preferredLang, hodEnabled
        );
        JSONObject sysObj = new JSONObject();
        JSONArray sysParts = new JSONArray();
        sysParts.put(new JSONObject().put("text", sysPrompt));
        sysObj.put("parts", sysParts);
        root.put("system_instruction", sysObj);

        // Contents (history + current user audio/text)
        JSONArray contents = new JSONArray();
        if (history != null) {
            for (VoiceOrderSession.ChatTurn turn : history) {
                JSONObject c = new JSONObject();
                c.put("role", "model".equals(turn.role) ? "model" : "user");
                JSONArray p = new JSONArray();
                p.put(new JSONObject().put("text", turn.text));
                c.put("parts", p);
                contents.put(c);
            }
        }

        JSONObject userContent = new JSONObject();
        userContent.put("role", "user");
        JSONArray userParts = new JSONArray();
        if (wavBytes != null && wavBytes.length > 0) {
            JSONObject inlineData = new JSONObject();
            inlineData.put("mime_type", "audio/wav");
            inlineData.put("data", Base64.getEncoder().encodeToString(wavBytes));
            userParts.put(new JSONObject().put("inline_data", inlineData));
            userParts.put(new JSONObject().put("text",
                    "Poslušaj govorni posnetek natakarja/gosta in vrni izključno veljaven JSON po predpisani shemi."));
        } else if (userText != null && !userText.trim().isEmpty()) {
            userParts.put(new JSONObject().put("text", userText.trim()));
        }
        userContent.put("parts", userParts);
        contents.put(userContent);
        root.put("contents", contents);

        // Generation config: strict JSON output, minimal latency (thinkingBudget=0)
        JSONObject genConfig = new JSONObject();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 512);
        genConfig.put("response_mime_type", "application/json");
        if (useThinkingBudgetZero) {
            genConfig.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        }
        root.put("generationConfig", genConfig);

        return root;
    }

    static String buildSystemInstruction(
            String activeMiza,
            VoiceOrderModels.VoiceOrderMode mode,
            boolean inTableSelectionScreen,
            List<VoiceOrderModels.CurrentOrderLine> currentLines,
            List<CenikListAdapter.CenikItem> cenik,
            List<MizaTp> mize,
            List<DodatekTp> dodatki,
            String preferredLang,
            boolean hodEnabled
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Si glasovni gostinski POS asistent za aplikacijo RosKasa.\n");
        sb.append("Razumeš slovenščino (sl) in angleščino (en), vključno z gostinskimi okrajšavami in narečji.\n");
        sb.append("Jezik odgovora (reply_text): ");
        if ("en".equalsIgnoreCase(preferredLang)) {
            sb.append("vedno angleščina (en).\n");
        } else if ("sl".equalsIgnoreCase(preferredLang)) {
            sb.append("vedno slovenščina (sl).\n");
        } else {
            sb.append("isti jezik kot govorec (sl ali en).\n");
        }

        sb.append("\nKONTEKST ZASLONA:\n");
        sb.append("- Zaslon: ").append(inTableSelectionScreen ? "MIZE (izbira mize)" : "NAROČILO (aktivna miza)").append("\n");
        sb.append("- Aktivna miza: ").append((activeMiza != null && !activeMiza.isEmpty()) ? activeMiza : "(ni izbrana)").append("\n");
        sb.append("- Način: ").append(mode == VoiceOrderModels.VoiceOrderMode.NEW ? "NEW (novo naročilo)" : "CONTINUE (nadaljuj naročilo)").append("\n");
        sb.append("- Hodi aktivni (HOD): ").append(hodEnabled ? "DA (1=predjed, 2=glavna jed, 3=sladica)" : "NE").append("\n");

        if (currentLines != null && !currentLines.isEmpty()) {
            sb.append("\nTRENUTNE VRSTICE NA NAROČILU (upoštevaj pri pavzah med gosti, 'meni isto', popravkih):\n");
            for (VoiceOrderModels.CurrentOrderLine line : currentLines) {
                sb.append("- nivo4Id=").append(line.nivo4Id)
                        .append(" | ").append(line.naziv)
                        .append(" | kol=").append(line.kolicina);
                if (!line.hod.isEmpty()) sb.append(" | hod=").append(line.hod);
                if (!line.dodatniOpis.isEmpty()) sb.append(" | opomba=").append(line.dodatniOpis);
                sb.append(line.poslano ? " [POSLANO]" : " [NOVO]").append("\n");
            }
        }

        if (mize != null && !mize.isEmpty()) {
            sb.append("\nSEZNAM MIZ: ");
            int count = 0;
            for (MizaTp m : mize) {
                if (count++ > 0) sb.append(", ");
                sb.append(m.getNaziv());
                if (count >= 120) break;
            }
            sb.append("\n");
        }

        if (dodatki != null && !dodatki.isEmpty()) {
            sb.append("\nŠIFRANT DODATKOV/OPOMB: ");
            int count = 0;
            for (DodatekTp d : dodatki) {
                if (count++ > 0) sb.append(", ");
                sb.append(d.getDodatekText());
                if (count >= 80) break;
            }
            sb.append("\n");
        }

        sb.append("\nCENIK (nivo4Id|naziv|cena[|LESTVICA:...]):\n");
        if (cenik != null && !cenik.isEmpty()) {
            boolean cena2Aktivna = Globals.getInstance().isCena2Aktivna();
            int added = 0;
            for (int i = 0; i < cenik.size() && added < MAX_CENIK_ITEMS_IN_PROMPT; i++) {
                CenikListAdapter.CenikItem ci = cenik.get(i);
                if (ci == null || ci.niMoznaIzbira) continue;
                java.math.BigDecimal activeCena = (cena2Aktivna && ci.cena2 != null && ci.cena2.compareTo(java.math.BigDecimal.ZERO) > 0)
                        ? ci.cena2
                        : (ci.cena1 != null && ci.cena1.compareTo(java.math.BigDecimal.ZERO) > 0 ? ci.cena1 : ci.cena);
                // Nikoli ne ponujaj artiklov s ceno <= 0 (tudi če so v ceniku)
                if (activeCena == null || activeCena.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;

                sb.append(ci.nivo4Id).append("|").append(ci.naziv).append("|")
                        .append(activeCena.toPlainString());
                String lestvica = formatLestvicaHint(ci.nacinProdaje, ci.polnjenje);
                if (!lestvica.isEmpty()) {
                    sb.append("|LESTVICA:").append(lestvica);
                }
                sb.append("\n");
                added++;
            }
        }

        sb.append("\nPRAVILA OBVEDENJA:\n");
        sb.append("1. VEDNO uporabi samo točen nivo4Id in naziv iz CENIK-a (vedno s ceno > 0). Nikoli si ne izmisli nivo4Id.\n");
        sb.append("2. Če je artikel najden in NIMA oznake LESTVICA (ali pa je govorec izrecno povedal mero, npr. '0,03', '0,05', '0,2', '1 liter'), ga takoj dodaj v add_items z ustreznim poljem ep (npr. 0.03 ali 0.05; za navadne artikle pusti ep=0).\n");
        sb.append("3. POMEMBNO ZA LESTVIČNE ARTIKLE (označeni z |LESTVICA:... npr. žgane pijače 0.03,0.05,1 ali točenje 0.1..1): če govorec NI povedal točne mere/količine (npr. rekel je samo '1 gin' ali 'en viski'), ga NE dodajaj v add_items! Namesto tega nastavi status='NEEDS_CLARIFICATION', v reply_text vprašaj za količino/polnjenje (npr. 'Katero količino za Gin – 0,03, 0,05 ali 1?'), in v suggestions[] dodaj posamezne možnosti iz LESTVICA z ustreznim poljem ep (0.03, 0.05, 1).\n");
        sb.append("4. Če zahtevanega artikla NI v CENIK-u (npr. 'dunajski zrezek'), poišči 1-3 najbližje alternative iz CENIK-a (npr. 'LJUBLJANSKI ZREZEK'), jih daj v suggestions[], nastavi status='NEEDS_CLARIFICATION' in v reply_text kratko in naravno vprašaj natakarja (npr. 'Dunajskega zrezka nimamo, imamo pa Ljubljanski zrezek – naročim tega?').\n");
        sb.append("5. Opombe na vrstici (npr. 'brez limone in brez ledu', 'no ice', 'medium') zapiši jedrnato v polje opomba.\n");
        sb.append("6. Če govorec želi odpreti ali preklopiti na drugo mizo (npr. 'odpri mizo 12', 'preklopi na mizo 4'), VEDNO nastavi target_miza='Miza 12' (tudi ko smo že na zaslonu NAROČILO!). Če omeni novo naročilo, nastavi mode='new'; sicer mode='continue'.\n");
        sb.append("7. Popravke ali brisanja vrstic ('zbriši kavo', 'namesto pomfrija riž', 'storniraj eno pivo') zapiši v modify_items z natančnim matchNivo4Id in matchNaziv iz TRENUTNE VRSTICE NA NAROČILU (za brisanje nastavi remove=true).\n");
        sb.append("8. Če je na posnetku samo šum/tišina brez naročila, vrni status='NO_ACTION' in prazen reply_text.\n");
        sb.append("9. Vrni IZKLJUČNO JSON objekt naslednje oblike:\n");
        sb.append("{\n");
        sb.append("  \"transcript\": \"kaj je uporabnik rekel\",\n");
        sb.append("  \"lang\": \"sl\",\n");
        sb.append("  \"status\": \"BOOKED\" | \"NEEDS_CLARIFICATION\" | \"NO_ACTION\",\n");
        sb.append("  \"reply_text\": \"kratek naraven odgovor za glasovno branje ali prazen niz\",\n");
        sb.append("  \"target_miza\": \"Miza 5 ali prazen niz\",\n");
        sb.append("  \"mode\": \"new\" | \"continue\" | \"\",\n");
        sb.append("  \"add_items\": [{\"nivo4Id\": 1001, \"naziv\": \"COCA COLA 0.25\", \"kolicina\": 2, \"ep\": 0, \"hod\": \"\", \"opomba\": \"brez limone, brez ledu\", \"gostSt\": 1}],\n");
        sb.append("  \"modify_items\": [{\"matchNivo4Id\": 1001, \"matchNaziv\": \"COCA COLA 0.25\", \"novaKolicina\": 1, \"novNivo4Id\": 0, \"novNaziv\": \"\", \"novaOpomba\": null, \"remove\": false}],\n");
        sb.append("  \"suggestions\": [{\"nivo4Id\": 2050, \"naziv\": \"LJUBLJANSKI ZREZEK\", \"cena\": \"14.50\", \"kolicina\": 1, \"ep\": 0, \"hod\": \"\", \"opomba\": \"\"}]\n");
        sb.append("}\n");

        return sb.toString();
    }

    static String formatLestvicaHint(int nacinProdaje, double polnjenje) {
        switch (nacinProdaje) {
            case 2:
                if (polnjenje > 0 && Math.abs(polnjenje - 0.03) > 1e-6 && Math.abs(polnjenje - 0.05) > 1e-6) {
                    String pStr = (polnjenje == (long) polnjenje) ? String.valueOf((long) polnjenje) : String.valueOf(polnjenje);
                    return "0.03,0.05," + pStr;
                }
                return "0.03,0.05,1";
            case 1:
            case 3:
            case 4:
            case 9:
            case 10:
                return "0.1,0.125,0.2,0.25,0.3,0.5,1";
            default:
                return "";
        }
    }

    /**
     * Parsira surov JSON odgovor iz Gemini REST API (candidates[0].content.parts[0].text).
     */
    public static VoiceOrderModels.AiActionResult parseGeminiResponseJson(String rawResponseBody) {
        VoiceOrderModels.AiActionResult result = new VoiceOrderModels.AiActionResult();
        if (rawResponseBody == null || rawResponseBody.trim().isEmpty()) {
            result.status = "ERROR";
            result.errorMessage = "Prazen odgovor iz Gemini API.";
            return result;
        }

        try {
            JSONObject envelope = new JSONObject(rawResponseBody);
            JSONArray candidates = envelope.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                result.status = "ERROR";
                result.errorMessage = "Gemini ni vrnil odgovora (candidates prazno).";
                return result;
            }

            JSONObject firstCandidate = candidates.getJSONObject(0);
            JSONObject content = firstCandidate.optJSONObject("content");
            JSONArray parts = content != null ? content.optJSONArray("parts") : null;
            if (parts == null || parts.length() == 0) {
                result.status = "NO_ACTION";
                return result;
            }

            String text = parts.getJSONObject(0).optString("text", "").trim();
            text = stripMarkdownJsonFence(text);
            return parseActionJsonObject(new JSONObject(text));
        } catch (Exception e) {
            result.status = "ERROR";
            result.errorMessage = "Neveljaven JSON iz Geminija: " + e.getMessage();
            return result;
        }
    }

    public static VoiceOrderModels.AiActionResult parseActionJsonObject(JSONObject json) {
        VoiceOrderModels.AiActionResult result = new VoiceOrderModels.AiActionResult();
        if (json == null) return result;

        result.transcript = json.optString("transcript", "").trim();
        String lang = json.optString("lang", "sl").trim().toLowerCase(Locale.US);
        result.lang = "en".equals(lang) ? "en" : "sl";
        result.status = json.optString("status", "NO_ACTION").trim().toUpperCase(Locale.US);
        result.replyText = json.optString("reply_text", "").trim();
        result.targetMiza = json.optString("target_miza", "").trim();

        String modeStr = json.optString("mode", "").trim().toLowerCase(Locale.US);
        if ("new".equals(modeStr) || "novo".equals(modeStr)) {
            result.mode = VoiceOrderModels.VoiceOrderMode.NEW;
        } else if ("continue".equals(modeStr) || "nadaljuj".equals(modeStr)) {
            result.mode = VoiceOrderModels.VoiceOrderMode.CONTINUE;
        }

        JSONArray addArr = json.optJSONArray("add_items");
        if (addArr != null) {
            for (int i = 0; i < addArr.length(); i++) {
                JSONObject o = addArr.optJSONObject(i);
                if (o == null) continue;
                int nivo4Id = o.optInt("nivo4Id", 0);
                String naziv = o.optString("naziv", "").trim();
                double kol = o.optDouble("kolicina", 1.0);
                double ep = o.optDouble("ep", 0.0);
                String hod = o.optString("hod", "").trim();
                String opomba = o.optString("opomba", "").trim();
                int gostSt = o.optInt("gostSt", 0);
                if (nivo4Id > 0 || !naziv.isEmpty()) {
                    result.addItems.add(new VoiceOrderModels.AiOrderItem(nivo4Id, naziv, kol, ep, hod, opomba, gostSt));
                }
            }
        }

        JSONArray modArr = json.optJSONArray("modify_items");
        if (modArr != null) {
            for (int i = 0; i < modArr.length(); i++) {
                JSONObject o = modArr.optJSONObject(i);
                if (o == null) continue;
                VoiceOrderModels.AiModifyItem m = new VoiceOrderModels.AiModifyItem();
                m.matchNivo4Id = o.optInt("matchNivo4Id", 0);
                m.matchNaziv = o.optString("matchNaziv", "").trim();
                m.novaKolicina = o.optDouble("novaKolicina", -1.0);
                m.novNivo4Id = o.optInt("novNivo4Id", 0);
                m.novNaziv = o.optString("novNaziv", "").trim();
                m.novaOpomba = o.isNull("novaOpomba") ? null : o.optString("novaOpomba", null);
                m.remove = o.optBoolean("remove", false);
                if (m.matchNivo4Id > 0 || !m.matchNaziv.isEmpty()) {
                    result.modifyItems.add(m);
                }
            }
        }

        JSONArray sugArr = json.optJSONArray("suggestions");
        if (sugArr != null) {
            for (int i = 0; i < sugArr.length(); i++) {
                JSONObject o = sugArr.optJSONObject(i);
                if (o == null) continue;
                int nivo4Id = o.optInt("nivo4Id", 0);
                String naziv = o.optString("naziv", "").trim();
                String cena = o.optString("cena", "").trim();
                double kol = o.optDouble("kolicina", 1.0);
                double ep = o.optDouble("ep", 0.0);
                String hod = o.optString("hod", "").trim();
                String opomba = o.optString("opomba", "").trim();
                if (nivo4Id > 0 && !naziv.isEmpty()) {
                    result.suggestions.add(new VoiceOrderModels.AiSuggestion(nivo4Id, naziv, cena, kol, ep, hod, opomba));
                }
            }
        }

        return result;
    }

    private static String stripMarkdownJsonFence(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private static String extractApiErrorMessage(String body) {
        if (body == null || body.trim().isEmpty()) return "Prazno telo napake";
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject err = obj.optJSONObject("error");
            if (err != null) {
                return err.optString("message", body);
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
