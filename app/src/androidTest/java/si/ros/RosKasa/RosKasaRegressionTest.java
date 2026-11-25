package si.ros.RosKasa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated Android regression test suite (TC01-TC10) for RosKasa (Java Android).
 * Uses direct Instrumentation main-thread View interaction (immune to Espresso InputManager
 * reflection issues on Android 16/17) and mirrors test_dataset.json.
 * Results on Miza 58/59/60 are verified by tests/regression/sql/02_snapshot_and_compare.sql.
 */
@RunWith(AndroidJUnit4.class)
public class RosKasaRegressionTest {
    private static final String TAG = "RosKasaRegression";

    private static final String TEST_URL = "https://web.ros.si/ora/r16f/asmx/kasa.asmx";
    private static final String TEST_MOBILE_ID = "6";
    private static final String TEST_TOKEN = "lpdZEc84S0ahF-jZz0RaLg";
    private static final String TEST_PIN = "9999";

    private static final String MIZA_PRIMARY = "Miza 58";
    private static final String MIZA_SECONDARY = "Miza 59";

    private static final String ART_LASKO = "PIVO LAŠKO 0,33";
    private static final String ART_GUINNES = "PIVO GUINNES 0,33";
    private static final String ART_PAKET = "PAKET KREMNA";
    private static final String ART_LESTVICA = "BELO GORI";

    private static final String PARTNER_ROS = "ROS D.O.O.";
    private static final String PARTNER_PRVA = "PRVA OSEBNA ZAVAROVALNICA";
    private static final String HOTEL_ROOM = "205";
    private static final String HOTEL_GUEST = "EVA";

    private final Instrumentation instr = InstrumentationRegistry.getInstrumentation();

    @Before
    public void setUpCredentials() {
        Context ctx = instr.getTargetContext();
        String pkg = ctx.getPackageName();
        try {
            instr.getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_CONNECT);
            instr.getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_SCAN);
        } catch (Throwable ignored) {}
        AppPreferences prefs = new AppPreferences(ctx);
        prefs.saveRegistration(TEST_URL, TEST_MOBILE_ID, TEST_TOKEN, "Offline2");
        prefs.setPrinterRacuni("");
    }

    @Test
    public void runFullRegressionSuite() throws Exception {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            loginWithPin(TEST_PIN);

            int tc01RacunId = 0;
            int tc02RacunId = 0;

            try { tc01RacunId = runSimpleCash(MIZA_PRIMARY, ART_LASKO); report.add("TC01 PASS racunId=" + tc01RacunId); }
            catch (Throwable t) { report.add("TC01 FAIL: " + t.getMessage()); failures.add("TC01: " + t.getMessage()); recoverToMize(); }

            try { tc02RacunId = runPaketKredRocno(MIZA_PRIMARY); report.add("TC02 PASS racunId=" + tc02RacunId); }
            catch (Throwable t) { report.add("TC02 FAIL: " + t.getMessage()); failures.add("TC02: " + t.getMessage()); recoverToMize(); }

            try { int r = runLestvicaHotelKredit(MIZA_PRIMARY); report.add("TC03 PASS racunId=" + r); }
            catch (Throwable t) { report.add("TC03 FAIL: " + t.getMessage()); failures.add("TC03: " + t.getMessage()); recoverToMize(); }

            try { int r = runDobavnica(MIZA_PRIMARY, ART_GUINNES, PARTNER_ROS); report.add("TC04 PASS racunId=" + r); }
            catch (Throwable t) { report.add("TC04 FAIL: " + t.getMessage()); failures.add("TC04: " + t.getMessage()); recoverToMize(); }

            try { int r = runDobavnica(MIZA_PRIMARY, ART_LASKO, PARTNER_PRVA); report.add("TC05 PASS racunId=" + r); }
            catch (Throwable t) { report.add("TC05 FAIL: " + t.getMessage()); failures.add("TC05: " + t.getMessage()); recoverToMize(); }

            try { int r = runPopust99(MIZA_PRIMARY); report.add("TC06 PASS racunId=" + r); }
            catch (Throwable t) { report.add("TC06 FAIL: " + t.getMessage()); failures.add("TC06: " + t.getMessage()); recoverToMize(); }

            try { int r = runPopustLine(MIZA_PRIMARY); report.add("TC07 PASS racunId=" + r); }
            catch (Throwable t) { report.add("TC07 FAIL: " + t.getMessage()); failures.add("TC07: " + t.getMessage()); recoverToMize(); }

            try { runZdruziAndSplit(); report.add("TC08 PASS"); }
            catch (Throwable t) { report.add("TC08 FAIL: " + t.getMessage()); failures.add("TC08: " + t.getMessage()); recoverToMize(); }

            if (tc01RacunId > 0) {
                try { runStorno(tc01RacunId, true); report.add("TC09 PASS source=" + tc01RacunId); }
                catch (Throwable t) { report.add("TC09 FAIL: " + t.getMessage()); failures.add("TC09: " + t.getMessage()); recoverToMize(); }
            }

            if (tc02RacunId > 0) {
                try { runStorno(tc02RacunId, false); report.add("TC10 PASS source=" + tc02RacunId); }
                catch (Throwable t) { report.add("TC10 FAIL: " + t.getMessage()); failures.add("TC10: " + t.getMessage()); recoverToMize(); }
            }

            try { runObracunaniStatus4Search(61779); report.add("TC11 PASS obracunani_ref=61779"); }
            catch (Throwable t) { report.add("TC11 FAIL: " + t.getMessage()); failures.add("TC11: " + t.getMessage()); recoverToMize(); }
        }
        String summary = String.join("\n", report);
        Log.i(TAG, "=== SUMMARY ===\n" + summary);
        if (!failures.isEmpty()) {
            throw new AssertionError("RosKasaRegressionTest failures:\n" + summary);
        }
    }

    private void recoverToMize() {
        try {
            for (int id : new int[]{R.id.btnSobeCancel, R.id.btnPartnerCancel, R.id.btnVnosCeneCancel, R.id.btnPopustCancel, R.id.btnCancelEdit, R.id.btnStornoPreklici, R.id.btnCancel}) {
                if (findVisibleView(id) != null) { clickView(id); Thread.sleep(300); }
            }
            dismissAnyAlertOk();
            goToMize();
        } catch (Throwable ignored) {}
    }

    private void loginWithPin(String pin) throws Exception {
        waitForView(R.id.etPinCode, 15000);
        // Wait for startup getAppConfig to finish before clicking Login
        long cfgDeadline = System.currentTimeMillis() + 15000;
        while ((Globals.getInstance().getCasGetAppConfig() == null || Globals.getInstance().getCasGetAppConfig().isEmpty())
                && System.currentTimeMillis() < cfgDeadline) {
            Thread.sleep(250);
        }
        Thread.sleep(500);
        setText(R.id.etPinCode, pin);
        clickView(R.id.btnLoginPin);
        waitForView(R.id.rvMizeGrid, 20000);
        Thread.sleep(1500);
    }

    private void goToMize() throws Exception {
        dismissAnyAlertOk();
        if (findVisibleView(R.id.rvMizeGrid) != null) return;
        if (findVisibleView(R.id.btnNavMize) != null) {
            clickView(R.id.btnNavMize);
        }
        waitForView(R.id.rvMizeGrid, 15000);
        Thread.sleep(800);
    }

    private void openTable(String marker) throws Exception {
        goToMize();
        waitForRecyclerAndClick(R.id.rvMizeGrid, marker, 15000);
        waitForView(R.id.btnNarociPost, 15000);
        Thread.sleep(500);
    }

    private void addArticleFromCenik(String searchText, int qty, boolean isLestvica) throws Exception {
        if (findVisibleView(R.id.etSearchCenik) == null) {
            clickView(R.id.btnCenikTipke);
            waitForView(R.id.etSearchCenik, 5000);
        }
        setText(R.id.etSearchCenik, searchText);
        Thread.sleep(600);
        for (int i = 0; i < qty; i++) {
            waitForRecyclerIndexAndClick(R.id.rvCenikList, 0, 8000);
            Thread.sleep(500);
            if (isLestvica) {
                waitForView(R.id.btnEp4, 5000);
                clickView(R.id.btnEp4);
                Thread.sleep(500);
            }
        }
    }

    private int postOrderAndGetRacunId(String marker) throws Exception {
        clickView(R.id.btnNarociPost);
        Thread.sleep(2500);
        if (findVisibleView(R.id.rvMizeGrid) != null) {
            openTable(marker);
        }
        int rid = getCurrentRacunId();
        long deadline = System.currentTimeMillis() + 8000;
        while (rid <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(400);
            rid = getCurrentRacunId();
        }
        return rid;
    }

    private int getCurrentRacunId() {
        return Globals.getInstance().getCurrentRacun() != null
                ? Globals.getInstance().getCurrentRacun().getRacunId() : 0;
    }

    private void openPlacila() throws Exception {
        clickView(R.id.btnNavPlacila);
        waitForView(R.id.btnIzpisRacuna, 10000);
        Thread.sleep(500);
    }

    private void confirmVnosCeneIfShown() throws Exception {
        Thread.sleep(700);
        if (findVisibleView(R.id.btnVnosCeneOk) != null) {
            clickView(R.id.btnVnosCeneOk);
            Thread.sleep(1500);
        }
    }

    private void printReceipt() throws Exception {
        Thread.sleep(1000);
        if (findVisibleView(R.id.btnIzpisRacuna) != null) {
            clickView(R.id.btnIzpisRacuna);
        }
        Thread.sleep(3500);
        dismissAnyAlertOk();
    }

    private void clickPaymentByName(final String paymentName) throws Exception {
        final AtomicBoolean clicked = new AtomicBoolean(false);
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View grid = roots.get(i).findViewById(R.id.gridPlacilaMethods);
                if (grid != null && grid.isShown()) {
                    View btn = findButtonByText(grid, paymentName);
                    if (btn != null && btn.isShown()) {
                        btn.performClick();
                        clicked.set(true);
                        return;
                    }
                }
            }
        });
        if (!clicked.get()) {
            throw new AssertionError("Payment button not found in gridPlacilaMethods: " + paymentName);
        }
    }

    private int runSimpleCash(String marker, String article) throws Exception {
        openTable(marker);
        addArticleFromCenik(article, 1, false);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickPaymentByName("GOTOVINA");
        confirmVnosCeneIfShown();
        printReceipt();
        return rid;
    }

    private int runPaketKredRocno(String marker) throws Exception {
        openTable(marker);
        addArticleFromCenik(ART_PAKET, 1, false);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickPaymentByName("KRED. K ROCNO");
        confirmVnosCeneIfShown();
        printReceipt();
        return rid;
    }

    private int runLestvicaHotelKredit(String marker) throws Exception {
        openTable(marker);
        addArticleFromCenik(ART_LESTVICA, 1, true);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickPaymentByName("GOST HOTELA");
        confirmVnosCeneIfShown();
        waitForView(R.id.etSearchSobe, 10000);
        setText(R.id.etSearchSobe, HOTEL_ROOM);
        clickView(R.id.btnSearchSobe);
        waitForRecyclerAndClick(R.id.rvSobe, HOTEL_GUEST, 12000);
        clickView(R.id.btnSobePotrdi);
        Thread.sleep(1500);
        printReceipt();
        return rid;
    }

    private int runDobavnica(String marker, String article, String partnerSearch) throws Exception {
        openTable(marker);
        addArticleFromCenik(article, 1, false);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickPaymentByName("DOBAVNICA");
        confirmVnosCeneIfShown();
        waitForView(R.id.etPartnerNaziv, 10000);
        setText(R.id.etPartnerNaziv, partnerSearch);
        clickView(R.id.btnSearchPartner);
        waitForRecyclerIndexAndClick(R.id.rvPartnerji, 0, 12000);
        clickView(R.id.btnPartnerPotrdi);
        Thread.sleep(1500);
        printReceipt();
        return rid;
    }

    private int runPopust99(String marker) throws Exception {
        openTable(marker);
        addArticleFromCenik(ART_LASKO, 2, false);
        addArticleFromCenik(ART_GUINNES, 1, false);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickView(R.id.btnPopust);
        waitForView(R.id.btnQuick10, 8000);
        clickView(R.id.btnQuick10);
        clickView(R.id.btnPopustOk);
        Thread.sleep(1500);
        clickPaymentByName("GOTOVINA");
        confirmVnosCeneIfShown();
        printReceipt();
        return rid;
    }

    private int runPopustLine(String marker) throws Exception {
        openTable(marker);
        addArticleFromCenik(ART_LASKO, 1, false);
        // Double-tap (< 600ms) on rvNarociloItems[0] opens RacpozicEditDialog
        doubleClickRecyclerIndex(R.id.rvNarociloItems, 0, 8000);
        waitForView(R.id.etPopustProcent, 8000);
        setText(R.id.etPopustProcent, "10");
        clickView(R.id.btnOkEdit);
        Thread.sleep(800);
        addArticleFromCenik(ART_GUINNES, 1, false);
        int rid = postOrderAndGetRacunId(marker);
        openPlacila();
        clickPaymentByName("GOTOVINA");
        confirmVnosCeneIfShown();
        printReceipt();
        return rid;
    }

    private void runZdruziAndSplit() throws Exception {
        openTable(MIZA_PRIMARY);
        addArticleFromCenik(ART_PAKET, 1, false);
        addArticleFromCenik(ART_LASKO, 4, false);
        int ridA = postOrderAndGetRacunId(MIZA_PRIMARY);

        openTable(MIZA_SECONDARY);
        addArticleFromCenik(ART_GUINNES, 1, false);
        int ridB = postOrderAndGetRacunId(MIZA_SECONDARY);

        goToMize();
        clickView(R.id.btnNavRacuni);
        waitForRecyclerAndClick(R.id.rvRacuni, String.valueOf(ridA), 12000);
        clickView(R.id.btnZdruzi);
        waitForRecyclerAndClick(R.id.rvOdprtiRacuni, String.valueOf(ridB), 10000);
        clickView(R.id.btnPotrdiZdruzi);
        Thread.sleep(2500);
        dismissAnyAlertOk();

        Thread.sleep(1500);
        waitForRecyclerAndClick(R.id.rvRacuni, String.valueOf(ridA), 12000);
        clickView(R.id.btnDeli);
        waitForRecyclerAndClick(R.id.rvOriginalPozicije, "PIVO LA", 10000);
        Thread.sleep(300);
        waitForRecyclerAndClick(R.id.rvOriginalPozicije, "PIVO LA", 5000);
        clickView(R.id.btnPotrdiSplit);
        Thread.sleep(3000);
        dismissAnyAlertOk();
    }

    private void runStorno(int targetRacunId, boolean createNew) throws Exception {
        goToMize();
        clickView(R.id.btnNavRacuni);
        waitForView(R.id.btnToggleStatus, 10000);
        clickView(R.id.btnToggleStatus);
        Thread.sleep(2500);
        waitForRecyclerAndClick(R.id.rvRacuni, String.valueOf(targetRacunId), 12000);
        clickView(R.id.btnPrikaz);
        waitForView(R.id.btnPreviewStorno, 10000);
        Thread.sleep(2000); // wait for getRacun inside preview dialog to finish
        clickView(R.id.btnPreviewStorno);
        Thread.sleep(800);
        dismissAnyAlertOk(); // confirms "Da" on "Storno računa #... Ali želite nadaljevati?"
        waitForRecyclerIndexAndClick(R.id.rvStornoRazlogi, 0, 12000);
        setChecked(R.id.cbNovRacun, createNew);
        clickView(R.id.btnStornoPotrdi);
        Thread.sleep(3500);
        dismissAnyAlertOk();
    }

    // --- Direct Main-Thread Window/View Helpers (works on Android 8..17+) ---

    @SuppressWarnings("unchecked")
    private List<View> getAllRootViews() {
        List<View> roots = new ArrayList<>();
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmgClass.getMethod("getInstance");
            Object wmg = getInstance.invoke(null);
            Field mViewsField = wmgClass.getDeclaredField("mViews");
            mViewsField.setAccessible(true);
            Object viewsObj = mViewsField.get(wmg);
            if (viewsObj instanceof List) {
                roots.addAll((List<View>) viewsObj);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getAllRootViews fallback: " + t.getMessage());
        }
        return roots;
    }

    private View findVisibleView(final int viewId) {
        final AtomicReference<View> found = new AtomicReference<>();
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            // Search topmost window first (dialogs sit above MainActivity)
            for (int i = roots.size() - 1; i >= 0; i--) {
                View v = roots.get(i).findViewById(viewId);
                if (v != null && v.isShown()) {
                    found.set(v);
                    return;
                }
            }
        });
        return found.get();
    }

    private void waitForView(int viewId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (findVisibleView(viewId) != null) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for view id=" + viewId);
    }

    private void clickView(final int viewId) {
        final AtomicBoolean clicked = new AtomicBoolean(false);
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View v = roots.get(i).findViewById(viewId);
                if (v != null && v.isShown()) {
                    v.performClick();
                    clicked.set(true);
                    return;
                }
            }
        });
        if (!clicked.get()) throw new AssertionError("View not visible to click: id=" + viewId);
    }

    private void setText(final int viewId, final String text) {
        final AtomicBoolean ok = new AtomicBoolean(false);
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View v = roots.get(i).findViewById(viewId);
                if (v instanceof EditText && v.isShown()) {
                    ((EditText) v).setText(text);
                    ok.set(true);
                    return;
                }
            }
        });
        if (!ok.get()) throw new AssertionError("EditText not visible: id=" + viewId);
    }

    private void setChecked(final int viewId, final boolean checked) {
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View v = roots.get(i).findViewById(viewId);
                if (v instanceof Checkable && v.isShown()) {
                    if (((Checkable) v).isChecked() != checked) {
                        v.performClick();
                    }
                    return;
                }
            }
        });
    }

    private void dismissAnyAlertOk() {
        final String[] labels = new String[]{"V redu", "OK", "Da", "Zapri"};
        for (int pass = 0; pass < 2; pass++) {
            final AtomicBoolean dismissed = new AtomicBoolean(false);
            instr.runOnMainSync(() -> {
                List<View> roots = getAllRootViews();
                if (roots.size() <= 1) return; // only MainActivity open
                View top = roots.get(roots.size() - 1);
                for (String lbl : labels) {
                    View btn = findButtonByText(top, lbl);
                    if (btn != null && btn.isShown()) {
                        btn.performClick();
                        dismissed.set(true);
                        return;
                    }
                }
            });
            if (!dismissed.get()) break;
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        }
    }

    private void waitForRecyclerAndClick(final int rvId, final String textFragment, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        final String needle = textFragment.toUpperCase();
        while (System.currentTimeMillis() < deadline) {
            final AtomicBoolean clicked = new AtomicBoolean(false);
            instr.runOnMainSync(() -> {
                View v = null;
                List<View> roots = getAllRootViews();
                for (int i = roots.size() - 1; i >= 0; i--) {
                    View cand = roots.get(i).findViewById(rvId);
                    if (cand instanceof RecyclerView && cand.isShown()) { v = cand; break; }
                }
                if (!(v instanceof RecyclerView)) return;
                RecyclerView rv = (RecyclerView) v;
                int count = rv.getAdapter() != null ? rv.getAdapter().getItemCount() : 0;
                for (int pos = 0; pos < count; pos++) {
                    RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
                    if (vh != null && collectText(vh.itemView).toUpperCase().contains(needle)) {
                        vh.itemView.performClick();
                        clicked.set(true);
                        return;
                    }
                }
                // Scroll progressively if not in current viewport
                if (count > 0 && rv.getChildCount() > 0) {
                    int last = rv.getChildAdapterPosition(rv.getChildAt(rv.getChildCount() - 1));
                    rv.scrollToPosition(last + 1 < count ? Math.min(count - 1, last + 6) : 0);
                }
            });
            if (clicked.get()) {
                Thread.sleep(100);
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("RecyclerView item not found: " + textFragment);
    }

    private void doubleClickRecyclerIndex(final int rvId, final int index, long timeoutMs) throws Exception {
        waitForRecyclerIndexAndClick(rvId, index, timeoutMs);
        // Wait briefly for notifyDataSetChanged() layout pass, well within 600ms double-tap window
        Thread.sleep(120);
        waitForRecyclerIndexAndClick(rvId, index, 2000);
    }

    private void waitForRecyclerIndexAndClick(final int rvId, final int index, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final AtomicBoolean clicked = new AtomicBoolean(false);
            instr.runOnMainSync(() -> {
                List<View> roots = getAllRootViews();
                for (int i = roots.size() - 1; i >= 0; i--) {
                    View cand = roots.get(i).findViewById(rvId);
                    if (cand instanceof RecyclerView && cand.isShown()) {
                        RecyclerView rv = (RecyclerView) cand;
                        if (rv.getAdapter() != null && rv.getAdapter().getItemCount() > index) {
                            rv.scrollToPosition(index);
                            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(index);
                            if (vh != null) {
                                vh.itemView.performClick();
                                clicked.set(true);
                                return;
                            }
                        }
                    }
                }
            });
            if (clicked.get()) {
                Thread.sleep(100);
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("RecyclerView item at index " + index + " not ready");
    }

    private void runObracunaniStatus4Search(int racunId) throws Exception {
        if (findVisibleView(R.id.btnPreviewZapri) != null) {
            clickView(R.id.btnPreviewZapri);
            Thread.sleep(400);
        }
        goToMize();
        clickView(R.id.btnNavRacuni);
        waitForView(R.id.btnObracunani, 10000);
        clickView(R.id.btnObracunani);
        Thread.sleep(600);
        // Find EditText inside AlertDialog and enter STATUS=4 reference number
        final AtomicBoolean entered = new AtomicBoolean(false);
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                EditText et = findFirstEditText(roots.get(i));
                if (et != null && et.isShown()) {
                    et.setText(String.valueOf(racunId));
                    entered.set(true);
                    return;
                }
            }
        });
        if (!entered.get()) throw new AssertionError("Obračunani dialog EditText not found");
        final AtomicBoolean clickedSearch = new AtomicBoolean(false);
        instr.runOnMainSync(() -> {
            List<View> roots = getAllRootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View btn = findButtonByText(roots.get(i), "Išči");
                if (btn != null && btn.isShown()) {
                    btn.performClick();
                    clickedSearch.set(true);
                    return;
                }
            }
        });
        if (!clickedSearch.get()) throw new AssertionError("Obračunani dialog 'Išči' button not found");
        waitForView(R.id.tvPreviewText, 12000);
        long deadline = System.currentTimeMillis() + 10000;
        boolean loaded = false;
        while (System.currentTimeMillis() < deadline) {
            View tv = findVisibleView(R.id.tvPreviewText);
            if (tv instanceof TextView) {
                final AtomicBoolean ok = new AtomicBoolean(false);
                instr.runOnMainSync(() -> {
                    String txt = ((TextView) tv).getText().toString();
                    if (txt.contains(String.valueOf(racunId))) {
                        ok.set(true);
                    }
                });
                if (ok.get()) { loaded = true; break; }
            }
            Thread.sleep(300);
        }
        if (!loaded) throw new AssertionError("STATUS=4 receipt #" + racunId + " preview did not load");
        clickView(R.id.btnPreviewZapri);
        Thread.sleep(400);
    }

    private static EditText findFirstEditText(View root) {
        if (root instanceof EditText) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                EditText hit = findFirstEditText(vg.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static View findButtonByText(View root, String exactText) {
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            if (cs != null && cs.toString().trim().equalsIgnoreCase(exactText) && root.isClickable()) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View hit = findButtonByText(vg.getChildAt(i), exactText);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        collectTextRec(v, sb);
        return sb.toString();
    }

    private static void collectTextRec(View v, StringBuilder out) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.length() > 0) out.append(cs).append(' ');
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) collectTextRec(vg.getChildAt(i), out);
        }
    }
}
