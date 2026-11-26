package si.ros.RosKasa.cache;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.models.KronologijaTp;
import si.ros.RosKasa.soap.RosKasaSoapClient;

/**
 * Trajna lokalna čakalna vrsta za kronologijo (kronologija_pending.json).
 * Vsak zapis se najprej atomarno shrani na disk in se izbriše ŠELE ko strežnik
 * (insertKronologija) potrdi uspešen prenos. Tako se ob izpadu omrežja, napaki
 * pri zagonu ali sesutju aplikacije noben zapis kronologije ne izgubi.
 */
public class KronologijaQueueManager {
    private static final String TAG = "KronologijaQueue";
    private static final String QUEUE_FILENAME = "kronologija_pending.json";
    private static final String LEGACY_LOG_FILENAME = "kronologija.log";
    private static final int MAX_QUEUE_SIZE = 500;
    private static final int BATCH_SIZE = 20;

    private static final Object LOCK = new Object();
    private static final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();

    private static boolean initialized = false;
    private static long nextId = 1L;
    private static final List<PendingEntry> queue = new ArrayList<>();

    private static class PendingEntry {
        long id;
        String datumUra;
        String opis;
        int osebaId;
        int obratId;
        String mobileId;

        PendingEntry(long id, String datumUra, String opis, int osebaId, int obratId, String mobileId) {
            this.id = id;
            this.datumUra = datumUra;
            this.opis = opis;
            this.osebaId = osebaId;
            this.obratId = obratId;
            this.mobileId = mobileId;
        }
    }

    private static void ensureLoadedLocked(Context context) {
        if (initialized || context == null) return;
        initialized = true;
        queue.clear();
        nextId = 1L;

        File dir = context.getFilesDir();
        if (dir == null) return;

        File file = new File(dir, QUEUE_FILENAME);
        if (file.exists() && file.length() > 0) {
            try {
                byte[] bytes = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read = fis.read(bytes);
                    if (read > 0) {
                        String jsonStr = new String(bytes, 0, read, StandardCharsets.UTF_8);
                        JSONArray arr = new JSONArray(jsonStr);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject jo = arr.optJSONObject(i);
                            if (jo == null) continue;
                            long id = jo.optLong("id", nextId);
                            if (id >= nextId) nextId = id + 1L;
                            String datumUra = jo.optString("datumUra", "");
                            String opis = jo.optString("opis", "");
                            int osebaId = jo.optInt("osebaId", 0);
                            int obratId = jo.optInt("obratId", 0);
                            String mobileId = jo.optString("mobileId", "");
                            if (!opis.isEmpty()) {
                                queue.add(new PendingEntry(id, datumUra, opis, osebaId, obratId, mobileId));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Napaka pri branju kronologija_pending.json: " + e.getMessage());
            }
        }

        // Enkratna migracija morebitnih starih zapisov iz kronologija.log
        File legacyFile = new File(dir, LEGACY_LOG_FILENAME);
        if (legacyFile.exists() && legacyFile.length() > 0) {
            boolean migratedAny = false;
            String fallbackDatum = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());
            try (BufferedReader br = new BufferedReader(new FileReader(legacyFile))) {
                List<String> legacyLines = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        legacyLines.add(trimmed);
                    }
                }
                int start = Math.max(0, legacyLines.size() - 50);
                for (int i = start; i < legacyLines.size(); i++) {
                    queue.add(new PendingEntry(nextId++, fallbackDatum, legacyLines.get(i), 0, 0, ""));
                    migratedAny = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Napaka pri migraciji kronologija.log: " + e.getMessage());
            }
            if (migratedAny) {
                trimQueueLocked();
                saveLocked(context);
            }
            //noinspection ResultOfMethodCallIgnored
            legacyFile.delete();
        }
    }

    private static void trimQueueLocked() {
        while (queue.size() > MAX_QUEUE_SIZE) {
            queue.remove(0);
        }
    }

    private static void saveLocked(Context context) {
        if (context == null) return;
        File dir = context.getFilesDir();
        if (dir == null) return;

        File target = new File(dir, QUEUE_FILENAME);
        File tmp = new File(dir, QUEUE_FILENAME + ".tmp");
        try {
            JSONArray arr = new JSONArray();
            for (PendingEntry e : queue) {
                JSONObject jo = new JSONObject();
                jo.put("id", e.id);
                jo.put("datumUra", e.datumUra != null ? e.datumUra : "");
                jo.put("opis", e.opis != null ? e.opis : "");
                jo.put("osebaId", e.osebaId);
                jo.put("obratId", e.obratId);
                jo.put("mobileId", e.mobileId != null ? e.mobileId : "");
                arr.put(jo);
            }
            byte[] data = arr.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Ni bilo mogoče zbrisati stare datoteke kronologija_pending.json pred preimenovanjem.");
            }
            if (!tmp.renameTo(target)) {
                // Fallback: neposreden zapis, če renameTo na napravi ne uspe
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri shranjevanju kronologija_pending.json: " + e.getMessage());
        }
    }

    /**
     * Doda zapis kronologije v trajno lokalno vrsto na disku.
     */
    public static void enqueue(Context context, String datumUra, String opisOperacije, int osebaId, int obratId, String mobileId) {
        if (context == null || opisOperacije == null || opisOperacije.trim().isEmpty()) return;
        String finalOpis = opisOperacije.length() > 950 ? opisOperacije.substring(0, 950) : opisOperacije;
        String finalDatum = (datumUra != null && !datumUra.trim().isEmpty())
                ? datumUra.trim()
                : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());

        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            queue.add(new PendingEntry(nextId++, finalDatum, finalOpis, osebaId, obratId, mobileId != null ? mobileId : ""));
            trimQueueLocked();
            saveLocked(context.getApplicationContext());
        }
    }

    public static int getPendingCount(Context context) {
        if (context == null) return 0;
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            return queue.size();
        }
    }

    /**
     * Asinhrono in zaporedno pošlje vse čakajoče zapise kronologije na strežnik.
     * Zapisi ostanejo shranjeni na disku, dokler strežnik (insertKronologija) ne potrdi uspeha.
     */
    public static void flushAsync(Context context, String serverUrl, String token) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        Globals g = Globals.getInstance();
        if (g.isKronologIzklop() || !g.isLogirajAktivnost()) return;

        final String sUrl = (serverUrl != null && !serverUrl.trim().isEmpty()) ? serverUrl.trim() : g.getServerUrl();
        final String tok = (token != null && !token.trim().isEmpty()) ? token.trim() : g.getToken();

        if (sUrl == null || sUrl.isEmpty() || tok == null || tok.isEmpty()) {
            return; // Še nismo registrirani / povezani — zapisi varno čakajo na disku
        }
        if (!Globals.isNetworkAvailable(appCtx)) {
            return; // Brez omrežja — zapisi varno čakajo na disku
        }

        flushExecutor.execute(() -> flushBlocking(appCtx, sUrl, tok));
    }

    private static void flushBlocking(Context appCtx, String serverUrl, String token) {
        Globals g = Globals.getInstance();
        if (g.isKronologIzklop() || !g.isLogirajAktivnost()) return;
        if (serverUrl == null || serverUrl.isEmpty() || token == null || token.isEmpty()) return;
        if (!Globals.isNetworkAvailable(appCtx)) return;

        while (true) {
            List<PendingEntry> batchSnapshot;
            synchronized (LOCK) {
                ensureLoadedLocked(appCtx);
                if (queue.isEmpty()) {
                    return;
                }
                int count = Math.min(BATCH_SIZE, queue.size());
                batchSnapshot = new ArrayList<>(queue.subList(0, count));
            }

            List<KronologijaTp> soapItems = new ArrayList<>(batchSnapshot.size());
            int fallbackOsebaId = g.getTekocaOsebaId();
            int fallbackObratId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0)
                    ? g.getTocilnicaId()
                    : g.getHisObrat();

            for (PendingEntry entry : batchSnapshot) {
                int osId = entry.osebaId > 0 ? entry.osebaId : fallbackOsebaId;
                int obId = entry.obratId > 0 ? entry.obratId : fallbackObratId;
                KronologijaTp kt = new KronologijaTp(entry.opis, osId, obId);
                if (entry.datumUra != null && !entry.datumUra.isEmpty()) {
                    kt.setDatumUra(entry.datumUra);
                }
                soapItems.add(kt);
            }

            try {
                RosKasaSoapClient.vpisKronologijeBatch(serverUrl, token, soapItems);
                // Strežnik je potrdil prenos -> šele zdaj odstranimo potrjene zapise z diska
                Set<Long> confirmedIds = new HashSet<>(batchSnapshot.size());
                for (PendingEntry e : batchSnapshot) {
                    confirmedIds.add(e.id);
                }
                synchronized (LOCK) {
                    Iterator<PendingEntry> it = queue.iterator();
                    while (it.hasNext()) {
                        if (confirmedIds.contains(it.next().id)) {
                            it.remove();
                        }
                    }
                    saveLocked(appCtx);
                }
                Log.d(TAG, "Uspešno prenesenih " + batchSnapshot.size() + " zapisov kronologije na strežnik.");
            } catch (Exception e) {
                // Prenos ni uspel -> zapisi OSTANEJO v kronologija_pending.json za naslednji poskus!
                Log.w(TAG, "Prenos kronologije začasno ni uspel (" + batchSnapshot.size() + " zapisov ostaja na disku): " + e.getMessage());
                return;
            }
        }
    }
}
