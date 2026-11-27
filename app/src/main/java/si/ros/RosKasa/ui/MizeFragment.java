package si.ros.RosKasa.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import android.Manifest;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.widget.LinearLayout;
import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.MainActivity;
import si.ros.RosKasa.databinding.FragmentMizeBinding;
import si.ros.RosKasa.models.MizaTp;
import si.ros.RosKasa.models.OsebaTp;
import si.ros.RosKasa.models.RacunSeznamItem;
import si.ros.RosKasa.models.RacunTp;
import si.ros.RosKasa.soap.RosKasaSoapClient;

public class MizeFragment extends Fragment {

    private static final String TAG = "MizeFragment";
    private FragmentMizeBinding binding;
    private AppPreferences prefs;
    private MizeAdapter mizeAdapter;
    private final List<MizeAdapter.MizaItem> currentMizeItems = new ArrayList<>();
    private VoiceOrderController voiceOrderController;
    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted && voiceOrderController != null) {
                    voiceOrderController.onPermissionGranted();
                } else if (getContext() != null) {
                    Toast.makeText(getContext(), si.ros.RosKasa.R.string.ai_err_no_mic_permission, Toast.LENGTH_LONG).show();
                }
            });
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMizeBinding.inflate(inflater, container, false);
        prefs = new AppPreferences(requireContext());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnNavRacuni.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new RacuniFragment());
            }
        });

        binding.btnNavNaroci.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
            }
        });

        binding.btnNavPlacila.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new PlacilaFragment());
            }
        });

        binding.btnOdjava.setVisibility(Globals.getInstance().ispLogout() ? View.VISIBLE : View.INVISIBLE);
        binding.btnOdjava.setOnClickListener(v -> {
            Globals.getInstance().vpisiKronologijo("Odjava osebe (Mize)");
            Globals.getInstance().posljiOfflineKronologijo();
            Globals.getInstance().setTekocaOseba(null);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
            }
        });

        // Dolgi klik na ime natakarja ročno razveljavi disk cache šifrantov (ekvivalent Delphi btnRacprenos)
        binding.tvNatakarInfo.setOnLongClickListener(v -> {
            si.ros.RosKasa.cache.LocalCacheManager.invalidateLargeSifrantiCache(requireContext());
            Toast.makeText(requireContext(), "Predpomnilnik šifrantov razveljavljen — ob naslednji prijavi/odprtju se bodo osvežili s strežnika.", Toast.LENGTH_LONG).show();
            return true;
        });

        setupVoiceOrderAssistant();

        binding.btnMarker.setOnClickListener(v -> odpriNovMarkerNarocilo());
        binding.btnInfo.setOnClickListener(v -> prikaziInfoDialog());

        updateNatakarInfo();
        setupRajoniBar();
        setupMizeGrid();
        // Takoj izriši znane mize + zadnjo znano zasednost iz Globals predpomnilnika (0 ms), nato onResume v ozadju osveži
        lastLoadedOpenAccounts = Globals.getInstance().getCachedOpenAccounts();
        populateMizeGrid(lastLoadedOpenAccounts);
    }

    private void prikaziInfoDialog() {
        Globals g = Globals.getInstance();
        OsebaTp oseba = g.getTekocaOseba();

        String osebaTekst = (oseba != null)
                ? oseba.getOsebaId() + " - " + (oseba.getNaziv() != null ? oseba.getNaziv() : "")
                : "(ni prijavljene osebe)";

        int strmId = (g.getTekociStrm() != null && g.getTekociStrm() > 0)
                ? g.getTekociStrm()
                : ((g.getTocilnicaId() != null && g.getTocilnicaId() > 0) ? g.getTocilnicaId() : g.getHisObrat());
        String strmNaziv = g.getNazivProdajnegaMesta() != null && !g.getNazivProdajnegaMesta().trim().isEmpty()
                ? g.getNazivProdajnegaMesta().trim()
                : (g.getNazivObratPE() != null ? g.getNazivObratPE().trim() : "");
        String strmTekst = strmId > 0
                ? (strmNaziv.isEmpty() ? String.valueOf(strmId) : strmId + " (" + strmNaziv + ")")
                : "-";

        java.util.Set<Integer> cenikIds = new java.util.TreeSet<>();
        for (CenikListAdapter.CenikItem ci : g.getCachedCenik()) {
            if (ci != null && ci.cenikId > 0) {
                cenikIds.add(ci.cenikId);
            }
        }
        String cenikIdTekst;
        if (cenikIds.isEmpty()) {
            cenikIdTekst = g.hasCachedCenik() ? "naložen (brez ID)" : "ni naložen";
        } else {
            StringBuilder sbC = new StringBuilder();
            for (Integer id : cenikIds) {
                if (sbC.length() > 0) sbC.append(", ");
                sbC.append(id);
            }
            cenikIdTekst = sbC.toString();
        }

        String mobileNaziv = (g.getNazivMobile() != null && !g.getNazivMobile().trim().isEmpty())
                ? " (" + g.getNazivMobile().trim() + ")"
                : "";
        int pendingKron = si.ros.RosKasa.cache.KronologijaQueueManager.getPendingCount(requireContext());

        StringBuilder msg = new StringBuilder();
        msg.append("Mobile ID: ").append(g.getMobileId()).append(mobileNaziv).append("\n");
        msg.append("Prijavljen: ").append(osebaTekst).append("\n");
        msg.append("Stroškovno mesto: ").append(strmTekst).append("\n");
        msg.append("Cenik ID: ").append(cenikIdTekst).append("\n");
        msg.append("APK verzija: ").append(g.getAppVersionInfo()).append("\n");
        msg.append("F_POS_ID: ").append(g.getfPosId() != null ? g.getfPosId() : prefs.getfPosId())
           .append("  |  TIPKE_POS_ID: ").append(g.getTipkePosId()).append("\n");
        msg.append("Kronologija v čakanju: ").append(pendingKron);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Informacije o blagajni")
                .setMessage(msg.toString())
                .setNeutralButton("Osveži šifrante", (d, w) -> refreshAllCacheDataNow())
                .setPositiveButton("Zapri", null)
                .show();
    }

    /**
     * Ročna osvežitev vseh predpomnjenih šifrantov (MOBINI, mize, cenik, hitre tipke, dodatki)
     * neposredno s strežnika brez odjave ali ponovnega zagona aplikacije.
     */
    private void refreshAllCacheDataNow() {
        if (!prefs.isRegistered()) {
            Toast.makeText(requireContext(), "Aplikacija ni registrirana na strežnik.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (binding != null && binding.layoutMizeLoading != null) {
            binding.layoutMizeLoading.setVisibility(View.VISIBLE);
        }
        Toast.makeText(requireContext(), "Osvežujem šifrante s strežnika...", Toast.LENGTH_SHORT).show();

        final android.content.Context appCtx = requireContext().getApplicationContext();
        executor.execute(() -> {
            try {
                Globals g = Globals.getInstance();
                si.ros.RosKasa.cache.LocalCacheManager.invalidateLargeSifrantiCache(appCtx);
                g.clearCenikCache();
                g.resetPraznikiChecked();

                int mobId = 1;
                try { mobId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                // 1. MobileSetup (MOBINI + mize + osebe + tarife + prioritete)
                si.ros.RosKasa.models.MobileSetupTp setup = RosKasaSoapClient.getAppConfig(
                        prefs.getServerUrl(), prefs.getToken(), mobId
                );
                if (setup != null) {
                    g.loadFromMobileSetup(setup, mobId);
                    si.ros.RosKasa.cache.LocalCacheManager.saveSifranti(appCtx, setup);
                }

                // 2. Cenik
                int obratId = prefs.getHisObrat();
                List<CenikListAdapter.CenikItem> cenik = RosKasaSoapClient.getCenik(
                        prefs.getServerUrl(), prefs.getToken(), obratId
                );
                if (cenik != null && !cenik.isEmpty()) {
                    g.setCachedCenik(cenik);
                }

                // 3. Hitre tipke
                int tipkePosId = g.getTipkePosId() > 0 ? g.getTipkePosId() : prefs.getTipkePosId();
                List<si.ros.RosKasa.models.HitraTipkaTp> tipke = RosKasaSoapClient.getHitreTipke(
                        prefs.getServerUrl(), prefs.getToken(), tipkePosId
                );
                if (tipke != null && !tipke.isEmpty()) {
                    g.setCachedHitreTipke(tipke);
                }

                // 4. Dodatki
                List<si.ros.RosKasa.models.DodatekTp> dodatki = RosKasaSoapClient.getDodatki(
                        prefs.getServerUrl(), prefs.getToken()
                );
                if (dodatki != null) {
                    g.setCachedDodatki(dodatki);
                }

                si.ros.RosKasa.cache.LocalCacheManager.saveLargeSifranti(appCtx);
                int cenikCount = g.getCachedCenik().size();
                int tipkeCount = g.getCachedHitreTipke().size();
                g.vpisiKronologijo("Ročna osvežitev šifrantov: cenik=" + cenikCount + ", tipke=" + tipkeCount);

                mainHandler.post(() -> {
                    if (!isAdded() || binding == null) return;
                    if (binding.layoutMizeLoading != null) {
                        binding.layoutMizeLoading.setVisibility(View.GONE);
                    }
                    binding.btnVoiceOrder.setVisibility(g.isAiEnabled() ? View.VISIBLE : View.INVISIBLE);
                    setupRajoniBar();
                    loadOpenTablesData();
                    Toast.makeText(requireContext(),
                            "Šifranti osveženi (cenik: " + cenikCount + ", tipke: " + tipkeCount + ").",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Globals.getInstance().vpisiKronologijo("Napaka osveževanja šifrantov: " + e.getMessage());
                mainHandler.post(() -> {
                    if (!isAdded() || binding == null) return;
                    if (binding.layoutMizeLoading != null) {
                        binding.layoutMizeLoading.setVisibility(View.GONE);
                    }
                    Toast.makeText(requireContext(), "Napaka pri osveževanju šifrantov: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateNatakarInfo() {
        OsebaTp tekoca = Globals.getInstance().getTekocaOseba();
        if (tekoca != null && !tekoca.getNaziv().isEmpty()) {
            binding.tvNatakarInfo.setText("👤 " + tekoca.getNaziv() + " (" + tekoca.getInicialke() + ")");
        } else {
            binding.tvNatakarInfo.setText("");
        }
    }

    private int selectedRajon = 0; // 0 = vsi
    private List<RacunSeznamItem> lastLoadedOpenAccounts = new ArrayList<>();

    private void setupRajoniBar() {
        binding.containerRajoni.removeAllViews();
        Globals g = Globals.getInstance();
        List<Integer> rajoni = g.getCachedRajoni();

        if (!g.isRajoni() || rajoni.size() <= 1) {
            binding.scrollRajoni.setVisibility(View.GONE);
            selectedRajon = 0;
            return;
        }
        binding.scrollRajoni.setVisibility(View.VISIBLE);

        // Gumb Vsi
        addRajonButton("Vsi", 0);

        for (Integer r : rajoni) {
            addRajonButton("R " + r, r);
        }

        if (g.getSelectedRajon() >= 0 && (g.getSelectedRajon() == 0 || rajoni.contains(g.getSelectedRajon()))) {
            selectedRajon = g.getSelectedRajon();
        } else if (g.getRajonDefault() > 0 && rajoni.contains(g.getRajonDefault())) {
            selectedRajon = g.getRajonDefault();
        } else {
            selectedRajon = 0;
        }
        updateRajonButtonStyles();
    }

    private void addRajonButton(String label, int rajonId) {
        com.google.android.material.button.MaterialButton btn = new com.google.android.material.button.MaterialButton(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (76 * getResources().getDisplayMetrics().density),
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        lp.setMargins(4, 0, 4, 0);
        btn.setLayoutParams(lp);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setTag(rajonId);
        btn.setPadding(4, 0, 4, 0);
        btn.setCornerRadius((int) (4 * getResources().getDisplayMetrics().density));
        btn.setOnClickListener(v -> {
            selectedRajon = rajonId;
            Globals.getInstance().setSelectedRajon(rajonId);
            updateRajonButtonStyles();
            populateMizeGrid(lastLoadedOpenAccounts);
        });
        binding.containerRajoni.addView(btn);
    }

    private void updateRajonButtonStyles() {
        for (int i = 0; i < binding.containerRajoni.getChildCount(); i++) {
            View child = binding.containerRajoni.getChildAt(i);
            if (child instanceof com.google.android.material.button.MaterialButton) {
                com.google.android.material.button.MaterialButton b = (com.google.android.material.button.MaterialButton) child;
                int rId = (int) b.getTag();
                if (rId == selectedRajon) {
                    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2196F3));
                    b.setTextColor(0xFFFFFFFF);
                } else {
                    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE0E0E0));
                    b.setTextColor(0xFF000000);
                }
            }
        }
    }

    private void setupMizeGrid() {
        mizeAdapter = new MizeAdapter(miza -> {
            Globals g = Globals.getInstance();
            // Preveri pravico SVidiVseRacune / SVidiVsaNarocila, če je mizo odprl drug natakar
            if (miza.isOccupied && miza.kasiralOsebaId > 0 && miza.kasiralOsebaId != g.getTekocaOsebaId()) {
                if (!g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVseRacune)
                        && !g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVsaNarocila)) {
                    String natakarIme = !miza.kasiralNaziv.isEmpty() ? miza.kasiralNaziv : miza.kasiralInicialke;
                    Toast.makeText(requireContext(), "Miza je zasedena (odprl: " + natakarIme + ")!\nNimate pravice za odpiranje računov drugih natakarjev.", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            prefs.setActiveMarker(miza.naziv);
            prefs.setActiveRacunId(miza.racunId);
            Globals.getInstance().setActiveRacunId(miza.racunId);
            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Izbrana " + miza.naziv + (miza.racunId > 0 ? " (Račun #" + miza.racunId + ")" : " (Nova miza)"), Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivity) {
                if (miza.isPlacano) {
                    ((MainActivity) getActivity()).navigateToFragment(new PlacilaFragment());
                } else {
                    ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
                }
            }
        });

        int spanCount = DeviceLayoutHelper.getMizeSpanCount(requireContext());
        binding.rvMizeGrid.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        binding.rvMizeGrid.setAdapter(mizeAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateNatakarInfo();
        loadOpenTablesData();
    }

    private String calculateOdDatum() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -3);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.getDefault());
        return sdf.format(cal.getTime());
    }

    private volatile boolean isLoadingTables = false;

    private void loadOpenTablesData() {
        if (!prefs.isRegistered()) {
            populateMizeGrid(new ArrayList<>());
            return;
        }
        if (isLoadingTables) return;
        isLoadingTables = true;
        if (binding != null && binding.layoutMizeLoading != null) {
            binding.layoutMizeLoading.setVisibility(View.VISIBLE);
        }

        String odDatum = calculateOdDatum();

        executor.execute(() -> {
            try {
                // Klic getRacuniSeznam za STATUS=1: Vidi vse račune ALI Vidi Vsa Narocila vidita vse STATUS=1; sicer le lastne
                Globals g = Globals.getInstance();
                boolean vidiVsaOdprta = g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVseRacune)
                        || g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVsaNarocila)
                        || g.isOdpriVseRacune();
                int pOseba = vidiVsaOdprta ? 0 : g.getTekocaOsebaId();
                int strmId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0)
                        ? g.getTocilnicaId() : prefs.getTocilnicaId();
                List<RacunSeznamItem> openAccounts = RosKasaSoapClient.getRacuniForMize(
                        prefs.getServerUrl(),
                        prefs.getToken(),
                        prefs.getMobileId(),
                        1,
                        odDatum,
                        pOseba,
                        strmId
                );

                mainHandler.post(() -> {
                    isLoadingTables = false;
                    if (binding != null && binding.layoutMizeLoading != null) {
                        binding.layoutMizeLoading.setVisibility(View.GONE);
                    }
                    if (binding != null) {
                        List<RacunSeznamItem> safeList = openAccounts != null ? openAccounts : new ArrayList<>();
                        lastLoadedOpenAccounts = safeList;
                        Globals.getInstance().setCachedOpenAccounts(safeList);
                        populateMizeGrid(safeList);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Napaka pri nalaganju zasednosti miz iz getRacuniSeznam", e);
                Globals.getInstance().vpisiNapakoKronologija("Mize getRacuniForMize", e);
                mainHandler.post(() -> {
                    isLoadingTables = false;
                    if (binding != null && binding.layoutMizeLoading != null) {
                        binding.layoutMizeLoading.setVisibility(View.GONE);
                    }
                    if (binding != null) {
                        populateMizeGrid(new ArrayList<>());
                    }
                });
            }
        });
    }

    private boolean isRacunPlacan(RacunSeznamItem acc) {
        if (acc == null) return false;
        if (acc.isPlacan() || acc.hasAnyPayment()) return true;
        Globals g = Globals.getInstance();
        BigDecimal cachedPl = g.getRacunPlacano(acc.getRacunId());
        if (cachedPl != null && cachedPl.compareTo(BigDecimal.ZERO) > 0) {
            return true;
        }
        RacunTp cur = g.getCurrentRacun();
        if (cur != null && cur.getRacunId() == acc.getRacunId()) {
            if (cur.getPlacano() != null && cur.getPlacano().compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
            if (cur.getRacPlaci() != null) {
                for (si.ros.RosKasa.models.PlaciloTp pl : cur.getRacPlaci()) {
                    if (pl != null && !pl.isRowDeleted() && pl.getPlaciloId() > 0 && pl.getPlaciloId() != 99) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BigDecimal getRacunPlacanoZnesek(RacunSeznamItem acc) {
        if (acc == null) return BigDecimal.ZERO;
        if (acc.getPlacano() != null && acc.getPlacano().compareTo(BigDecimal.ZERO) > 0) {
            return acc.getPlacano();
        }
        Globals g = Globals.getInstance();
        BigDecimal p = g.getRacunPlacano(acc.getRacunId());
        if (p != null && p.compareTo(BigDecimal.ZERO) > 0) {
            return p;
        }
        RacunTp cur = g.getCurrentRacun();
        if (cur != null && cur.getRacunId() == acc.getRacunId() && cur.getPlacano() != null) {
            return cur.getPlacano();
        }
        return BigDecimal.ZERO;
    }

    private void populateMizeGrid(List<RacunSeznamItem> openAccounts) {
        this.lastLoadedOpenAccounts = openAccounts != null ? openAccounts : new ArrayList<>();

        // Filtriramo le aktivne odprte račune (STATUS == 1 ter brez stornacije / negativnega zneska)
        List<RacunSeznamItem> activeOpenAccounts = new ArrayList<>();
        if (openAccounts != null) {
            for (RacunSeznamItem acc : openAccounts) {
                boolean isStorno = acc != null && (
                        (acc.getStornoRacunId() != null && acc.getStornoRacunId() > 0)
                        || (acc.getStornoOriginal() != null && acc.getStornoOriginal() > 0)
                        || (acc.getZnesek() != null && acc.getZnesek().compareTo(BigDecimal.ZERO) < 0)
                );
                if (acc != null && acc.getStatus() != null && acc.getStatus() == 1 && !isStorno) {
                    activeOpenAccounts.add(acc);
                }
            }
        }

        Set<Integer> processedRacunIds = new HashSet<>();
        Map<String, List<RacunSeznamItem>> markerMap = new HashMap<>();

        for (RacunSeznamItem acc : activeOpenAccounts) {
            String markerStr = (acc.getMarker() != null) ? acc.getMarker().trim() : "";
            String key = markerStr.toLowerCase(Locale.getDefault());
            if (!markerMap.containsKey(key)) {
                markerMap.put(key, new ArrayList<>());
            }
            markerMap.get(key).add(acc);
        }

        List<MizeAdapter.MizaItem> mizeList = new ArrayList<>();
        Map<String, Integer> markerCounts = new HashMap<>();

        List<MizaTp> cachedMize = Globals.getInstance().getCachedMize();
        if (cachedMize != null && !cachedMize.isEmpty()) {
            // Uporabi šifrant miz iz MobileSetup
            List<MizaTp> filtered = new ArrayList<>();
            for (MizaTp m : cachedMize) {
                if (selectedRajon == 0 || (m.getRajon() != null && m.getRajon() == selectedRajon)) {
                    filtered.add(m);
                }
            }
            filtered.sort((m1, m2) -> Integer.compare(m1.getZap() != null ? m1.getZap() : 0, m2.getZap() != null ? m2.getZap() : 0));

            for (MizaTp m : filtered) {
                String mizaName = m.getNaziv();
                String keyFull = mizaName.toLowerCase(Locale.getDefault());
                String keyNum = mizaName.replaceAll("[^0-9]", "");

                List<RacunSeznamItem> matches = null;
                if (markerMap.containsKey(keyFull) && !markerMap.get(keyFull).isEmpty()) {
                    matches = markerMap.get(keyFull);
                } else if (!keyNum.isEmpty() && markerMap.containsKey(keyNum) && !markerMap.get(keyNum).isEmpty()) {
                    matches = markerMap.get(keyNum);
                }

                if (matches != null && !matches.isEmpty()) {
                    RacunSeznamItem firstAcc = matches.get(0);
                    int kasiralId = firstAcc.getKasiral() != null ? firstAcc.getKasiral() : 0;
                    OsebaTp kasiralOseba = Globals.getInstance().najdiOseboById(kasiralId);
                    String kasiralNaziv = kasiralOseba != null ? kasiralOseba.getNaziv() : "";
                    String kasiralIni = kasiralOseba != null ? kasiralOseba.getInicialke() : "";
                    boolean isMy = kasiralId > 0 && kasiralId == Globals.getInstance().getTekocaOsebaId();
                    boolean isPlacano = isRacunPlacan(firstAcc);
                    BigDecimal placano = getRacunPlacanoZnesek(firstAcc);

                    mizeList.add(new MizeAdapter.MizaItem(mizaName, true, firstAcc.getZnesek(), firstAcc.getRacunId(),
                            kasiralId, kasiralNaziv, kasiralIni, isMy, isPlacano, placano));
                    processedRacunIds.add(firstAcc.getRacunId());
                    markerCounts.put(mizaName.toLowerCase(Locale.getDefault()), 1);
                } else {
                    mizeList.add(new MizeAdapter.MizaItem(mizaName, false, BigDecimal.ZERO, 0));
                }
            }
        } else {
            // Fallback: standardne mize 1..30
            for (int i = 1; i <= 30; i++) {
                String mizaName = "Miza " + i;
                String keyFull = mizaName.toLowerCase(Locale.getDefault());
                String keyNum = String.valueOf(i);

                List<RacunSeznamItem> matches = null;
                if (markerMap.containsKey(keyFull) && !markerMap.get(keyFull).isEmpty()) {
                    matches = markerMap.get(keyFull);
                } else if (markerMap.containsKey(keyNum) && !markerMap.get(keyNum).isEmpty()) {
                    matches = markerMap.get(keyNum);
                }

                if (matches != null && !matches.isEmpty()) {
                    RacunSeznamItem firstAcc = matches.get(0);
                    int kasiralId = firstAcc.getKasiral() != null ? firstAcc.getKasiral() : 0;
                    OsebaTp kasiralOseba = Globals.getInstance().najdiOseboById(kasiralId);
                    String kasiralNaziv = kasiralOseba != null ? kasiralOseba.getNaziv() : "";
                    String kasiralIni = kasiralOseba != null ? kasiralOseba.getInicialke() : "";
                    boolean isMy = kasiralId > 0 && kasiralId == Globals.getInstance().getTekocaOsebaId();
                    boolean isPlacano = isRacunPlacan(firstAcc);
                    BigDecimal placano = getRacunPlacanoZnesek(firstAcc);

                    mizeList.add(new MizeAdapter.MizaItem(mizaName, true, firstAcc.getZnesek(), firstAcc.getRacunId(),
                            kasiralId, kasiralNaziv, kasiralIni, isMy, isPlacano, placano));
                    processedRacunIds.add(firstAcc.getRacunId());
                    markerCounts.put(mizaName.toLowerCase(Locale.getDefault()), 1);
                } else {
                    mizeList.add(new MizeAdapter.MizaItem(mizaName, false, BigDecimal.ZERO, 0));
                }
            }
        }

        // 2. Obdelava dinamičnih prostih markerjev (rajon < 0): vedno vidni pri vseh rajonih!
        // Preskočimo le račune, ki pripadajo znani mizi iz drugega rajona.
        Set<String> knownTableMarkers = new HashSet<>();
        if (cachedMize != null) {
            for (MizaTp m : cachedMize) {
                if (m != null && m.getNaziv() != null) {
                    String mn = m.getNaziv().trim().toLowerCase(Locale.getDefault());
                    knownTableMarkers.add(mn);
                    String mNum = mn.replaceAll("[^0-9]", "");
                    if (!mNum.isEmpty()) knownTableMarkers.add(mNum);
                }
            }
        }
        {
            int emptyMarkerIndex = 1;

            for (RacunSeznamItem acc : activeOpenAccounts) {
                if (processedRacunIds.contains(acc.getRacunId())) {
                    continue;
                }

                String rawMarker = (acc.getMarker() != null) ? acc.getMarker().trim() : "";
                String upperMarker = rawMarker.toUpperCase(Locale.ROOT);
                if (upperMarker.startsWith("Z:") || upperMarker.contains("ZDRU")) {
                    continue;
                }
                // Če gledamo specifičen rajon in račun pripada fiksni mizi iz drugega rajona, ga ne prikažemo
                if (selectedRajon != 0 && !rawMarker.isEmpty() && knownTableMarkers.contains(rawMarker.toLowerCase(Locale.getDefault()))) {
                    continue;
                }
                String buttonName;

                if (rawMarker.isEmpty()) {
                    buttonName = "Brez oznake #" + (emptyMarkerIndex++);
                } else {
                    String markerKey = rawMarker.toLowerCase(Locale.getDefault());
                    int currentCount = markerCounts.containsKey(markerKey) ? markerCounts.get(markerKey) + 1 : 1;
                    markerCounts.put(markerKey, currentCount);

                    if (currentCount == 1) {
                        buttonName = rawMarker;
                    } else {
                        buttonName = rawMarker + " (#" + currentCount + ")";
                    }
                }

                int kasiralId = acc.getKasiral() != null ? acc.getKasiral() : 0;
                OsebaTp kasiralOseba = Globals.getInstance().najdiOseboById(kasiralId);
                String kasiralNaziv = kasiralOseba != null ? kasiralOseba.getNaziv() : "";
                String kasiralIni = kasiralOseba != null ? kasiralOseba.getInicialke() : "";
                boolean isMy = kasiralId > 0 && kasiralId == Globals.getInstance().getTekocaOsebaId();
                boolean isPlacano = isRacunPlacan(acc);
                BigDecimal placano = getRacunPlacanoZnesek(acc);

                mizeList.add(new MizeAdapter.MizaItem(buttonName, true, acc.getZnesek(), acc.getRacunId(),
                        kasiralId, kasiralNaziv, kasiralIni, isMy, isPlacano, placano));
                processedRacunIds.add(acc.getRacunId());
            }
        }

        currentMizeItems.clear();
        currentMizeItems.addAll(mizeList);
        mizeAdapter.setItems(mizeList);
    }

    private void setupVoiceOrderAssistant() {
        boolean aiEnabled = Globals.getInstance().isAiEnabled();
        binding.btnVoiceOrder.setVisibility(aiEnabled ? View.VISIBLE : View.INVISIBLE);

        voiceOrderController = new VoiceOrderController(
                requireContext(),
                binding.voiceAssistantBar.getRoot(),
                new VoiceOrderController.Host() {
                    @Override
                    public boolean isTableSelectionScreen() {
                        return true;
                    }

                    @Override
                    public String getCurrentMiza() {
                        return "";
                    }

                    @Override
                    public List<si.ros.RosKasa.ai.VoiceOrderModels.CurrentOrderLine> getCurrentOrderLines() {
                        return java.util.Collections.emptyList();
                    }

                    @Override
                    public void onAiSelectTable(String mizaName,
                                                si.ros.RosKasa.ai.VoiceOrderModels.VoiceOrderMode mode,
                                                si.ros.RosKasa.ai.VoiceOrderModels.AiActionResult initialResult) {
                        openTableFromVoice(mizaName, mode, initialResult);
                    }

                    @Override
                    public int onAiAddItems(List<si.ros.RosKasa.ai.VoiceOrderModels.AiOrderItem> items) {
                        return 0;
                    }

                    @Override
                    public int onAiModifyItems(List<si.ros.RosKasa.ai.VoiceOrderModels.AiModifyItem> items) {
                        return 0;
                    }

                    @Override
                    public void onRequestRecordAudioPermission() {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                    }
                }
        );

        binding.btnVoiceOrder.setOnClickListener(v -> {
            if (voiceOrderController != null) {
                voiceOrderController.toggle();
            }
        });
    }

    private void openTableFromVoice(String requestedMiza,
                                    si.ros.RosKasa.ai.VoiceOrderModels.VoiceOrderMode mode,
                                    si.ros.RosKasa.ai.VoiceOrderModels.AiActionResult initialResult) {
        if (requestedMiza == null || requestedMiza.trim().isEmpty()) return;
        String cleanReq = requestedMiza.trim();
        String reqLower = cleanReq.toLowerCase(Locale.getDefault());
        String reqDigits = cleanReq.replaceAll("[^0-9]", "");

        MizeAdapter.MizaItem matched = null;
        for (MizeAdapter.MizaItem item : currentMizeItems) {
            if (item.naziv.equalsIgnoreCase(cleanReq)) {
                matched = item;
                break;
            }
        }
        if (matched == null && !reqDigits.isEmpty()) {
            for (MizeAdapter.MizaItem item : currentMizeItems) {
                String itemDigits = item.naziv.replaceAll("[^0-9]", "");
                if (!itemDigits.isEmpty() && itemDigits.equals(reqDigits)) {
                    matched = item;
                    break;
                }
            }
        }
        if (matched == null) {
            for (MizeAdapter.MizaItem item : currentMizeItems) {
                if (item.naziv.toLowerCase(Locale.getDefault()).contains(reqLower)) {
                    matched = item;
                    break;
                }
            }
        }

        Globals g = Globals.getInstance();
        String targetMarker;
        int targetRacunId;

        if (matched != null) {
            if (matched.isOccupied && matched.kasiralOsebaId > 0 && matched.kasiralOsebaId != g.getTekocaOsebaId()) {
                if (!g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVseRacune)
                        && !g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVsaNarocila)) {
                    String natakarIme = !matched.kasiralNaziv.isEmpty() ? matched.kasiralNaziv : matched.kasiralInicialke;
                    Toast.makeText(requireContext(), "Miza je zasedena (odprl: " + natakarIme + ")!", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            targetMarker = matched.naziv;
            targetRacunId = (mode == si.ros.RosKasa.ai.VoiceOrderModels.VoiceOrderMode.NEW) ? 0 : matched.racunId;
        } else {
            targetMarker = Globals.preveriMarker(cleanReq);
            targetRacunId = 0;
        }

        prefs.setActiveMarker(targetMarker);
        prefs.setActiveRacunId(targetRacunId);
        g.setActiveRacunId(targetRacunId);
        if (targetRacunId == 0) {
            g.setCurrentRacun(null);
        }

        si.ros.RosKasa.ai.VoiceOrderSession session = si.ros.RosKasa.ai.VoiceOrderSession.getInstance();
        session.setActiveMiza(targetMarker);
        session.setPendingResultForNarocila(initialResult, true);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
        }
    }

    private void odpriNovMarkerNarocilo() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle("Nov marker");
        builder.setMessage("Vnesite marker za novo naročilo:");
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("npr. Šank, Terasa 1, VIP...");
        input.setPadding(32, 16, 32, 16);
        builder.setView(input);

        builder.setPositiveButton("Odpri", (d, w) -> {
            String raw = input.getText() != null ? input.getText().toString() : "";
            String clean = Globals.preveriMarker(raw);
            if (clean.isEmpty()) {
                Toast.makeText(requireContext(), "Marker ne sme biti prazen!", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.setActiveMarker(clean);
            prefs.setActiveRacunId(0);
            Globals.getInstance().setActiveRacunId(0);
            Globals.getInstance().setCurrentRacun(null);

            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Odprto naročilo za marker: " + clean, Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
            }
        });
        builder.setNegativeButton("Prekliči", null);
        builder.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (voiceOrderController != null) {
            voiceOrderController.destroy();
            voiceOrderController = null;
        }
        binding = null;
    }
}
