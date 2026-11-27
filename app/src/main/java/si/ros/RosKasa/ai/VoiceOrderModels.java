package si.ros.RosKasa.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Podatkovni modeli za Gemini AI glasovno naročanje v RosKasa.
 */
public final class VoiceOrderModels {

    private VoiceOrderModels() {}

    public enum VoiceOrderMode {
        NEW,
        CONTINUE
    }

    /**
     * Posnetek obstoječe vrstice iz aktivnega naročila (NarocilaFragment / currentRacun),
     * ki se pošlje Geminiju kot kontekst (vključno z ročnimi popravki natakarja).
     */
    public static class CurrentOrderLine {
        public final int pozicijaId;
        public final int nivo4Id;
        public final String naziv;
        public final double kolicina;
        public final String hod;
        public final String dodatniOpis;
        public final boolean poslano;

        public CurrentOrderLine(int pozicijaId, int nivo4Id, String naziv, double kolicina,
                                String hod, String dodatniOpis, boolean poslano) {
            this.pozicijaId = pozicijaId;
            this.nivo4Id = nivo4Id;
            this.naziv = naziv != null ? naziv : "";
            this.kolicina = kolicina;
            this.hod = hod != null ? hod : "";
            this.dodatniOpis = dodatniOpis != null ? dodatniOpis : "";
            this.poslano = poslano;
        }
    }

    /**
     * Nova postavka, ki jo Gemini predlaga za takojšnje knjiženje v naročilo.
     */
    public static class AiOrderItem {
        public int nivo4Id;
        public String naziv = "";
        public double kolicina = 1.0;
        public double ep = 0.0; // 0.0 = ni eksplicitno določeno (za lestvične artikle zahteva izbiro)
        public String hod = "";
        public String opomba = "";
        public int gostSt = 0;

        public AiOrderItem() {}

        public AiOrderItem(int nivo4Id, String naziv, double kolicina, double ep,
                           String hod, String opomba, int gostSt) {
            this.nivo4Id = nivo4Id;
            this.naziv = naziv != null ? naziv : "";
            this.kolicina = kolicina > 0 ? kolicina : 1.0;
            this.ep = ep > 0 ? ep : 0.0;
            this.hod = hod != null ? hod.trim() : "";
            this.opomba = opomba != null ? opomba.trim() : "";
            this.gostSt = gostSt;
        }
    }

    /**
     * Popravek ali odstranitev neposlane postavke v aktivnem naročilu.
     */
    public static class AiModifyItem {
        public int matchNivo4Id;
        public String matchNaziv = "";
        public double novaKolicina = -1.0; // <= 0 pomeni ne spreminjaj (razen če remove=true)
        public int novNivo4Id = 0;
        public String novNaziv = "";
        public String novaOpomba = null; // null pomeni ne spreminjaj
        public boolean remove = false;
    }

    /**
     * Alternativni predlog artikla iz cenika (npr. namesto dunajskega zrezka -> Ljubljanski zrezek).
     */
    public static class AiSuggestion {
        public int nivo4Id;
        public String naziv = "";
        public String cena = "";
        public double kolicina = 1.0;
        public double ep = 0.0;
        public String hod = "";
        public String opomba = "";

        public AiSuggestion() {}

        public AiSuggestion(int nivo4Id, String naziv, String cena, double kolicina, double ep, String hod, String opomba) {
            this.nivo4Id = nivo4Id;
            this.naziv = naziv != null ? naziv : "";
            this.cena = cena != null ? cena : "";
            this.kolicina = kolicina > 0 ? kolicina : 1.0;
            this.ep = ep > 0 ? ep : 0.0;
            this.hod = hod != null ? hod.trim() : "";
            this.opomba = opomba != null ? opomba.trim() : "";
        }
    }

    /**
     * Strukturiran rezultat ene obdelave govora preko Gemini API.
     */
    public static class AiActionResult {
        public String transcript = "";
        public String lang = "sl"; // "sl" | "en"
        public String status = "NO_ACTION"; // BOOKED | NEEDS_CLARIFICATION | NO_ACTION | ERROR
        public String replyText = "";
        public String targetMiza = "";
        public VoiceOrderMode mode = null; // NEW | CONTINUE | null (nespremenjeno)
        public final List<AiOrderItem> addItems = new ArrayList<>();
        public final List<AiModifyItem> modifyItems = new ArrayList<>();
        public final List<AiSuggestion> suggestions = new ArrayList<>();
        public String errorMessage = "";

        public boolean hasActions() {
            return !addItems.isEmpty() || !modifyItems.isEmpty();
        }
    }
}
