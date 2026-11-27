package si.ros.RosKasa.ai;

import org.junit.Before;
import org.junit.Test;

import si.ros.RosKasa.Globals;

import static org.junit.Assert.*;

public class GeminiVoiceOrderTest {

    @Before
    public void setUp() {
        VoiceOrderSession.getInstance().reset();
    }

    @Test
    public void testMobIniJsonArrayAndMasking() {
        Globals g = Globals.getInstance();
        String jsonArray = "["
                + "{\"naziv\":\"AI_ENABLED\",\"vrednost\":\"D\"},"
                + "{\"naziv\":\"AI_GEMINI_API_KEY\",\"vrednost\":\"AIzaSyTestSecretKey9876\"},"
                + "{\"naziv\":\"AI_GEMINI_MODEL\",\"vrednost\":\"gemini-3.8-flash\"},"
                + "{\"naziv\":\"AI_LIVE_AUDIO_MODE\",\"vrednost\":\"vad\"},"
                + "{\"naziv\":\"AI_VAD_SILENCE_MS\",\"vrednost\":\"1300\"},"
                + "{\"naziv\":\"AI_LANG\",\"vrednost\":\"auto\"},"
                + "{\"naziv\":\"AI_TTS_ENABLED\",\"vrednost\":\"D\"}"
                + "]";

        g.mobIniRead(jsonArray);

        assertTrue(g.isAiEnabled());
        assertEquals("AIzaSyTestSecretKey9876", g.getAiGeminiApiKey());
        assertEquals("AIza••••9876", g.getMaskedAiGeminiApiKey());
        assertEquals("gemini-3.8-flash", g.getAiGeminiModel());
        assertEquals("vad", g.getAiLiveAudioMode());
        assertEquals(1300, g.getAiVadSilenceMs());
        assertEquals("auto", g.getAiLang());
        assertTrue(g.isAiTtsEnabled());
    }

    @Test
    public void testMobIniFlatJsonObject() {
        Globals g = Globals.getInstance();
        String flatJson = "{"
                + "\"AI_ENABLED\":\"N\","
                + "\"AI_LIVE_AUDIO_MODE\":\"push\","
                + "\"AI_LANG\":\"en\","
                + "\"AI_TTS_ENABLED\":\"N\""
                + "}";

        g.mobIniRead(flatJson);

        assertFalse(g.isAiEnabled());
        assertEquals("push", g.getAiLiveAudioMode());
        assertEquals("en", g.getAiLang());
        assertFalse(g.isAiTtsEnabled());
    }

    @Test
    public void testParsePrimer1WithAlternativeSuggestion() {
        String innerJson = "{"
                + "\"transcript\":\"miza 5 naroča 1 kavo, 1 coca colo, 1 dunajski zrezek\","
                + "\"lang\":\"sl\","
                + "\"status\":\"NEEDS_CLARIFICATION\","
                + "\"reply_text\":\"Dunajskega zrezka nimamo, imamo pa Ljubljanski zrezek – naročim tega?\","
                + "\"target_miza\":\"Miza 5\","
                + "\"mode\":\"new\","
                + "\"add_items\":["
                + "  {\"nivo4Id\":1002,\"naziv\":\"KAVA ESPRESSO\",\"kolicina\":1,\"ep\":1.0,\"hod\":\"\",\"opomba\":\"\",\"gostSt\":1},"
                + "  {\"nivo4Id\":1001,\"naziv\":\"COCA COLA 0.25\",\"kolicina\":1,\"ep\":1.0,\"hod\":\"\",\"opomba\":\"\",\"gostSt\":1}"
                + "],"
                + "\"modify_items\":[],"
                + "\"suggestions\":["
                + "  {\"nivo4Id\":2050,\"naziv\":\"LJUBLJANSKI ZREZEK\",\"cena\":\"14.50\",\"kolicina\":1,\"hod\":\"2\",\"opomba\":\"\"}"
                + "]"
                + "}";

        String envelope = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + org.json.JSONObject.quote(innerJson)
                + "}]}}]}";

        VoiceOrderModels.AiActionResult res = GeminiVoiceClient.parseGeminiResponseJson(envelope);

        assertEquals("sl", res.lang);
        assertEquals("NEEDS_CLARIFICATION", res.status);
        assertEquals("Miza 5", res.targetMiza);
        assertEquals(VoiceOrderModels.VoiceOrderMode.NEW, res.mode);
        assertEquals(2, res.addItems.size());
        assertEquals(1002, res.addItems.get(0).nivo4Id);
        assertEquals(1, res.suggestions.size());
        assertEquals("LJUBLJANSKI ZREZEK", res.suggestions.get(0).naziv);
        assertTrue(res.replyText.contains("Ljubljanski zrezek"));
    }

    @Test
    public void testParsePrimer2LineNotesAndContinueMode() {
        String innerJson = "{"
                + "\"transcript\":\"miza 4 naroča 2 coca coli brez limone in brez ledu\","
                + "\"lang\":\"sl\","
                + "\"status\":\"BOOKED\","
                + "\"reply_text\":\"\","
                + "\"target_miza\":\"Miza 4\","
                + "\"mode\":\"continue\","
                + "\"add_items\":["
                + "  {\"nivo4Id\":1001,\"naziv\":\"COCA COLA 0.25\",\"kolicina\":2,\"ep\":1.0,\"hod\":\"\",\"opomba\":\"brez limone, brez ledu\",\"gostSt\":2}"
                + "],"
                + "\"modify_items\":[],"
                + "\"suggestions\":[]"
                + "}";

        String envelope = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + org.json.JSONObject.quote(innerJson)
                + "}]}}]}";

        VoiceOrderModels.AiActionResult res = GeminiVoiceClient.parseGeminiResponseJson(envelope);

        assertEquals("BOOKED", res.status);
        assertEquals(VoiceOrderModels.VoiceOrderMode.CONTINUE, res.mode);
        assertEquals(1, res.addItems.size());
        assertEquals(2.0, res.addItems.get(0).kolicina, 0.001);
        assertEquals("brez limone, brez ledu", res.addItems.get(0).opomba);
    }

    @Test
    public void testVoiceOrderSessionHandoff() {
        VoiceOrderSession session = VoiceOrderSession.getInstance();
        session.startNewSession("Miza 5", VoiceOrderModels.VoiceOrderMode.NEW);
        VoiceOrderModels.AiActionResult pending = new VoiceOrderModels.AiActionResult();
        pending.targetMiza = "Miza 5";
        session.setPendingResultForNarocila(pending, true);

        VoiceOrderModels.AiActionResult consumed = session.consumePendingResultForNarocila();
        assertNotNull(consumed);
        assertEquals("Miza 5", consumed.targetMiza);
        assertNull(session.consumePendingResultForNarocila());
        assertTrue(session.consumeAutoStartListeningOnNarocila());
        assertFalse(session.consumeAutoStartListeningOnNarocila());
    }

    @Test
    public void testWavHeaderWrapping() {
        byte[] pcm = new byte[320];
        byte[] wav = AudioCaptureVadManager.wrapPcmAsWav(pcm, 16000);
        assertEquals(44 + 320, wav.length);
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
    }

    @Test
    public void testSystemInstructionExcludesZeroPriceAndMarksLestvica() {
        java.util.List<si.ros.RosKasa.ui.CenikListAdapter.CenikItem> cenik = new java.util.ArrayList<>();
        cenik.add(new si.ros.RosKasa.ui.CenikListAdapter.CenikItem(100, "COCA COLA GRATIS", "00100", java.math.BigDecimal.ZERO, 6, 1.0));
        cenik.add(new si.ros.RosKasa.ui.CenikListAdapter.CenikItem(101, "COCA COLA 0.25", "00101", new java.math.BigDecimal("2.90"), 6, 1.0));
        cenik.add(new si.ros.RosKasa.ui.CenikListAdapter.CenikItem(201, "GIN BEEFEATER", "00201", new java.math.BigDecimal("4.50"), 2, 0.03));

        String prompt = GeminiVoiceClient.buildSystemInstruction(
                "Miza 5",
                VoiceOrderModels.VoiceOrderMode.CONTINUE,
                false,
                java.util.Collections.emptyList(),
                cenik,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                "sl",
                false
        );

        assertFalse("Zero-price item must be excluded from AI prompt", prompt.contains("COCA COLA GRATIS"));
        assertTrue(prompt.contains("101|COCA COLA 0.25|2.90"));
        assertTrue(prompt.contains("201|GIN BEEFEATER|4.50|LESTVICA:0.03,0.05,1"));
    }

    @Test
    public void testParseGinLestvicaSuggestionsWithEp() {
        String innerJson = "{"
                + "\"transcript\":\"en gin\","
                + "\"lang\":\"sl\","
                + "\"status\":\"NEEDS_CLARIFICATION\","
                + "\"reply_text\":\"Katero količino za Gin – 0,03, 0,05 ali 1?\","
                + "\"target_miza\":\"\","
                + "\"mode\":\"continue\","
                + "\"add_items\":[],"
                + "\"modify_items\":[],"
                + "\"suggestions\":["
                + "  {\"nivo4Id\":201,\"naziv\":\"GIN BEEFEATER\",\"cena\":\"4.50\",\"kolicina\":1,\"ep\":0.03,\"hod\":\"\",\"opomba\":\"\"},"
                + "  {\"nivo4Id\":201,\"naziv\":\"GIN BEEFEATER\",\"cena\":\"7.50\",\"kolicina\":1,\"ep\":0.05,\"hod\":\"\",\"opomba\":\"\"}"
                + "]"
                + "}";

        String envelope = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + org.json.JSONObject.quote(innerJson)
                + "}]}}]}";

        VoiceOrderModels.AiActionResult res = GeminiVoiceClient.parseGeminiResponseJson(envelope);
        assertEquals("NEEDS_CLARIFICATION", res.status);
        assertEquals(0, res.addItems.size());
        assertEquals(2, res.suggestions.size());
        assertEquals(0.03, res.suggestions.get(0).ep, 0.0001);
        assertEquals(0.05, res.suggestions.get(1).ep, 0.0001);
    }

    @Test
    public void testStornoPoslanegaNarocilaPrivilegePin9999VsPin1203Milan() {
        Globals g = Globals.getInstance();
        si.ros.RosKasa.models.MobileSetupTp setup = new si.ros.RosKasa.models.MobileSetupTp();

        si.ros.RosKasa.models.PrioritetaProjektaTp prio = new si.ros.RosKasa.models.PrioritetaProjektaTp();
        prio.setPozicijaId(4);
        prio.setCaption(si.ros.RosKasa.models.PraviceConsts.SStorniraPoslanoNarocilo);
        setup.getPrioriteteProjektov().add(prio);

        si.ros.RosKasa.models.OsebaTp o9999 = new si.ros.RosKasa.models.OsebaTp();
        o9999.setOsebaId(9999);
        o9999.setPin("9999");
        o9999.setNaziv("Admin 9999");
        o9999.setPravice("1111"); // bit 4 = '1'
        setup.getOsebe().add(o9999);

        si.ros.RosKasa.models.OsebaTp oMilan = new si.ros.RosKasa.models.OsebaTp();
        oMilan.setOsebaId(101);
        oMilan.setPin("1203");
        oMilan.setNaziv("Milan");
        oMilan.setPravice("1110"); // bit 4 = '0'
        setup.getOsebe().add(oMilan);

        setup.setMobIni("NISTORNONAROCILA=D\nOVERRIDESTORNOPOSLAN=D");
        g.loadFromMobileSetup(setup, 1);

        si.ros.RosKasa.models.OsebaTp found9999 = g.najdiOseboZaPin("9999");
        si.ros.RosKasa.models.OsebaTp foundMilan = g.najdiOseboZaPin("1203");
        assertNotNull(found9999);
        assertNotNull(foundMilan);
        assertEquals(9999, found9999.getOsebaId());
        assertEquals("Milan", foundMilan.getNaziv());

        // 1) NISTORNONAROCILA=D + OVERRIDESTORNOPOSLAN=N (realna produkcijska nastavitev po Delphi FormKasaMobile.pas:4686-4691):
        //    PIN 9999 (oseba_id=9999) IMA pravico -> DOVOLJENO; PIN 1203 (Milan) NIMA pravice -> ZAVRNJENO!
        g.mobIniRead("NISTORNONAROCILA=D\nOVERRIDESTORNOPOSLAN=N");
        g.setTekocaOseba(found9999);
        assertTrue("PIN 9999 (oseba_id=9999) mora imeti pravico brisanja poslanega artikla tudi pri NISTORNONAROCILA=D",
                g.isStornoPoslanegaNarocilaDovoljen());
        g.setTekocaOseba(foundMilan);
        assertFalse("PIN 1203 (Milan) NE sme imeti pravice brisanja poslanega artikla",
                g.isStornoPoslanegaNarocilaDovoljen());

        // 2) NISTORNONAROCILA=N + OVERRIDESTORNOPOSLAN=N: PIN 9999 sme, Milan (PIN 1203) NE sme!
        g.mobIniRead("NISTORNONAROCILA=N\nOVERRIDESTORNOPOSLAN=N");
        g.setTekocaOseba(found9999);
        assertTrue(g.isStornoPoslanegaNarocilaDovoljen());
        g.setTekocaOseba(foundMilan);
        assertFalse(g.isStornoPoslanegaNarocilaDovoljen());

        // 3) OVERRIDESTORNOPOSLAN=D: eksplicitni override dovoli vsem (skladno z Delphi)
        g.mobIniRead("NISTORNONAROCILA=D\nOVERRIDESTORNOPOSLAN=D");
        g.setTekocaOseba(found9999);
        assertTrue(g.isStornoPoslanegaNarocilaDovoljen());
        g.setTekocaOseba(foundMilan);
        assertTrue(g.isStornoPoslanegaNarocilaDovoljen());
    }
}
