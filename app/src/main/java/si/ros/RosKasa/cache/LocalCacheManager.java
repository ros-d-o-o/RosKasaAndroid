package si.ros.RosKasa.cache;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.models.CenikVrVrTp;
import si.ros.RosKasa.models.DodatekTp;
import si.ros.RosKasa.models.HitraTipkaTp;
import si.ros.RosKasa.models.LojalnostnaTp;
import si.ros.RosKasa.models.MizaTp;
import si.ros.RosKasa.models.MobileSetupTp;
import si.ros.RosKasa.models.NacPlacTp;
import si.ros.RosKasa.models.OsebaTp;
import si.ros.RosKasa.models.PrioritetaProjektaTp;
import si.ros.RosKasa.models.StornoRazlogTp;
import si.ros.RosKasa.models.TarifaTp;
import si.ros.RosKasa.ui.CenikListAdapter;

public class LocalCacheManager {
    private static final String TAG = "LocalCacheManager";
    private static final String SIFRANTI_FILENAME = "sifranti_setup.json";
    private static final String LARGE_SIFRANTI_FILENAME = "large_sifranti_cache.json";

    public static synchronized void saveSifranti(Context context, MobileSetupTp setup) {
        if (context == null || setup == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis());

            // Osebe
            JSONArray arrOsebe = new JSONArray();
            if (setup.getOsebe() != null) {
                for (OsebaTp o : setup.getOsebe()) {
                    JSONObject jo = new JSONObject();
                    jo.put("osebaId", o.getOsebaId());
                    jo.put("naziv", o.getNaziv());
                    jo.put("uporabniskoIme", o.getUporabniskoIme());
                    jo.put("pin", o.getPin());
                    jo.put("privilegiji", o.getPrivilegiji());
                    jo.put("oddelek", o.getOddelek());
                    jo.put("karticaId", o.getKarticaId());
                    jo.put("kartica2Id", o.getKartica2Id());
                    jo.put("pravice", o.getPravice());
                    jo.put("prioriteta", o.getPrioriteta());
                    jo.put("veljavnostPin", o.getVeljavnostPin());
                    if (o.getDatumSpremembePin() != null) {
                        jo.put("datumSpremembePin", o.getDatumSpremembePin().getTime());
                    }
                    arrOsebe.put(jo);
                }
            }
            root.put("osebe", arrOsebe);

            // Prioritete
            JSONArray arrPrio = new JSONArray();
            if (setup.getPrioriteteProjektov() != null) {
                for (PrioritetaProjektaTp p : setup.getPrioriteteProjektov()) {
                    JSONObject jp = new JSONObject();
                    jp.put("caption", p.getCaption());
                    jp.put("pozicijaId", p.getPozicijaId());
                    arrPrio.put(jp);
                }
            }
            root.put("prioritete", arrPrio);

            // Tarife
            JSONArray arrTarife = new JSONArray();
            if (setup.getTarife() != null) {
                for (TarifaTp t : setup.getTarife()) {
                    JSONObject jt = new JSONObject();
                    jt.put("tarifaId", t.getTarifaId());
                    jt.put("naziv", t.getNaziv());
                    jt.put("oznaka", t.getOznaka());
                    if (t.getMetodaId() != null) jt.put("metodaId", t.getMetodaId());
                    if (t.getDavekProc() != null) jt.put("davekProc", t.getDavekProc().toString());
                    arrTarife.put(jt);
                }
            }
            root.put("tarife", arrTarife);

            // Mize
            JSONArray arrMize = new JSONArray();
            if (setup.getMobileSetupMize() != null) {
                for (MizaTp m : setup.getMobileSetupMize()) {
                    JSONObject jm = new JSONObject();
                    jm.put("naziv", m.getNaziv());
                    jm.put("rajon", m.getRajon());
                    jm.put("zap", m.getZap());
                    arrMize.put(jm);
                }
            }
            root.put("mize", arrMize);

            // MobileSetupPlacila (seznam dovoljenih ID-jev placil)
            JSONArray arrPlac = new JSONArray();
            if (setup.getMobileSetupPlacila() != null) {
                for (Integer pId : setup.getMobileSetupPlacila()) {
                    if (pId != null) arrPlac.put(pId);
                }
            }
            root.put("mobileSetupPlacila", arrPlac);

            if (setup.getMobIni0() != null) root.put("mobIni0", setup.getMobIni0());
            if (setup.getMobIni() != null) root.put("mobIni", setup.getMobIni());
            if (setup.getMobIni2() != null) root.put("mobIni2", setup.getMobIni2());

            File file = new File(context.getFilesDir(), SIFRANTI_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "Lokalni šifranti uspešno shranjeni: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri shranjevanju lokalnih šifrantov", e);
        }
    }

    public static synchronized boolean loadSifrantiIfValid(Context context) {
        if (context == null) return false;
        File file = new File(context.getFilesDir(), SIFRANTI_FILENAME);
        if (!file.exists()) return false;

        try {
            int size = (int) file.length();
            byte[] bytes = new byte[size];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);

            long ts = root.optLong("timestamp", 0);
            Globals g = Globals.getInstance();
            int maxDays = g.getCenikStDniObnova() > 0 ? g.getCenikStDniObnova() : 3;
            long ageDays = (System.currentTimeMillis() - ts) / (1000L * 60 * 60 * 24);

            if (g.isCenikLokalno() && ageDays > maxDays) {
                Log.d(TAG, "Lokalni šifranti so potekli (" + ageDays + " dni > " + maxDays + " dni)");
                // Cache je prestar za redno obnovo, vendar ga lahko vseeno uporabimo kot fallback ob odsotnosti povezave
            }

            // Osebe
            List<OsebaTp> osebe = new ArrayList<>();
            JSONArray arrOsebe = root.optJSONArray("osebe");
            if (arrOsebe != null) {
                for (int i = 0; i < arrOsebe.length(); i++) {
                    JSONObject jo = arrOsebe.getJSONObject(i);
                    OsebaTp o = new OsebaTp();
                    o.setOsebaId(jo.optInt("osebaId"));
                    o.setNaziv(jo.optString("naziv"));
                    o.setUporabniskoIme(jo.optString("uporabniskoIme"));
                    o.setPin(jo.optString("pin"));
                    o.setPrivilegiji(jo.optString("privilegiji"));
                    o.setOddelek(jo.optString("oddelek"));
                    o.setKarticaId(jo.optString("karticaId"));
                    o.setKartica2Id(jo.optString("kartica2Id"));
                    o.setPravice(jo.optString("pravice"));
                    o.setPrioriteta(jo.optInt("prioriteta"));
                    o.setVeljavnostPin(jo.optInt("veljavnostPin"));
                    if (jo.has("datumSpremembePin")) {
                        o.setDatumSpremembePin(new Date(jo.optLong("datumSpremembePin")));
                    }
                    osebe.add(o);
                }
            }

            // Prioritete
            List<PrioritetaProjektaTp> prioritete = new ArrayList<>();
            JSONArray arrPrio = root.optJSONArray("prioritete");
            if (arrPrio != null) {
                for (int i = 0; i < arrPrio.length(); i++) {
                    JSONObject jp = arrPrio.getJSONObject(i);
                    prioritete.add(new PrioritetaProjektaTp(jp.optString("caption"), jp.optInt("pozicijaId")));
                }
            }

            // Tarife
            List<TarifaTp> tarife = new ArrayList<>();
            JSONArray arrTarife = root.optJSONArray("tarife");
            if (arrTarife != null) {
                for (int i = 0; i < arrTarife.length(); i++) {
                    JSONObject jt = arrTarife.getJSONObject(i);
                    TarifaTp t = new TarifaTp();
                    t.setTarifaId(jt.optInt("tarifaId"));
                    t.setNaziv(jt.optString("naziv"));
                    t.setOznaka(jt.optString("oznaka"));
                    if (jt.has("metodaId")) t.setMetodaId(jt.optInt("metodaId"));
                    if (jt.has("davekProc")) {
                        t.setDavekProc(new BigDecimal(jt.optString("davekProc")));
                    }
                    tarife.add(t);
                }
            }

            // Mize
            List<MizaTp> mize = new ArrayList<>();
            JSONArray arrMize = root.optJSONArray("mize");
            if (arrMize != null) {
                for (int i = 0; i < arrMize.length(); i++) {
                    JSONObject jm = arrMize.getJSONObject(i);
                    mize.add(new MizaTp(jm.optString("naziv"), jm.optInt("rajon"), jm.optInt("zap")));
                }
            }

            g.setCachedSifranti(osebe, prioritete, tarife, mize);

            // MobileSetupPlacila
            JSONArray arrPlac = root.optJSONArray("mobileSetupPlacila");
            if (arrPlac != null && arrPlac.length() > 0) {
                List<Integer> placila = new ArrayList<>();
                for (int i = 0; i < arrPlac.length(); i++) {
                    placila.add(arrPlac.optInt(i));
                }
                g.getPlacilnaSredstva().clear();
                g.getPlacilnaSredstva().addAll(placila);
            }

            if (root.has("mobIni0")) g.mobIniRead(root.optString("mobIni0"));
            if (root.has("mobIni")) g.mobIniRead(root.optString("mobIni"));
            if (root.has("mobIni2")) g.mobIniRead(root.optString("mobIni2"));

            Log.d(TAG, "Lokalni šifranti naloženi: Osebe=" + osebe.size() + ", Prioritete=" + prioritete.size() + ", Tarife=" + tarife.size() + ", Mize=" + mize.size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri branju lokalnih šifrantov", e);
            return false;
        }
    }

    /**
     * Shrani 6 velikih šifrantov na disk v JSON predpomnilnik (skladno z Delphi CenikLokalno / CenikStDniObnova):
     * Cenik (+ CenikVrVr + CenikVrCene), HitreTipke, NacPlac, Dodatki, StornoRazlogi, Lojalnostna.
     */
    public static synchronized void saveLargeSifranti(Context context) {
        if (context == null) return;
        try {
            Globals g = Globals.getInstance();
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis());

            // 1. Cenik
            JSONArray arrCenik = new JSONArray();
            for (CenikListAdapter.CenikItem ci : g.getCachedCenik()) {
                if (ci == null) continue;
                JSONObject jc = new JSONObject();
                jc.put("nivo4Id", ci.nivo4Id);
                jc.put("naziv", ci.naziv != null ? ci.naziv : "");
                jc.put("plu", ci.plu != null ? ci.plu : "");
                jc.put("cena1", ci.cena1 != null ? ci.cena1.toPlainString() : "0");
                jc.put("cena2", ci.cena2 != null ? ci.cena2.toPlainString() : "0");
                jc.put("nacinProdaje", ci.nacinProdaje);
                jc.put("polnjenje", ci.polnjenje);
                jc.put("em", ci.em);
                jc.put("paket", ci.paket);
                jc.put("nivo1Id", ci.nivo1Id);
                jc.put("tarifaId", ci.tarifaId);
                jc.put("izvorStrmId", ci.izvorStrmId);
                jc.put("davekProc", ci.davekProc);
                jc.put("izpisNarocila", ci.izpisNarocila);
                jc.put("standard", ci.standard != null ? ci.standard : "");
                jc.put("niMoznaIzbira", ci.niMoznaIzbira);
                jc.put("cenikId", ci.cenikId);
                arrCenik.put(jc);
            }
            root.put("cenik", arrCenik);

            // 1b. CenikVrVr
            JSONArray arrVrVr = new JSONArray();
            for (CenikVrVrTp cv : g.getCachedCenikVrVr()) {
                if (cv == null) continue;
                JSONObject jv = new JSONObject();
                jv.put("cenikId", cv.getCenikId());
                jv.put("cenikvrnivo4Id", cv.getCenikvrnivo4Id());
                jv.put("nivo4Id", cv.getNivo4Id());
                jv.put("kolicina", cv.getKolicina());
                jv.put("cena1", cv.getCena1().toPlainString());
                jv.put("cena2", cv.getCena2().toPlainString());
                jv.put("izvorStrmId", cv.getIzvorStrmId());
                jv.put("izvorPrihodekId", cv.getIzvorPrihodekId());
                jv.put("nivo1Id", cv.getNivo1Id());
                jv.put("tarifaId", cv.getTarifaId());
                jv.put("popustDane", cv.getPopustDane());
                arrVrVr.put(jv);
            }
            root.put("cenikVrVr", arrVrVr);

            // 2. HitreTipke
            JSONArray arrHT = new JSONArray();
            for (HitraTipkaTp ht : g.getCachedHitreTipke()) {
                if (ht == null) continue;
                JSONObject jh = new JSONObject();
                jh.put("tipkaId", ht.getTipkaId());
                jh.put("naziv", ht.getNaziv() != null ? ht.getNaziv() : "");
                if (ht.getBarva() != null) jh.put("barva", ht.getBarva());
                if (ht.getSkupinaId() != null) jh.put("skupinaId", ht.getSkupinaId());
                if (ht.getNivo4Id() != null) jh.put("nivo4Id", ht.getNivo4Id());
                if (ht.getEnotaProdaje() != null) jh.put("enotaProdaje", ht.getEnotaProdaje().toPlainString());
                if (ht.getKolicina() != null) jh.put("kolicina", ht.getKolicina());
                if (ht.getDodatekId() != null) jh.put("dodatekId", ht.getDodatekId());
                arrHT.put(jh);
            }
            root.put("hitreTipke", arrHT);

            // 3. NacPlac
            JSONArray arrNP = new JSONArray();
            for (NacPlacTp np : g.getCachedPlacila()) {
                if (np == null) continue;
                JSONObject jn = new JSONObject();
                jn.put("placiloId", np.getPlaciloId());
                jn.put("naziv", np.getNaziv() != null ? np.getNaziv() : "");
                jn.put("metoda", np.getMetoda());
                if (np.getFiskalno() != null) jn.put("fiskalno", np.getFiskalno());
                if (np.getInkaso() != null) jn.put("inkaso", np.getInkaso());
                if (np.getKupecId() != null) jn.put("kupecId", np.getKupecId());
                if (np.getStKopij() != null) jn.put("stKopij", np.getStKopij());
                if (np.getStoritevId() != null) jn.put("storitevId", np.getStoritevId());
                jn.put("vrsta", np.getVrsta());
                if (np.getCrm() != null) jn.put("crm", np.getCrm());
                if (np.getfPlacilo() != null) jn.put("fPlacilo", np.getfPlacilo());
                arrNP.put(jn);
            }
            root.put("nacPlac", arrNP);

            // 4. Dodatki
            JSONArray arrDod = new JSONArray();
            for (DodatekTp d : g.getCachedDodatki()) {
                if (d == null) continue;
                JSONObject jd = new JSONObject();
                jd.put("dodatekId", d.getDodatekId());
                jd.put("dodatekText", d.getDodatekText() != null ? d.getDodatekText() : "");
                arrDod.put(jd);
            }
            root.put("dodatki", arrDod);

            // 5. StornoRazlogi
            JSONArray arrSR = new JSONArray();
            for (StornoRazlogTp sr : g.getCachedStornoRazlogi()) {
                if (sr == null) continue;
                JSONObject js = new JSONObject();
                js.put("stornoRazlogId", sr.getStornoRazlogId());
                js.put("naziv", sr.getNaziv() != null ? sr.getNaziv() : "");
                arrSR.put(js);
            }
            root.put("stornoRazlogi", arrSR);

            // 6. Lojalnostna
            JSONArray arrLoj = new JSONArray();
            for (LojalnostnaTp l : g.getCachedLojalnostna()) {
                if (l == null) continue;
                JSONObject jl = new JSONObject();
                jl.put("bonitetniRazred", l.getBonitetniRazred());
                jl.put("naziv", l.getNaziv() != null ? l.getNaziv() : "");
                arrLoj.put(jl);
            }
            root.put("lojalnostna", arrLoj);

            File file = new File(context.getFilesDir(), LARGE_SIFRANTI_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "Veliki šifranti shranjeni v JSON cache: Cenik=" + arrCenik.length() + ", HT=" + arrHT.length() + ", NP=" + arrNP.length());
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri shranjevanju velikih šifrantov", e);
        }
    }

    /**
     * Naloži 6 velikih šifrantov iz disk JSON predpomnilnika, če je CENIKLOKALNO='D' in starost <= CenikStDniObnova dni.
     */
    public static synchronized boolean loadLargeSifrantiIfValid(Context context) {
        if (context == null) return false;
        Globals g = Globals.getInstance();
        if (!g.isCenikLokalno()) return false;

        File file = new File(context.getFilesDir(), LARGE_SIFRANTI_FILENAME);
        if (!file.exists()) return false;

        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long ts = root.optLong("timestamp", 0L);
            int maxDays = g.getCenikStDniObnova() > 0 ? g.getCenikStDniObnova() : 3;
            long ageDays = (System.currentTimeMillis() - ts) / (1000L * 60 * 60 * 24);
            if (ts <= 0L || ageDays > maxDays) {
                Log.d(TAG, "Veliki šifranti cache potekel (" + ageDays + " dni > " + maxDays + " dni).");
                return false;
            }

            List<CenikListAdapter.CenikItem> cenik = new ArrayList<>();
            JSONArray arrCenik = root.optJSONArray("cenik");
            if (arrCenik != null) {
                for (int i = 0; i < arrCenik.length(); i++) {
                    JSONObject jc = arrCenik.getJSONObject(i);
                    BigDecimal c1 = new BigDecimal(jc.optString("cena1", "0"));
                    CenikListAdapter.CenikItem ci = new CenikListAdapter.CenikItem(
                            jc.optInt("nivo4Id"), jc.optString("naziv"), jc.optString("plu"),
                            c1, jc.optInt("nacinProdaje"), jc.optDouble("polnjenje", 1.0));
                    ci.cena1 = c1;
                    ci.cena2 = new BigDecimal(jc.optString("cena2", "0"));
                    ci.em = jc.optInt("em", 1);
                    ci.paket = jc.optInt("paket", 0);
                    ci.nivo1Id = jc.optInt("nivo1Id", 1);
                    ci.tarifaId = jc.optInt("tarifaId", 1);
                    ci.izvorStrmId = jc.optInt("izvorStrmId", 0);
                    ci.davekProc = jc.optDouble("davekProc", 22.0);
                    ci.izpisNarocila = jc.optInt("izpisNarocila", 0);
                    ci.standard = jc.optString("standard", "");
                    ci.niMoznaIzbira = jc.optBoolean("niMoznaIzbira", false);
                    ci.cenikId = jc.optInt("cenikId", 0);
                    ci.updateCenaForCurrentState();
                    cenik.add(ci);
                }
            }
            if (!cenik.isEmpty()) g.setCachedCenik(cenik);

            List<CenikVrVrTp> vrvr = new ArrayList<>();
            JSONArray arrVrVr = root.optJSONArray("cenikVrVr");
            if (arrVrVr != null) {
                for (int i = 0; i < arrVrVr.length(); i++) {
                    JSONObject jv = arrVrVr.getJSONObject(i);
                    CenikVrVrTp cv = new CenikVrVrTp();
                    cv.setCenikId(jv.optInt("cenikId"));
                    cv.setCenikvrnivo4Id(jv.optInt("cenikvrnivo4Id"));
                    cv.setNivo4Id(jv.optInt("nivo4Id"));
                    cv.setKolicina(jv.optDouble("kolicina", 1.0));
                    cv.setCena1(new BigDecimal(jv.optString("cena1", "0")));
                    cv.setCena2(new BigDecimal(jv.optString("cena2", "0")));
                    cv.setIzvorStrmId(jv.optInt("izvorStrmId"));
                    cv.setIzvorPrihodekId(jv.optInt("izvorPrihodekId"));
                    cv.setNivo1Id(jv.optInt("nivo1Id"));
                    cv.setTarifaId(jv.optInt("tarifaId"));
                    cv.setPopustDane(jv.optInt("popustDane"));
                    vrvr.add(cv);
                }
            }
            if (!vrvr.isEmpty()) g.setCachedCenikVrVr(vrvr);

            List<HitraTipkaTp> htList = new ArrayList<>();
            JSONArray arrHT = root.optJSONArray("hitreTipke");
            if (arrHT != null) {
                for (int i = 0; i < arrHT.length(); i++) {
                    JSONObject jh = arrHT.getJSONObject(i);
                    HitraTipkaTp ht = new HitraTipkaTp();
                    ht.setTipkaId(jh.optInt("tipkaId"));
                    ht.setNaziv(jh.optString("naziv"));
                    if (jh.has("barva")) ht.setBarva(jh.optInt("barva"));
                    if (jh.has("skupinaId")) ht.setSkupinaId(jh.optInt("skupinaId"));
                    if (jh.has("nivo4Id")) ht.setNivo4Id(jh.optInt("nivo4Id"));
                    if (jh.has("enotaProdaje")) ht.setEnotaProdaje(new BigDecimal(jh.optString("enotaProdaje", "1")));
                    if (jh.has("kolicina")) ht.setKolicina(jh.optInt("kolicina"));
                    if (jh.has("dodatekId")) ht.setDodatekId(jh.optInt("dodatekId"));
                    htList.add(ht);
                }
            }
            if (!htList.isEmpty()) g.setCachedHitreTipke(htList);

            List<NacPlacTp> npList = new ArrayList<>();
            JSONArray arrNP = root.optJSONArray("nacPlac");
            if (arrNP != null) {
                for (int i = 0; i < arrNP.length(); i++) {
                    JSONObject jn = arrNP.getJSONObject(i);
                    NacPlacTp np = new NacPlacTp();
                    np.setPlaciloId(jn.optInt("placiloId"));
                    np.setNaziv(jn.optString("naziv"));
                    np.setMetoda(jn.optInt("metoda"));
                    if (jn.has("fiskalno")) np.setFiskalno(jn.optInt("fiskalno"));
                    if (jn.has("inkaso")) np.setInkaso(jn.optInt("inkaso"));
                    if (jn.has("kupecId")) np.setKupecId(jn.optLong("kupecId"));
                    if (jn.has("stKopij")) np.setStKopij(jn.optInt("stKopij"));
                    if (jn.has("storitevId")) np.setStoritevId(jn.optInt("storitevId"));
                    if (jn.has("vrsta")) np.setVrsta(jn.optInt("vrsta"));
                    if (jn.has("crm")) np.setCrm(jn.optInt("crm"));
                    if (jn.has("fPlacilo")) np.setfPlacilo(jn.optString("fPlacilo"));
                    npList.add(np);
                }
            }
            if (!npList.isEmpty()) g.filterAndSetCachedPlacila(npList);

            List<DodatekTp> dodList = new ArrayList<>();
            JSONArray arrDod = root.optJSONArray("dodatki");
            if (arrDod != null) {
                for (int i = 0; i < arrDod.length(); i++) {
                    JSONObject jd = arrDod.getJSONObject(i);
                    dodList.add(new DodatekTp(jd.optInt("dodatekId"), jd.optString("dodatekText")));
                }
            }
            if (!dodList.isEmpty()) g.setCachedDodatki(dodList);

            List<StornoRazlogTp> srList = new ArrayList<>();
            JSONArray arrSR = root.optJSONArray("stornoRazlogi");
            if (arrSR != null) {
                for (int i = 0; i < arrSR.length(); i++) {
                    JSONObject js = arrSR.getJSONObject(i);
                    srList.add(new StornoRazlogTp(js.optInt("stornoRazlogId"), js.optString("naziv")));
                }
            }
            if (!srList.isEmpty()) g.setCachedStornoRazlogi(srList);

            List<LojalnostnaTp> lojList = new ArrayList<>();
            JSONArray arrLoj = root.optJSONArray("lojalnostna");
            if (arrLoj != null) {
                for (int i = 0; i < arrLoj.length(); i++) {
                    JSONObject jl = arrLoj.getJSONObject(i);
                    LojalnostnaTp l = new LojalnostnaTp();
                    l.setBonitetniRazred(jl.optInt("bonitetniRazred"));
                    l.setNaziv(jl.optString("naziv"));
                    lojList.add(l);
                }
            }
            if (!lojList.isEmpty()) g.setCachedLojalnostna(lojList);

            Log.d(TAG, "Veliki šifranti uspešno naloženi iz cache (starost " + ageDays + " dni).");
            return !cenik.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri branju velikih šifrantov iz cache", e);
            return false;
        }
    }

    /**
     * Ročna prisilna razveljavitev lokalnega predpomnilnika (ekvivalent Delphi btnRacprenos).
     */
    public static synchronized void invalidateLargeSifrantiCache(Context context) {
        if (context == null) return;
        File file = new File(context.getFilesDir(), LARGE_SIFRANTI_FILENAME);
        if (file.exists()) {
            file.delete();
        }
        Globals g = Globals.getInstance();
        g.setCachedCenik(new ArrayList<>());
        g.setCachedHitreTipke(new ArrayList<>());
        Log.i(TAG, "Lokalni predpomnilnik velikih šifrantov razveljavljen (ročna osvežitev).");
    }
}
